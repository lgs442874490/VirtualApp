package com.carlos.vault.bootstrap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * 起号注册/起号批量的表单纯逻辑（M5，2026-11-07）：校验、providerConfig/accounts JSON
 * 组装、状态→文案映射。全部纯 JVM 可测，不 import android.*，不发起请求。
 *
 * <p>口径与服务端一致（vault-server RegistrationApi/BootstrapApi 为准）：
 * goalCount 1–50、maxNumbers ≥goal 且 ≤100、gamePassword ≥6 或空、idCard 18 位、
 * serverId 正整数、targetFurnaceLevel 1–9、roleName ≤16、accounts 1–100。
 */
public final class BootstrapForms {

    /** 短信关键词默认值（任务书 §2：点点互动）。 */
    public static final String DEFAULT_KEYWORD = "点点互动";
    /** 接码注册合法域（对齐 RegistrationApi）。 */
    public static final int GOAL_MIN = 1, GOAL_MAX = 50, MAX_NUMBERS_CEILING = 100;
    /** 起号合法域（对齐 BootstrapApi / BootstrapConfig）。 */
    public static final int FURNACE_MIN = 1, FURNACE_MAX = 9;
    public static final int ROLE_NAME_MAX = 16;
    /** 游戏密码长度。 */
    public static final int GAME_PASSWORD_LEN = 8;

    private BootstrapForms() {
    }

    // ================= 接码平台 =================

    /** 平台标识：d1 / haozhuma（服务端 providerType 口径）。 */
    public static final String PROVIDER_D1 = "d1";
    public static final String PROVIDER_HAOZHUMA = "haozhuma";

    /** 平台展示名（App 内 segmented 用）。 */
    public static String providerDisplay(String type) {
        return PROVIDER_HAOZHUMA.equals(type) ? "豪猪" : "D1";
    }

    /**
     * 组装 providerConfig JSON 对象字符串（HMAC 扁平标量约定；服务端按类型取键）：
     * d1 → {@code {token}}（必填）；haozhuma → {@code {server,user,password,sid}}（后三项必填，
     * server 可空不拼）。必填缺失返回 null（调用方提示），返回 null 前不抛异常。
     * 输入只进本返回值（内存），调用方不落盘、不打日志。
     */
    public static String providerConfigJson(String type, Map<String, String> inputs) {
        if (inputs == null) return null;
        try {
            JSONObject o = new JSONObject();
            if (PROVIDER_HAOZHUMA.equals(type)) {
                String user = text(inputs.get("user"));
                String password = text(inputs.get("password"));
                String sid = text(inputs.get("sid"));
                if (user.isEmpty() || password.isEmpty() || sid.isEmpty()) return null;
                String server = text(inputs.get("server"));
                if (!server.isEmpty()) o.put("server", server);
                o.put("user", user);
                o.put("password", password);
                o.put("sid", sid);
            } else {
                String token = text(inputs.get("token"));
                if (token.isEmpty()) return null;
                o.put("token", token);
            }
            return o.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 接码注册表单校验（错误文案；null=通过）。realNameIdCard 空串=跳过实名；
     * 非空时必须 18 位（与服务端 PassportIds.isValidIdCard 同门槛的长度预检）。
     */
    public static String validatePhoneBatch(int goalCount, int maxNumbers,
                                            String gamePassword, String realNameIdCard) {
        if (goalCount < GOAL_MIN || goalCount > GOAL_MAX) {
            return "目标成功数必须在 " + GOAL_MIN + "–" + GOAL_MAX + " 之间";
        }
        if (maxNumbers < goalCount) {
            return "最多尝试号码数不能小于目标成功数";
        }
        if (maxNumbers > MAX_NUMBERS_CEILING) {
            return "最多尝试号码数不能超过 " + MAX_NUMBERS_CEILING;
        }
        if (gamePassword != null && !gamePassword.isEmpty() && gamePassword.length() < 6) {
            return "游戏账号密码（至少 6 位）";
        }
        if (realNameIdCard != null && !realNameIdCard.trim().isEmpty()
                && realNameIdCard.trim().length() != 18) {
            return "身份证号必须为 18 位";
        }
        return null;
    }

    /** 自动生成游戏密码（8 位字母+数字，至少含一个大写与一个数字）。 */
    public static String generateGamePassword() {
        String lower = "abcdefghijkmnpqrstuvwxyz";
        String upper = "ABCDEFGHJKLMNPQRSTUVWXYZ";
        String digit = "23456789";
        String all = lower + upper + digit;
        SplittableRandom rnd = new SplittableRandom();
        StringBuilder sb = new StringBuilder();
        sb.append(upper.charAt(rnd.nextInt(upper.length())));
        sb.append(digit.charAt(rnd.nextInt(digit.length())));
        while (sb.length() < GAME_PASSWORD_LEN) {
            sb.append(all.charAt(rnd.nextInt(all.length())));
        }
        return sb.toString();
    }

    // ================= 起号批量 =================

    /** 起号表单校验（错误文案；null=通过）。serverIdText 需正整数；roleName 可空。 */
    public static String validateBootstrapBatch(String serverIdText, int targetFurnace,
                                                String roleName) {
        long serverId = parsePositiveLong(serverIdText);
        if (serverId <= 0) return "区服号必须为正整数";
        if (targetFurnace < FURNACE_MIN || targetFurnace > FURNACE_MAX) {
            return "目标大熔炉必须在 " + FURNACE_MIN + "–" + FURNACE_MAX + " 级";
        }
        if (roleName != null && roleName.trim().length() > ROLE_NAME_MAX) {
            return "角色名最多 " + ROLE_NAME_MAX + " 个字";
        }
        return null;
    }

    /** 正整数解析（空/非法/≤0 → 0）。 */
    public static long parsePositiveLong(String text) {
        if (text == null) return 0L;
        try {
            return Long.parseLong(text.trim());
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 多行账号清单 → 逐行 trim 后的非空行（保持用户顺序，不解析内容——服务端 import 解析）。 */
    public static List<String> parseAccountLines(String multiLineText) {
        List<String> out = new ArrayList<>();
        if (multiLineText == null || multiLineText.isEmpty()) return out;
        for (String line : multiLineText.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /**
     * 组装 batch-start 的 accounts JSON 数组字符串：文本行（加密串/两段式）为字符串元素 +
     * 勾选的回流条目为 {@code {fpid,sdkToken,label,encrypted}} 对象元素（服务端两种都认，
     * 对象统一由服务端解析去重）。无内容返回空串（调用方提示清单为空）。
     */
    public static String accountsJson(List<String> lines,
                                      List<BootstrapReferralSync.ReferralEntry> picked) {
        try {
            JSONArray arr = new JSONArray();
            if (lines != null) {
                for (String line : lines) {
                    if (line != null && !line.trim().isEmpty()) arr.put(line.trim());
                }
            }
            if (picked != null) {
                for (BootstrapReferralSync.ReferralEntry e : picked) {
                    if (e == null || e.fpid.isEmpty() || e.sdkToken.isEmpty()) continue;
                    JSONObject o = new JSONObject();
                    o.put("fpid", e.fpid);
                    o.put("sdkToken", e.sdkToken);
                    o.put("label", BootstrapReferralSync.displayLabel(e));
                    if (!e.encrypted.isEmpty()) o.put("encrypted", e.encrypted);
                    arr.put(o);
                }
            }
            return arr.length() == 0 ? "" : arr.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    // ================= 状态 → 显示文案（任务书 §2 语义 1:1） =================

    /**
     * 统一状态文案：注册单号 / 起号单号 / 批次根状态共用一张映射表——
     * PENDING→等待中、CREATING→建号中、RUNNING→执行中、SUCCESS→成功、
     * COMPLETED→已完成、FAILED→失败、CANCELLED→已取消、其余（WAITING/null）→等待中。
     */
    public static String stateText(String state) {
        if (state == null) return "等待中";
        switch (state) {
            case "CREATING":
                return "建号中";
            case "RUNNING":
                return "执行中";
            case "SUCCESS":
                return "成功";
            case "COMPLETED":
                return "已完成";
            case "FAILED":
                return "失败";
            case "CANCELLED":
                return "已取消";
            default:
                return "等待中";
        }
    }

    /** 状态 chip 色系（UiStyle.badge type）：ok/run/wait/warn/err/idle。 */
    public static String badgeType(String state) {
        if ("SUCCESS".equals(state) || "COMPLETED".equals(state)) return "ok";
        if ("RUNNING".equals(state)) return "run";
        if ("CREATING".equals(state)) return "wait";
        if ("FAILED".equals(state)) return "err";
        if ("CANCELLED".equals(state)) return "warn";
        return "idle";
    }

    /** 注册单号状态：等待中 / 执行中 / 成功 / 失败（PENDING/RUNNING/SUCCESS/FAILED）。 */
    public static String phoneStateText(String state) {
        if (state == null) return "等待中";
        switch (state) {
            case "RUNNING":
                return "执行中";
            case "SUCCESS":
                return "成功";
            case "FAILED":
                return "失败";
            default:
                return "等待中";
        }
    }

    /** 起号单号状态：PENDING/CREATING/RUNNING/SUCCESS/FAILED → 等待中/建号中/执行中/成功/失败。 */
    public static String accountStateText(String state) {
        if (state == null) return "等待中";
        switch (state) {
            case "CREATING":
                return "建号中";
            case "RUNNING":
                return "执行中";
            case "SUCCESS":
                return "成功";
            case "FAILED":
                return "失败";
            default:
                return "等待中";
        }
    }

    /** 批次根状态：等待中 / 执行中 / 已完成 / 失败 / 已取消。 */
    public static String batchStateText(String state) {
        if (state == null) return "等待中";
        switch (state) {
            case "RUNNING":
                return "执行中";
            case "COMPLETED":
                return "已完成";
            case "FAILED":
                return "失败";
            case "CANCELLED":
                return "已取消";
            default:
                return "等待中";
        }
    }

    /** 批次是否终态（终态后停止轮询）。 */
    public static boolean isTerminalBatch(String state) {
        return "COMPLETED".equals(state) || "FAILED".equals(state) || "CANCELLED".equals(state);
    }

    /** 批次显示短号：batchId 过长时取尾 4 位（「批次 #4501」），短则原样。 */
    public static String batchShortId(String batchId) {
        if (batchId == null || batchId.isEmpty()) return "#?";
        return batchId.length() <= 4 ? "#" + batchId : "#" + batchId.substring(batchId.length() - 4);
    }

    /** 敏感串掩码（token/加密串在 UI 展示用）：≤8 位全掩，否则前 4 后 4，中段打码。 */
    public static String maskCredential(String secret) {
        if (secret == null) return "";
        String s = secret.trim();
        if (s.isEmpty()) return "";
        if (s.length() <= 8) return "****";
        return s.substring(0, 4) + "…" + s.substring(s.length() - 4)
                + "（" + s.length() + " 字符）";
    }

    private static String text(String v) {
        return v == null ? "" : v.trim();
    }
}
