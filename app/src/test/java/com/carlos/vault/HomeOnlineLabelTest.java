package com.carlos.vault;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONObject;
import org.junit.Test;

/**
 * 账号池 per-role 在线文案（2026-11-08）：今日页/账号页统一口径，
 * 同服双角色按各自 role.state 显示，不再共用账号级单会话状态。
 */
public class HomeOnlineLabelTest {

    private static JSONObject role(boolean online, String runState, String recoveryState,
                                   String statusMessage, boolean takeoverActive,
                                   boolean startup, boolean login) {
        JSONObject st = new JSONObject();
        try {
            st.put("online", online);
            st.put("runState", runState);
            st.put("recoveryState", recoveryState);
            st.put("statusMessage", statusMessage == null ? "" : statusMessage);
            st.put("takeoverRecoveryActive", takeoverActive);
            st.put("nextRecoveryAt", 0L);
            st.put("startupInProgress", startup);
            st.put("loginInProgress", login);
            st.put("runScheduleState", "ACTIVE");
            JSONObject role = new JSONObject();
            role.put("state", st);
            role.put("enabled", true);
            return role;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void online_role() {
        String[] lb = HomeActivity.poolOnlineLabel(role(true, "RUNNING", "AVAILABLE", "", false, false, false));
        assertEquals("● 日常在线", lb[0]);
        assertEquals("on", lb[1]);
    }

    @Test
    public void takeover_role() {
        String[] lb = HomeActivity.poolOnlineLabel(role(false, "RUNNING", "DISABLED", "检测到账号在其他设备上线", true, false, false));
        assertEquals("△ 被顶号暂停", lb[0]);
        assertEquals("off", lb[1]);
    }

    @Test
    public void recovering_role() {
        String[] lb = HomeActivity.poolOnlineLabel(role(false, "RUNNING", "WAITING_RETRY", "断线重试", false, false, false));
        assertEquals("◌ 重连中", lb[0]);
        String[] lb2 = HomeActivity.poolOnlineLabel(role(false, "RUNNING", "AVAILABLE", "", false, true, false));
        assertEquals("◌ 重连中", lb2[0]);
    }

    @Test
    public void needToken_role() {
        String[] lb = HomeActivity.poolOnlineLabel(role(false, "RUNNING", "ACTION_REQUIRED", "token 失效", false, false, false));
        assertEquals("⚠ 需重导token", lb[0]);
    }

    @Test
    public void offline_and_missingRole() {
        String[] lb = HomeActivity.poolOnlineLabel(role(false, "STOPPED", "AVAILABLE", "", false, false, false));
        assertEquals("○ 离线", lb[0]);
        assertEquals("off", lb[1]);
        // 无池数据：返回 null，由调用方回落旧状态源（不会被误判为离线）
        assertNull(HomeActivity.poolOnlineLabel(null));
        assertNull(HomeActivity.poolOnlineLabel(new JSONObject()));
    }
}
