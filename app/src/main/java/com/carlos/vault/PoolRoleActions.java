package com.carlos.vault;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.carlos.vault.daily.RoleDisplay;
import com.carlos.vault.daily.RoleStore;

import org.json.JSONObject;

/**
 * P1 角色动作 UI 帮助类（重命名 / 移除角色 / 启用·收纳 / 防封开关确认）：
 * 账号页角色行与角色管理弹层（{@link RoleManagerSheet}）共用同一份实现——
 * 弹窗文案走 {@link RoleActionRules}（契约逐字），网络走 {@link VaultSync}
 * 新增的 P1 动作通道，成功/失败 toast 口径一致（成功优先服务端 message，
 * 失败 toast 服务端 message；服务端 3002 中文原因原样透出）。
 *
 * <p>不做事：账号移动、批量复制凭据、计划休眠、角色创建、游戏内角色删除（P1 范围外）。
 */
public final class PoolRoleActions {

    private PoolRoleActions() {
    }

    /** 动作完成回调（调用方刷新池数据/列表；OkHttp 线程回调里已切主线程）。 */
    public interface OnDone {
        void run();
    }

    private interface Invoke {
        void run(VaultSync.Callback cb);
    }

    // ---------- 防封保护（§三.1） ----------

    /**
     * 防封开关动作入口：开启先弹确认（标题/正文/按钮逐字对齐契约），关闭直接调用。
     * 成功 toast 服务端 message（「已开启/已关闭防封风控安全保护」）。
     */
    public static void toggleAntiban(final Activity act, final AccountStore.Account a,
                                     final RoleStore.Role r, final boolean enable,
                                     final OnDone onDone) {
        if (!enable) {
            actAntiban(act, a, r, false, onDone);
            return;
        }
        new AlertDialog.Builder(act, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle(RoleActionRules.ANTIBAN_CONFIRM_TITLE)
                .setMessage(RoleActionRules.antibanConfirmBody(RoleDisplay.roleName(r.name)))
                .setPositiveButton(RoleActionRules.ANTIBAN_CONFIRM_POSITIVE,
                        (d, w) -> actAntiban(act, a, r, true, onDone))
                .setNegativeButton(RoleActionRules.ANTIBAN_CONFIRM_NEGATIVE, null)
                .show();
    }

    private static void actAntiban(final Activity act, final AccountStore.Account a,
                                   final RoleStore.Role r, final boolean enable,
                                   final OnDone onDone) {
        runRoleAction(act, a, r, enable ? RoleActionRules.ANTIBAN_ON_TOAST
                        : RoleActionRules.ANTIBAN_OFF_TOAST,
                "防封保护设置失败：", null, onDone,
                cb -> VaultSync.roleActionAntiban(act, a.fpid, String.valueOf(r.serverid),
                        String.valueOf(r.uid), enable, cb));
    }

    // ---------- 重命名（§三.4） ----------

    /**
     * 改名对话框：输入 3~12 字符（首尾空格/控制字符本地预校验，文案同服务端口径），
     * 对话框内展示消耗提示；校验不过不关弹窗（toast 中文原因）。
     * 成功 toast「角色已改名「新名」 · 本次消耗：改名道具/400 钻石」（按响应 usedDiamond）。
     */
    public static void showRenameDialog(final Activity act, final AccountStore.Account a,
                                        final RoleStore.Role r, final OnDone onDone) {
        final LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(act, 22), dp(act, 14), dp(act, 22), dp(act, 4));

        final TextView cap = new TextView(act);
        cap.setText("新角色名（" + RoleActionRules.NAME_MIN + "~" + RoleActionRules.NAME_MAX
                + " 个字符，首尾不能有空格）");
        cap.setTextSize(12);
        cap.setTextColor(UiStyle.INK2);
        box.addView(cap);

        final EditText input = new EditText(act);
        input.setText(r.name == null ? "" : r.name);
        input.setSelection(input.getText().length());
        input.setSingleLine(true);
        input.setTextSize(14);
        input.setTextColor(UiStyle.INK);
        input.setBackground(UiStyle.roundRectStroke(act, UiStyle.WHITE, 12, UiStyle.LINE_STRONG));
        input.setMinHeight(dp(act, 44));
        input.setPadding(dp(act, 12), dp(act, 10), dp(act, 12), dp(act, 10));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(act, 8);
        box.addView(input, ilp);

        final TextView hint = new TextView(act);
        hint.setText(RoleActionRules.RENAME_COST_HINT);
        hint.setTextSize(10.5f);
        hint.setTextColor(UiStyle.INK3);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(act, 6);
        box.addView(hint, hlp);

        final AlertDialog dialog = new AlertDialog.Builder(act, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("重命名角色")
                .setView(box)
                .setPositiveButton("确认改名", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    final String name = input.getText() == null ? "" : input.getText().toString();
                    final String err = RoleActionRules.validateRoleName(name);
                    if (err != null) {
                        UiStyle.toast(act, err);
                        return;
                    }
                    final TextView pos = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                    pos.setEnabled(false);
                    pos.setText("提交中…");
                    VaultSync.roleActionRename(act, a.fpid, String.valueOf(r.serverid),
                            String.valueOf(r.uid), name, (code, message, data) ->
                                    act.runOnUiThread(() -> {
                                        pos.setEnabled(true);
                                        pos.setText("确认改名");
                                        if (!ok(code, data)) {
                                            UiStyle.toast(act, "改名失败：" + msgOr(
                                                    message, "服务器未受理"));
                                            return;
                                        }
                                        String newName = data == null ? ""
                                                : data.optString("name", "");
                                        boolean usedDiamond = data != null
                                                && data.optBoolean("usedDiamond", false);
                                        // 本地角色表同步新名（registerRole 原地升级、只覆盖非空字段），
                                        // 行内昵称立即跟随，无需等下次服务器元数据学习
                                        if (!newName.isEmpty()) {
                                            RoleStore.registerRole(act, a.fpid, r.serverid,
                                                    r.uid, newName);
                                        }
                                        dialog.dismiss();
                                        UiStyle.toast(act, "角色已改名"
                                                + (newName.isEmpty() ? "" : "「" + newName + "」")
                                                + " · " + RoleActionRules.renameCostText(usedDiamond));
                                        if (onDone != null) onDone.run();
                                    }));
                }));
        dialog.show();
    }

    // ---------- 移除角色（§三.4） ----------

    /** 移除确认（正文逐字，强调不删除游戏内角色）→ op remove；成功后本地同步移出工作区。 */
    public static void confirmRemove(final Activity act, final AccountStore.Account a,
                                     final RoleStore.Role r, final OnDone onDone) {
        new AlertDialog.Builder(act, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle(RoleActionRules.REMOVE_CONFIRM_TITLE)
                .setMessage(RoleActionRules.removeConfirmBody(RoleDisplay.roleName(r.name)))
                .setPositiveButton(RoleActionRules.REMOVE_CONFIRM_POSITIVE,
                        (d, w) -> runRoleAction(act, a, r,
                                "角色已移除（游戏内角色未删除）", "移除失败：",
                                () -> RoleStore.setEnabled(act, a.fpid, r.serverid, r.uid, false),
                                onDone,
                                cb -> VaultSync.roleActionRemove(act, a.fpid,
                                        String.valueOf(r.serverid), String.valueOf(r.uid), cb)))
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------- 启用 / 收纳（§三.4） ----------

    /**
     * 启用/收纳切换（op enabled）：成功 toast 服务端 message（「已启用」/「已收纳」）；
     * 成功后本地 RoleStore 同步镜像（工作区列表与池状态一致，服务端是事实源）。
     */
    public static void setEnabled(final Activity act, final AccountStore.Account a,
                                  final RoleStore.Role r, final boolean enable,
                                  final OnDone onDone) {
        runRoleAction(act, a, r, null, enable ? "启用失败：" : "收纳失败：",
                () -> RoleStore.setEnabled(act, a.fpid, r.serverid, r.uid, enable),
                onDone,
                cb -> VaultSync.roleActionEnabled(act, a.fpid, String.valueOf(r.serverid),
                        String.valueOf(r.uid), enable, cb));
    }

    // ---------- 角色动作菜单（角色行长按，账号页/弹层共用） ----------

    /**
     * 角色管理菜单：重命名 / 移除角色 / 启用·收纳（收纳态显示「启用角色」）。
     * {@code poolEnabled} 为服务端口径（pool 角色缺失时回落本地 enabled）。
     */
    public static void showMenu(final Activity act, final AccountStore.Account a,
                                final RoleStore.Role r, final boolean poolEnabled,
                                final OnDone onDone) {
        String title = RoleDisplay.roleListLabel(r);
        new AlertDialog.Builder(act, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle(title)
                .setItems(new String[]{
                        "✏️ 重命名",
                        "🗑️ 移除角色（不删除游戏内角色）",
                        poolEnabled ? "📦 " + RoleActionRules.SHELVE_LABEL + "（停用）"
                                : "✅ " + RoleActionRules.ENABLE_LABEL}, (d, w) -> {
                    if (w == 0) showRenameDialog(act, a, r, onDone);
                    else if (w == 1) confirmRemove(act, a, r, onDone);
                    else setEnabled(act, a, r, !poolEnabled, onDone);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------- 公共 ----------

    /** 业务成功判定：code==0（data 缺失/无 accepted 字段宽容放行，服务端失败会给非 0 码）。 */
    private static boolean ok(int code, JSONObject data) {
        return code == 0 && (data == null || data.optBoolean("accepted", true));
    }

    private static String msgOr(String message, String fallback) {
        return message == null || message.isEmpty() ? fallback : message;
    }

    /**
     * P1 角色动作公共壳：同角色串行（poolBusy 语义在调用方 HomeActivity，这里按
     * fpid:sid:uid 复用其集合）→ 请求 → 成功 toast（服务端 message 缺省用 okFallback）
     * / 失败 toast 服务端 message → onOk（本地镜像，主线程）→ onDone（刷新）。
     */
    private static void runRoleAction(final Activity act, final AccountStore.Account a,
                                      final RoleStore.Role r, final String okFallback,
                                      final String failPrefix, final Runnable onOk,
                                      final OnDone onDone, final Invoke invoke) {
        invoke.run((code, message, data) -> act.runOnUiThread(() -> {
            if (ok(code, data)) {
                String msg = data == null ? "" : data.optString("message", "");
                UiStyle.toast(act, msg.isEmpty()
                        ? (okFallback == null ? "操作成功" : okFallback) : msg);
                if (onOk != null) onOk.run();
            } else {
                UiStyle.toast(act, failPrefix + msgOr(message, "服务器未受理"));
            }
            if (onDone != null) onDone.run();
        }));
    }

    private static int dp(Activity act, int v) {
        return (int) (act.getResources().getDisplayMetrics().density * v + 0.5f);
    }
}
