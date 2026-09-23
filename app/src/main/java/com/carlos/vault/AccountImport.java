package com.carlos.vault;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 账号批量导入：解析 → 校验 → 去重/冲突判定 → 事务化提交。
 *
 * <p><b>本类刻意不 import 任何 android.* </b>（只依赖 {@link AccountStore.Account} 这个纯数据类
 * 与 {@link AccountStore#extractFpid(String)} 这个纯静态方法），因此可以直接用 javac 在宿主机上
 * 干跑验证解析逻辑，不必启动模拟器。
 *
 * <h3>支持的输入格式</h3>
 * <p><b>(A) 既有单行格式</b>（兼容）：{@code 备注|号ID----token} 或 {@code 号ID----token}，
 * 分隔符半角 {@code |} 与全角 {@code ｜} 均可，号ID 取末尾连续数字。
 *
 * <p><b>(B) 表格粘贴格式</b>（本次重点，一条记录跨两行）：
 * <pre>
 * 备注⇥token
 * 小气气气⇥account=107574941
 * sdkToken=1D7RtRhOgzPdHdL9QZTmvpooY6fgPGtaQ0itzWP3SRr2GejPKY
 * </pre>
 * 规则：首行含"备注"与"token"字样且不含数字 ⇒ 当表头跳过；含 {@code account=} 的行开启一条新
 * 记录（label = 第一个 TAB 之前的文本，fpid = {@code account=} 之后的值取末尾数字）；
 * 含 {@code sdkToken=} 但不含 {@code account=} 的行作为上一条记录的 token 续行；
 * 单行里两者都有 ⇒ 完整记录（兼容表格粘成一行的情况）。续行记录的行号统一记为起始行号。
 *
 * <h3>事务性</h3>
 * <p>解析与校验全在内存完成，账号列表只在 {@link #apply} 开始时加载一次，最终只做
 * <b>一次</b>落盘（{@link AccountStore#saveSync(List)}，内部 {@code commit()}）。
 * 落盘前任何异常 ⇒ 一条都不写。
 *
 * <h3>不变量</h3>
 * <p>本类不触碰 {@code last_entry} / {@code live_entry_id} / {@code snap_fp_*} / 任何快照文件。
 */
public final class AccountImport {

    /** 与库内已有账号冲突时的处理策略。 */
    public enum ConflictPolicy {
        /** 跳过（默认）：绝不覆盖任何已有数据。 */
        SKIP,
        /** 覆盖：用户显式选择后才写入。 */
        OVERWRITE
    }

    /** 解析出的一行（或一条跨两行的记录）。 */
    public static class Row {
        /** 行号（1 起算；续行记录记为起始行号）。 */
        public int lineNo;
        public String label;
        public String fpid;
        public String token;
        /** 非空 ⇒ 该条校验/解析失败，绝不写入。 */
        public String error;
        /** 非空 ⇒ 与库内已有账号冲突（仅在 SKIP 策略下会填）。 */
        public String conflict;
        /** 原文，供导出失败明细时回查。 */
        public String raw;

        public boolean isOk() {
            return error == null && conflict == null;
        }
    }

    /** 一次导入的汇总结果。 */
    public static class Result {
        public int total;
        public int ok;
        public int failed;
        public int skipped;
        public List<Row> failures = new ArrayList<>();
        public List<Row> conflicts = new ArrayList<>();
    }

    private AccountImport() {
    }

    // ---------------- 解析（纯函数，不落盘） ----------------

    /**
     * 解析整段文本为 Row 列表。本方法<b>不写任何数据</b>，只做解析 + 逐条校验 + 批内去重。
     *
     * @param text 原始输入（可能带 BOM / CRLF / 空行 / 注释行）
     * @return 每行一条 Row；失败条目已填好 {@link Row#error}
     */
    public static List<Row> parse(String text) {
        List<Row> rows = new ArrayList<>();
        if (text == null) return rows;

        // —— (C) 通用清洗：去 BOM、换行归一 ——
        String src = text;
        if (!src.isEmpty() && src.charAt(0) == '\uFEFF') src = src.substring(1);
        src = src.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = src.split("\n", -1);

        /** 上一条"已开启但还没收到 token"的记录（由 account= 行开启）。 */
        Row pending = null;
        boolean firstContent = true;

        for (int i = 0; i < lines.length; i++) {
            String rawLine = lines[i];
            int lineNo = i + 1;
            String line = rawLine.trim();

            // 空行 / 注释行：跳过
            if (line.isEmpty()) continue;
            if (line.charAt(0) == '#') continue;

            // 首行表头：含"备注"（或"账号"）与 "token" 字样、且不含数字 ⇒ 跳过
            if (firstContent) {
                firstContent = false;
                if (isHeader(line)) continue;
            }

            String tokenKey = tokenKeyOf(line);
            boolean hasAccount = line.contains("account=");
            boolean hasToken = tokenKey != null;

            if (hasAccount) {
                // 开启一条新记录
                Row r = new Row();
                r.lineNo = lineNo;
                r.raw = line;
                r.label = labelBeforeTab(line);
                String av = valueAfter(line, "account=");
                r.fpid = AccountStore.extractFpid(av);
                // 兜底：account= 后面没数字时，从整行取末尾连续数字
                if (r.fpid.isEmpty()) r.fpid = AccountStore.extractFpid(line);
                if (hasToken) {
                    r.token = valueAfter(line, tokenKey);
                    pending = null;
                } else {
                    r.token = null;   // 等下一行的 sdkToken= 续上；没等到则由校验判失败
                    pending = r;
                }
                rows.add(r);
                continue;
            }

            if (hasToken) {
                // 续行：只作为上一条记录的 token
                if (pending == null || (pending.token != null && !pending.token.isEmpty())) {
                    Row f = new Row();
                    f.lineNo = lineNo;
                    f.raw = line;
                    f.error = "token 行没有对应的账号行（第 " + lineNo + " 行）";
                    rows.add(f);
                } else {
                    pending.token = valueAfter(line, tokenKey);
                    pending.raw = pending.raw + " ⏎ " + line;
                    pending = null;
                }
                continue;
            }

            // 既不是 account= 也不是 token= ⇒ 走老的单行格式 (A)
            rows.add(parseLegacy(line, lineNo));
            pending = null;
        }

        // 走到这里仍未闭合的 account= 行：token 为空 ⇒ 由 validate 判为"token 缺失"
        for (Row r : rows) validate(r);
        dedupe(rows);
        return rows;
    }

    /** 老格式 (A)：{@code 备注|号ID----token} 或 {@code 号ID----token} */
    private static Row parseLegacy(String line, int lineNo) {
        Row r = new Row();
        r.lineNo = lineNo;
        r.raw = line;
        int sep = line.indexOf("----");
        if (sep <= 0) {
            r.error = "无法解析该行（不是 备注|号ID----token，也不是表格格式）";
            return r;
        }
        String head = line.substring(0, sep);
        String token = line.substring(sep + 4).trim();
        String label = null;
        int bar = Math.max(head.lastIndexOf('|'), head.lastIndexOf('｜'));
        if (bar > 0) {
            label = head.substring(0, bar).trim();
            head = head.substring(bar + 1).trim();
        }
        r.fpid = AccountStore.extractFpid(head);
        r.token = token;
        r.label = label == null ? "" : label;
        return r;
    }

    /** 逐条校验：不通过就填 error，绝不写入该条。 */
    private static void validate(Row r) {
        if (r.error != null) return;

        if (r.fpid == null || r.fpid.isEmpty() || !isDigits(r.fpid)) {
            r.error = "号ID为空或不是数字";
            return;
        }
        String tk = (r.token == null) ? "" : r.token.trim();
        if (tk.isEmpty() || tk.contains("----") || hasWhitespace(tk) || tk.length() < 16) {
            r.error = "token 缺失或格式异常";
            return;
        }
        r.token = tk;
        // 备注为空 ⇒ 默认用号ID 作为 label（沿用 AccountStore.importLine 的既有约定）
        if (r.label == null || r.label.trim().isEmpty()) r.label = r.fpid;
        else r.label = r.label.trim();
    }

    /** 批内重复：同一 fpid 出现多次 ⇒ 保留第一条，后续标记失败。 */
    private static void dedupe(List<Row> rows) {
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (Row r : rows) {
            if (r.error != null) continue;
            if (r.fpid == null || r.fpid.isEmpty()) continue;
            Integer prev = seen.get(r.fpid);
            if (prev != null) {
                r.error = "与第 " + prev + " 行重复（同一号ID）";
                continue;
            }
            seen.put(r.fpid, r.lineNo);
        }
    }

    // ---------------- 事务化提交 ----------------

    /**
     * 把解析结果按策略提交落盘。
     *
     * <p>账号列表只在开始时加载一次，全部判定在内存完成，最终只调用一次
     * {@link AccountStore#saveSync(List)}。落盘前任何异常 ⇒ 一条都不写。
     *
     * @param s       账号库
     * @param rows    {@link #parse} 的产物
     * @param policy  冲突策略（{@link ConflictPolicy#SKIP} 为默认，绝不覆盖）
     */
    public static Result apply(AccountStore s, List<Row> rows, ConflictPolicy policy) {
        Result res = new Result();
        if (rows == null) return res;
        res.total = rows.size();
        if (policy == null) policy = ConflictPolicy.SKIP;

        if (s == null) {
            res.failed = res.total;
            Row r = new Row();
            r.lineNo = 0;
            r.raw = "";
            r.error = "导入失败（未写入任何数据）：账号库不可用";
            res.failures.add(r);
            return res;
        }

        try {
            // —— 只在开始时加载一次 ——
            List<AccountStore.Account> list = new ArrayList<>(s.all());
            Map<String, AccountStore.Account> byFpid = new LinkedHashMap<>();
            Map<String, AccountStore.Account> byId = new LinkedHashMap<>();
            for (AccountStore.Account a : list) {
                if (a == null) continue;
                if (a.fpid != null && !a.fpid.isEmpty()) byFpid.put(a.fpid, a);
                if (a.id != null && !a.id.isEmpty() && !byId.containsKey(a.id)) byId.put(a.id, a);
            }

            List<AccountStore.Account> adds = new ArrayList<>();
            Map<String, Integer> batchSeen = new LinkedHashMap<>();
            boolean overwritten = false;

            for (Row r : rows) {
                if (r.error != null) {
                    res.failed++;
                    res.failures.add(r);
                    continue;
                }
                // 批内重复（parse 已判过一次，这里再兜一层，防止调用方自己拼 rows）
                Integer prev = batchSeen.get(r.fpid);
                if (prev != null) {
                    r.error = "与第 " + prev + " 行重复（同一号ID）";
                    res.failed++;
                    res.failures.add(r);
                    continue;
                }
                batchSeen.put(r.fpid, r.lineNo);

                // —— 与库内已有冲突 ——
                AccountStore.Account exist = byFpid.get(r.fpid);
                if (exist == null) exist = byId.get(r.fpid);
                if (exist != null) {
                    r.conflict = "号ID " + r.fpid + " 已存在（" + safeLabel(exist.label) + "）";
                    if (policy == ConflictPolicy.OVERWRITE) {
                        exist.fpid = r.fpid;
                        exist.token = r.token;
                        exist.label = r.label;
                        overwritten = true;
                        res.ok++;
                    } else {
                        // 默认策略：跳过，一个字节都不改
                        res.skipped++;
                        res.conflicts.add(r);
                    }
                    continue;
                }

                AccountStore.Account a = new AccountStore.Account();
                a.id = r.fpid;
                a.fpid = r.fpid;
                a.token = r.token;
                a.label = r.label;
                a.lastLogin = 0L;
                adds.add(a);
                byFpid.put(a.fpid, a);
                byId.put(a.id, a);
                res.ok++;
            }

            // —— 最终只做一次落盘 ——
            if (!adds.isEmpty() || overwritten) {
                list.addAll(adds);
                s.saveSync(list);
            }
        } catch (Throwable t) {
            // 落盘前/落盘中的任何异常 ⇒ 一条都不算成功（也就不产生"看起来成功"的脏数据）
            res.ok = 0;
            res.skipped = 0;
            res.failed = res.total;
            res.failures.clear();
            res.conflicts.clear();
            Row r = new Row();
            r.lineNo = 0;
            r.raw = "";
            r.error = "导入失败（未写入任何数据）：" + t;
            res.failures.add(r);
        }
        return res;
    }

    // ---------------- 文本小工具 ----------------

    /** 表头判定：含"备注/账号"与 "token" 字样、且不含任何数字。 */
    private static boolean isHeader(String line) {
        boolean hasName = line.contains("备注") || line.contains("账号") || line.contains("名称");
        if (!hasName) return false;
        String lower = line.toLowerCase();
        if (!lower.contains("token")) return false;
        return !hasDigit(line);
    }

    /** 取本行的 token 键：优先 {@code sdkToken=}，其次 {@code token=}；无则返回 null。 */
    private static String tokenKeyOf(String line) {
        if (line.contains("sdkToken=")) return "sdkToken=";
        if (line.contains("token=")) return "token=";
        return null;
    }

    /** label = 第一个 TAB 之前的文本；没有 TAB 则留空。 */
    private static String labelBeforeTab(String line) {
        int tab = line.indexOf('\t');
        if (tab > 0) return line.substring(0, tab).trim();
        return "";
    }

    /** 取 {@code key} 之后的值，截到下一个空白/TAB 为止。 */
    private static String valueAfter(String line, String key) {
        int i = line.indexOf(key);
        if (i < 0) return "";
        String v = line.substring(i + key.length()).trim();
        int cut = v.length();
        for (int k = 0; k < v.length(); k++) {
            char c = v.charAt(k);
            if (c == '\t' || c == ' ' || c == '\u3000') { cut = k; break; }
        }
        return v.substring(0, cut).trim();
    }

    private static boolean hasDigit(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) return true;
        }
        return false;
    }

    private static boolean isDigits(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private static boolean hasWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return true;
        }
        return false;
    }

    private static String safeLabel(String label) {
        if (label == null || label.trim().isEmpty()) return "未命名";
        return label.trim();
    }
}
