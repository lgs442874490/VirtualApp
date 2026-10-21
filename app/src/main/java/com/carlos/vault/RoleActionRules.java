package com.carlos.vault;

/**
 * P1 角色动作（防封/暂停/重命名/移除/收纳）的<b>纯文案与校验规则</b>
 * （契约 P1 §三，逐字对齐线上——文案常量集中此处，JVM 单测直跑锁定）。
 *
 * <p>UI 层（HomeActivity/弹层）只做视图拼装，规则改这里即可全局生效。
 */
public final class RoleActionRules {

    // ---------- 防封保护（§三.1） ----------
    public static final String ANTIBAN_CONFIRM_TITLE = "开启防封保护确认";
    public static final String ANTIBAN_CONFIRM_POSITIVE = "确认开启";
    public static final String ANTIBAN_CONFIRM_NEGATIVE = "取消";
    /** 成功 toast：优先取服务端 message（同文案），此为缺失兜底。 */
    public static final String ANTIBAN_ON_TOAST = "已开启防封风控安全保护";
    public static final String ANTIBAN_OFF_TOAST = "已关闭防封风控安全保护";

    // ---------- 暂停自动恢复（§三.2） ----------
    /** 暂停成功固定 toast（契约逐字；失败 toast 服务端 message）。 */
    public static final String PAUSE_OK_TOAST = "账号已暂停，将按顶号设置自动恢复";

    // ---------- 重命名（§三.4） ----------
    public static final int NAME_MIN = 3;
    public static final int NAME_MAX = 12;
    public static final String ERR_NAME_EMPTY = "角色名不能为空";
    public static final String ERR_NAME_TRIM = "角色名首尾不能有空格";
    public static final String ERR_NAME_CONTROL = "角色名不能包含控制字符";
    public static final String ERR_NAME_LENGTH = "角色名长度必须在 3 到 12 个字符之间";
    /** 改名消耗提示（对话框内展示，契约 §二.3：先消耗改名道具 610001，不足自动改 400 钻石）。 */
    public static final String RENAME_COST_HINT = "本次消耗改名道具；不足时自动消耗 400 钻石";

    // ---------- 移除角色（§三.4） ----------
    public static final String REMOVE_CONFIRM_TITLE = "移除角色";
    public static final String REMOVE_CONFIRM_POSITIVE = "确认移除";

    // ---------- 启用/收纳（§三.4） ----------
    public static final String ENABLE_LABEL = "启用角色";
    public static final String SHELVE_LABEL = "收纳角色";
    public static final String SHELVED_BADGE = "已收纳";

    private RoleActionRules() {
    }

    /**
     * 防封开启确认正文（契约 §三.1 逐字，【角色名】替换为实际名称）。
     * 关闭不弹确认（仅开启前确认，契约口径）。
     */
    public static String antibanConfirmBody(String roleName) {
        return "确定为角色【" + (roleName == null ? "" : roleName)
                + "】开启防封保护吗？开启后，该角色在登录游戏时将自动模拟真实客户端上报设备环境"
                + "（req_settings_set_device），并在进入游戏 10 秒后执行易盾安全校验"
                + "（req_dun163_risk_check）。⚠ 注意事项："
                + "1. 采用官方受信任特征与动态 Nonce 掩码重封装机制；"
                + "2. 开关变更将在下一次角色登录/启动时生效；"
                + "3. 未开启时，角色仍按原来的极简协议链路登录启动。";
    }

    /** 移除角色确认正文（契约 §三.4 逐字，强调不删除游戏内角色）。 */
    public static String removeConfirmBody(String roleName) {
        return "确定要移除角色【" + (roleName == null ? "" : roleName)
                + "】吗？仅从工具工作区移除，不会删除游戏内角色。";
    }

    /**
     * 改名输入校验（契约 §三.4：3~12 个字符、首尾不能空格、不能含控制字符）。
     * 返回 null=合法；否则返回中文错误文案（toast 直接展示）。顺序：
     * 空值 → 首尾空格 → 控制字符 → 长度。长度按 UTF-16 code unit
     * （与服务端 Java {@code String.length()} 同口径，中文/emoji 常规名不受影响）。
     */
    public static String validateRoleName(String name) {
        if (name == null || name.isEmpty()) return ERR_NAME_EMPTY;
        char first = name.charAt(0);
        char last = name.charAt(name.length() - 1);
        if (Character.isWhitespace(first) || Character.isWhitespace(last)) return ERR_NAME_TRIM;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7F) return ERR_NAME_CONTROL;
        }
        if (name.length() < NAME_MIN || name.length() > NAME_MAX) return ERR_NAME_LENGTH;
        return null;
    }

    /** 改名成功 toast 的消耗段（契约 §二.3 响应 usedDiamond → 400 钻石 / 改名道具）。 */
    public static String renameCostText(boolean usedDiamond) {
        return usedDiamond ? "本次消耗：400 钻石" : "本次消耗：改名道具";
    }
}
