package com.carlos.vault.daily.formation;

import android.content.Context;

import com.carlos.vault.daily.FnParams;
import com.carlos.vault.daily.FormationStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 每功能编队绑定（契约 §2.2 门面方法的实现体 / §5 每功能 params）：读/写/校验。
 *
 * <p>params 字段（契约 §5，沿用旧 FnParams）：
 * <ul>
 *   <li>{@code formation_mode}：{@code AUTOMATIC}|{@code ROLE}；</li>
 *   <li>{@code formation_id}：单编队功能/旧版兼容（ROLE 时 &gt;0）；</li>
 *   <li>{@code formation_ids}：多队列功能的编队 id 数组（新增；兼容旧 {@code formation_id}）；</li>
 *   <li>{@code soldiers}/{@code ratio}/{@code total}：兵量覆盖/自动比例（沿用旧键）。</li>
 * </ul>
 *
 * <p>纯函数（{@link #parseIds}/{@link #applyIds}/{@link #applyAutomatic}/{@link #validate}）
 * 无 Android 依赖可 JVM 直测；{@link #readIds}/{@link #writeIds} 只做 prefs 读写。
 */
public final class FormationBinding {

    public static final String KEY_MODE = "formation_mode";
    public static final String KEY_ID = "formation_id";
    public static final String KEY_IDS = "formation_ids";

    /** {@code {队列}使用自动编队前，请先登录并完成出征容量同步}（网页 §4.6）。 */
    public static final String MSG_AUTO_NOT_READY_FMT = "%s使用自动编队前，请先登录并完成出征容量同步";
    /** {@code {队列}请选择角色编队}（网页 §4.6）。 */
    public static final String MSG_SELECT_ROLE_FMT = "%s请选择角色编队";
    /** {@code {队列}不能与同模块其他队列共用角色编队}（网页 §4.6 / 契约 §2.5）。 */
    public static final String MSG_SHARED_FMT = "%s不能与同模块其他队列共用角色编队";
    /** {@code {队列}引用的角色编队不存在，请重新选择}（网页 §4.6 / 契约 §2.5）。 */
    public static final String MSG_NOT_EXIST_FMT = "%s引用的角色编队不存在，请重新选择";
    /** {@code {英雄所在队列}与{队列}的角色编队存在英雄冲突}（网页 §4.6 / 契约 §2.5）。 */
    public static final String MSG_HERO_CONFLICT_FMT = "%s与%s的角色编队存在英雄冲突";
    /** 游戏编队只读：ROLE 绑定不允许直接引用（契约 §3.3）。 */
    public static final String MSG_GAME_READONLY_FMT = "%s不能引用游戏编队，请先创建副本";

    private FormationBinding() {
    }

    // ==================== 纯函数：params 解析/写入 ====================

    /** 读绑定编队 id：优先 {@code formation_ids}，回落旧 {@code formation_id}；去非法值。 */
    public static long[] parseIds(JSONObject params) {
        if (params == null) return new long[0];
        List<Long> ids = new ArrayList<Long>();
        JSONArray arr = params.optJSONArray(KEY_IDS);
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                long id = arr.optLong(i, 0);
                if (id != 0) ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            long id = params.optLong(KEY_ID, 0);
            if (id != 0) ids.add(id);
        }
        long[] out = new long[ids.size()];
        for (int i = 0; i < ids.size(); i++) out[i] = ids.get(i);
        return out;
    }

    /**
     * 写 ROLE 绑定（就地修改并返回 params）：mode=ROLE + formation_ids；
     * 单编队时同时写旧 {@code formation_id}（旧服务端兼容，契约 §5）。
     */
    public static JSONObject applyIds(JSONObject params, long[] ids) {
        JSONObject p = params == null ? new JSONObject() : params;
        try {
            p.put(KEY_MODE, FormationStore.MODE_ROLE);
            JSONArray arr = new JSONArray();
            int count = 0;
            if (ids != null) {
                for (long id : ids) {
                    if (id == 0) continue;
                    arr.put(id);
                    count++;
                }
            }
            p.put(KEY_IDS, arr);
            if (count == 1) {
                p.put(KEY_ID, arr.optLong(0, 0));
            } else {
                p.remove(KEY_ID);
            }
        } catch (Throwable ignored) {
        }
        return p;
    }

    /** 写 AUTOMATIC（就地修改并返回 params）：移除 formation_id/formation_ids（契约 §5）。 */
    public static JSONObject applyAutomatic(JSONObject params) {
        JSONObject p = params == null ? new JSONObject() : params;
        try {
            p.put(KEY_MODE, FormationStore.MODE_AUTOMATIC);
            p.remove(KEY_ID);
            p.remove(KEY_IDS);
        } catch (Throwable ignored) {
        }
        return p;
    }

    // ==================== prefs 读写（Context 只做存取） ====================

    /** 读该功能绑定编队 id（ROLE 多队列功能返回数组；旧数据回落 formation_id）。 */
    public static long[] readIds(Context ctx, String fpid, long sid, String fnId) {
        try {
            return parseIds(FnParams.params(ctx, fpid, sid, fnId));
        } catch (Throwable t) {
            return new long[0];
        }
    }

    /** 写该功能绑定编队 id（同时把模式置 ROLE；空数组视为清除 → 模式仍 ROLE 但无引用，由校验拦截）。 */
    public static void writeIds(Context ctx, String fpid, long sid, String fnId, long[] ids) {
        try {
            JSONObject p = FnParams.params(ctx, fpid, sid, fnId);
            applyIds(p, ids);
            FnParams.saveParams(ctx, fpid, sid, fnId, p);
        } catch (Throwable ignored) {
        }
    }

    /** 写该功能为自动编队（清除编队引用，保留 ratio/total 等其它键）。 */
    public static void writeAutomatic(Context ctx, String fpid, long sid, String fnId) {
        try {
            JSONObject p = FnParams.params(ctx, fpid, sid, fnId);
            applyAutomatic(p);
            FnParams.saveParams(ctx, fpid, sid, fnId, p);
        } catch (Throwable ignored) {
        }
    }

    // ==================== 校验（网页 §4.6 预检口径） ====================

    /**
     * 队列绑定校验。
     *
     * @return {@code null}=合法；否则中文原因（队列名用 {@link #fnLabel} 填充；文案逐字网页 §4.6）
     */
    public static String validate(FormationCatalogStore cat, List<FormationModel> lib,
                                  String fnId, String mode, long[] ids) {
        String label = fnLabel(fnId);
        String m = FormationStore.normalizeMode(mode);
        if (FormationStore.MODE_AUTOMATIC.equals(m)) {
            // 自动编队可用性由 UI banner 提示（未同步时禁用 tab），此处不拦截保存
            return null;
        }
        List<Long> valid = new ArrayList<Long>();
        if (ids != null) {
            for (long id : ids) {
                if (id != 0) valid.add(id);
            }
        }
        if (valid.isEmpty()) {
            return String.format(Locale.ROOT, MSG_SELECT_ROLE_FMT, label);
        }
        Set<Long> seen = new HashSet<Long>();
        for (Long id : valid) {
            if (!seen.add(id)) {
                return String.format(Locale.ROOT, MSG_SHARED_FMT, label);
            }
            if (id.longValue() < 0) {
                return String.format(Locale.ROOT, MSG_GAME_READONLY_FMT, label);
            }
            if (FormationRules.findById(lib, id.longValue()) == null) {
                return String.format(Locale.ROOT, MSG_NOT_EXIST_FMT, label);
            }
        }
        // 跨队列英雄冲突（同模块其他队列已绑定的编队）
        if (lib != null) {
            long[] bound = new long[valid.size()];
            for (int i = 0; i < valid.size(); i++) bound[i] = valid.get(i);
            for (int i = 0; i < bound.length; i++) {
                FormationModel candidate = FormationRules.findById(lib, bound[i]);
                if (candidate == null) continue;
                for (int j = 0; j < bound.length; j++) {
                    if (i == j) continue;
                    FormationModel other = FormationRules.findById(lib, bound[j]);
                    if (other == null) continue;
                    long dup = firstSharedHero(candidate.distinctHeroIds(),
                            other.distinctHeroIds());
                    if (dup > 0) {
                        return String.format(Locale.ROOT, MSG_HERO_CONFLICT_FMT,
                                quote(candidate.name), quote(other.name));
                    }
                }
            }
        }
        return null;
    }

    /**
     * 选择器 banner 文案（契约 §2.5）：{@code 请选择一支编队} / {@code 请选择 N 支编队} /
     * {@code 还需要选择 N 支编队}。
     *
     * @param selected 已选数量
     * @param required 需要数量（≤1 视为单选）
     * @return 文案；已选够返回 null
     */
    public static String selectionBanner(int selected, int required) {
        int need = Math.max(1, required);
        int sel = Math.max(0, selected);
        if (need <= 1) {
            return sel >= 1 ? null
                    : String.format(Locale.ROOT, FormationRules.MSG_SELECT_ONE, new Object[0]);
        }
        if (sel <= 0) return String.format(Locale.ROOT, FormationRules.MSG_SELECT_N_FMT, need);
        if (sel < need) {
            return String.format(Locale.ROOT, FormationRules.MSG_NEED_N_FMT, need - sel);
        }
        return null;
    }

    /** 功能中文名（队列名占位填充；未知回落 fnId）。 */
    public static String fnLabel(String fnId) {
        if (fnId == null) return "队列";
        String id = fnId.trim();
        if ("gather".equals(id)) return "采集";
        if ("intel".equals(id)) return "情报";
        if ("behemoth".equals(id)) return "巨兽";
        if ("bear".equals(id)) return "巨熊";
        if ("hunt".equals(id)) return "狩猎";
        if ("guardian".equals(id)) return "守护盟友";
        if ("hero_mission".equals(id) || "hero-mission".equals(id)) return "英雄的使命";
        if ("mercenary".equals(id) || "act_mercenary".equals(id)) return "佣兵";
        return id.isEmpty() ? "队列" : id;
    }

    // ==================== 内部 ====================

    private static long firstSharedHero(List<Long> a, List<Long> b) {
        for (Long h : a) {
            if (h == null) continue;
            for (Long o : b) {
                if (o != null && o.longValue() == h.longValue()) return h.longValue();
            }
        }
        return -1;
    }

    private static String quote(String name) {
        String n = name == null || name.trim().isEmpty() ? "编队" : name.trim();
        return "“" + n + "”";
    }
}
