package com.carlos.vault;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 账号库：SharedPreferences 存储 [{fpid, token, label, lastLogin}] */
public class AccountStore {

    public static class Account {
        public String id;      // 入口唯一标识（有号ID用号ID，否则自动生成）
        public String fpid;    // 可选：号ID（设置token后填充）
        public String token;   // 可选：登录token
        public String label;
        public long lastLogin;
    }

    private final SharedPreferences sp;

    public AccountStore(Context ctx) {
        sp = ctx.getSharedPreferences("account_vault", Context.MODE_PRIVATE);
    }

    public List<Account> all() {
        List<Account> out = new ArrayList<>();
        boolean changed = false;
        java.util.Map<String, String> idRemap = new java.util.HashMap<>();
        try {
            JSONArray arr = new JSONArray(sp.getString("accounts", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Account a = new Account();
                a.id = o.optString("id");
                a.fpid = o.optString("fpid");
                a.token = o.optString("token");
                a.label = o.optString("label", a.fpid.isEmpty() ? "入口" : a.fpid);
                a.lastLogin = o.optLong("lastLogin");
                if (a.id.isEmpty()) a.id = a.fpid.isEmpty() ? ("e" + a.hashCode()) : a.fpid;
                // 修复旧版批量导入混入备注的号ID（如 "kk大王｜⇥81444913" → "81444913"）
                if (!a.fpid.isEmpty() && !a.fpid.matches("[0-9]+")) {
                    String fixed = extractFpid(a.fpid);
                    if (!fixed.isEmpty() && !fixed.equals(a.fpid)) {
                        // 从脏id里恢复备注（"kk大王｜⇥81444913" → "kk大王"）
                        if (a.fpid.equals(a.label)) {
                            int bar = Math.max(a.fpid.lastIndexOf('|'), a.fpid.lastIndexOf('｜'));
                            if (bar > 0) {
                                String lbl = a.fpid.substring(0, bar).trim();
                                if (!lbl.isEmpty()) a.label = lbl;
                            }
                        }
                        if (a.fpid.equals(a.id)) { idRemap.put(a.id, fixed); a.id = fixed; }
                        a.fpid = fixed;
                        changed = true;
                    }
                }
                out.add(a);
            }
        } catch (Throwable ignored) {
        }
        if (changed) {
            save(out);
            String last = sp.getString("last_entry", null);
            if (last != null && idRemap.containsKey(last)) {
                sp.edit().putString("last_entry", idRemap.get(last)).apply();
            }
        }
        return out;
    }

    private void save(List<Account> list) {
        JSONArray arr = new JSONArray();
        for (Account a : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", a.id);
                o.put("fpid", a.fpid);
                o.put("token", a.token);
                o.put("label", a.label);
                o.put("lastLogin", a.lastLogin);
                arr.put(o);
            } catch (Throwable ignored) {
            }
        }
        sp.edit().putString("accounts", arr.toString()).apply();
    }

    /** 序列化为 JSON 数组（{@link #saveSync} 专用；{@code save()} 本体未作任何改动）。 */
    private JSONArray toJsonArray(List<Account> list) {
        JSONArray arr = new JSONArray();
        for (Account a : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", a.id);
                o.put("fpid", a.fpid);
                o.put("token", a.token);
                o.put("label", a.label);
                o.put("lastLogin", a.lastLogin);
                arr.put(o);
            } catch (Throwable ignored) {
            }
        }
        return arr;
    }

    /**
     * 同步落盘（{@code commit()}），<b>仅用于批量导入</b>这类"要么全有、要么全无"的提交路径。
     *
     * 与 {@code save()}（{@code apply()} 异步）的区别只在落盘方式，序列化规则完全一致。
     * 用 {@code commit()} 的理由与 {@link #setLiveEntryId} 相同：{@code apply()} 是异步的，
     * 批量导入一次可能写入几十条，若用户导入后立刻把 App 划掉，整个批次可能还没落盘就随进程
     * 一起没了；同步写在返回前就能保证已写入磁盘，代价可忽略。
     *
     * @return true 表示已同步写入磁盘
     */
    public boolean saveSync(List<Account> list) {
        return sp.edit().putString("accounts", toJsonArray(list).toString()).commit();
    }

    /**
     * 从文本中提取末尾连续数字作为号ID。
     * 兼容 "备注｜⇥81444913"、"备注|81444913"、"81444913" 等格式混入的备注/分隔符/空白。
     */
    public static String extractFpid(String raw) {
        if (raw == null) return "";
        raw = raw.trim();
        int start = raw.length();
        while (start > 0 && Character.isDigit(raw.charAt(start - 1))) start--;
        return raw.substring(start);
    }

    /**
     * 导入一行：备注|号ID----token / 备注｜号ID----token 或 号ID----token
     * 分隔符支持半角 | 和全角｜，号ID自动提取末尾数字。
     * @return 错误信息，null 表示成功
     */
    public String importLine(String line) {
        if (line == null) return null;
        line = line.trim();
        if (line.isEmpty()) return null;
        String label = null, body = line;
        int sepDash = line.indexOf("----");
        if (sepDash > 0) {
            String head = line.substring(0, sepDash);
            int bar = Math.max(head.lastIndexOf('|'), head.lastIndexOf('｜'));
            if (bar > 0) {
                label = head.substring(0, bar).trim();
                body = head.substring(bar + 1).trim() + line.substring(sepDash);
            }
        }
        int sep = body.indexOf("----");
        if (sep <= 0) return "无法解析: " + line;
        String fpid = extractFpid(body.substring(0, sep));
        String token = body.substring(sep + 4).trim();
        if (fpid.isEmpty() || token.isEmpty() || token.contains("----")) return "无法解析: " + line;
        if (label == null || label.isEmpty()) label = fpid;
        // 去重：同 fpid 覆盖
        List<Account> list = all();
        for (Account a : list) {
            if (a.fpid.equals(fpid)) {
                a.token = token;
                if (label != null && !label.equals(fpid)) a.label = label;
                save(list);
                return null;
            }
        }
        Account a = new Account();
        a.id = fpid;
        a.fpid = fpid;
        a.token = token;
        a.label = label;
        list.add(a);
        save(list);
        return null;
    }

    public void updateLabel(String id, String label) {
        List<Account> list = all();
        for (Account a : list) {
            if (a.id.equals(id)) {
                a.label = label;
                save(list);
                return;
            }
        }
    }

    public void updateToken(String id, String token) {
        List<Account> list = all();
        for (Account a : list) {
            if (a.id.equals(id)) {
                a.token = token;
                save(list);
                return;
            }
        }
    }

    public void remove(String id) {
        List<Account> list = all();
        List<Account> out = new ArrayList<>();
        for (Account a : list) if (!a.id.equals(id)) out.add(a);
        save(out);
    }

    public String getLastEntry() {
        return sp.getString("last_entry", null);
    }

    public void setLastEntry(String id) {
        sp.edit().putString("last_entry", id).apply();
    }

    /**
     * 当前 live（游戏内部数据目录）归属于哪个入口。
     *
     * 只存**入口 id**，不存任何凭据（无 token / 无手机号 / 无 fpid）。这是 "下次冷启动要不要
     * wipe live" 的唯一判据，见 VaultEngine.launchEntry()。
     */
    public String getLiveEntryId() {
        String v = sp.getString("live_entry_id", null);
        return (v == null || v.isEmpty()) ? null : v;
    }

    /**
     * 记录 live 目录的归属；传 null 表示清空（live 已不属于任何入口）。
     *
     * 这里刻意用 **commit() 而不是 apply()**：apply() 是异步落盘的，进程被强杀时这个标记
     * 可能还没写下去；一旦它丢了，下次冷启动就会把 live 当成"无主数据"从而 wipe 掉，正好把
     * 刚刚登录好的会话删掉 —— 与本次要修的问题是同一个失效模式。该键很小，同步写的开销可忽略。
     */
    public void setLiveEntryId(String id) {
        if (id == null) sp.edit().remove("live_entry_id").commit();
        else sp.edit().putString("live_entry_id", id).commit();
    }

    /**
     * 上次为该入口打快照时、live 目录的指纹。用于跳过"内容没变却重写一遍约 9MB zip"的无意义操作。
     * 指纹本身只含文件数/总字节数/最新 mtime，不含任何文件内容或凭据。
     */
    public String getSnapshotFingerprint(String entryId) {
        return sp.getString("snap_fp_" + entryId, null);
    }

    public void setSnapshotFingerprint(String entryId, String fp) {
        if (fp == null) sp.edit().remove("snap_fp_" + entryId).apply();
        else sp.edit().putString("snap_fp_" + entryId, fp).apply();
    }

    public void markLogin(String id) {
        List<Account> list = all();
        for (Account a : list) {
            if (a.id.equals(id)) {
                a.lastLogin = System.currentTimeMillis();
                save(list);
                return;
            }
        }
    }

    public void createEntry(String label) {
        Account a = new Account();
        a.id = "e" + System.currentTimeMillis();
        a.fpid = "";
        a.token = "";
        a.label = label == null || label.trim().isEmpty() ? ("入口" + (all().size() + 1)) : label.trim();
        List<Account> list = all();
        list.add(a);
        save(list);
    }

    public void setToken(String id, String fpid, String token) {
        List<Account> list = all();
        for (Account a : list) {
            if (a.id.equals(id)) {
                a.fpid = fpid;
                a.token = token;
                if (a.label == null || a.label.isEmpty() || a.label.startsWith("入口")) {
                    a.label = fpid;
                }
                save(list);
                return;
            }
        }
    }
}
