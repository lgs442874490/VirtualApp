package com.carlos.vault.daily;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 日常配置存储：元配置（模板）+ 各账号配置。
 * 存储形态：SharedPreferences 中一份 JSON，key=meta / acc:<fpid>。
 * 支持将账号配置一键同步为与元配置完全一致（覆盖差异/补齐缺失/清理多余）。
 */
public class DailyConfigStore {

    /** 旧版护盾任务 id（TaskDef 侧）与 FnRegistry 的 {@code battle_shield} 曾是同一功能的两套 id，已统一为后者。 */
    private static final String LEGACY_SHIELD_ID = "free_shield";
    private static final String SHIELD_ID = "battle_shield";

    private final SharedPreferences prefs;

    public DailyConfigStore(Context context) {
        prefs = context.getSharedPreferences("daily_config", Context.MODE_PRIVATE);
    }

    /** 读取元配置（模板）。null → 用全任务默认值初始化。 */
    public Map<String, Boolean> metaConfig(List<DailyTasks.TaskDef> defs) {
        Map<String, Boolean> m = readJson("meta");
        if (m == null) {
            m = new LinkedHashMap<>();
            for (DailyTasks.TaskDef d : defs) {
                m.put(d.id, d.enabledByDefault);
            }
            writeJson("meta", m);
        }
        return m;
    }

    /** 读取账号配置（旧：整号维度）；无记录时返回 null（区别于空）。 */
    public Map<String, Boolean> accountConfig(String fpid) {
        return readJson("acc:" + fpid);
    }

    /** 角色（区服）维度配置：acc:<fpid>:<serverid>，互不覆盖。 */
    public Map<String, Boolean> roleConfig(String fpid, long serverid) {
        Map<String, Boolean> role = readJson("acc:" + fpid + ":" + serverid);
        if (role != null) return role;
        // 迁移：旧整号配置作为默认；否则用元配置模板初始化
        Map<String, Boolean> legacy = readJson("acc:" + fpid);
        if (legacy == null) {
            legacy = readJson("meta");
            if (legacy == null) return null;
        }
        writeJson("acc:" + fpid + ":" + serverid, legacy);
        return legacy;
    }

    public void saveRoleConfig(String fpid, long serverid, Map<String, Boolean> config) {
        writeJson("acc:" + fpid + ":" + serverid, config);
    }

    public void saveAccountConfig(String fpid, Map<String, Boolean> config) {
        writeJson("acc:" + fpid, config);
    }

    public void saveMetaConfig(Map<String, Boolean> config) {
        writeJson("meta", config);
    }

    /** 同步结果：覆盖差异/补齐缺失/清理多余 的计数与校验结论。 */
    public static class SyncReport {
        public int covered;   // 值不同的项被覆盖
        public int added;     // 元配置有、账号缺的项补齐
        public int removed;   // 账号有、元配置没有的多余项清理
        public boolean valid;
        public String invalidReason = "";
    }

    /**
     * 一键同步：把账号配置修改为与元配置完全一致。
     * 返回操作报告（含校验：同步后逐项比对必须全等）。
     */
    public SyncReport syncRoleToMeta(String fpid, long serverid, Map<String, Boolean> meta) {
        SyncReport r = new SyncReport();
        Map<String, Boolean> acc = readJson("acc:" + fpid + ":" + serverid);
        if (acc == null) acc = new LinkedHashMap<>();
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (Map.Entry<String, Boolean> e : meta.entrySet()) {
            Boolean oldV = acc.get(e.getKey());
            if (oldV == null) r.added++;
            else if (!oldV.equals(e.getValue())) r.covered++;
            result.put(e.getKey(), e.getValue());
        }
        for (String key : acc.keySet()) {
            if (!meta.containsKey(key)) r.removed++;
        }
        saveRoleConfig(fpid, serverid, result);
        Map<String, Boolean> verify = readJson("acc:" + fpid + ":" + serverid);
        r.valid = verify != null && verify.equals(meta);
        if (!r.valid) r.invalidReason = "同步后校验不一致，请重试";
        return r;
    }

    public SyncReport syncAccountToMeta(String fpid, Map<String, Boolean> meta) {
        SyncReport r = new SyncReport();
        Map<String, Boolean> acc = accountConfig(fpid);
        if (acc == null) acc = new LinkedHashMap<>();
        Map<String, Boolean> result = new LinkedHashMap<>();

        // 1) 覆盖差异 + 补齐缺失（以元配置键序为准）
        for (Map.Entry<String, Boolean> e : meta.entrySet()) {
            Boolean old = acc.get(e.getKey());
            if (old == null) {
                r.added++;
            } else if (!old.equals(e.getValue())) {
                r.covered++;
            }
            result.put(e.getKey(), e.getValue());
        }
        // 2) 清理多余项
        for (String key : acc.keySet()) {
            if (!meta.containsKey(key)) r.removed++;
        }
        // 3) 落盘 + 校验（重读后与元配置全等）
        saveAccountConfig(fpid, result);
        Map<String, Boolean> verify = accountConfig(fpid);
        r.valid = verify != null && verify.equals(meta);
        if (!r.valid) {
            r.invalidReason = "同步后校验不一致，请重试";
        }
        return r;
    }

    /** 配置合法性检查：键必须全部是已知任务 id（供 UI 在保存元配置前校验）。 */
    public static String validate(Map<String, Boolean> config, List<DailyTasks.TaskDef> defs) {
        if (config == null || config.isEmpty()) return "配置为空";
        List<String> known = new ArrayList<>();
        for (DailyTasks.TaskDef d : defs) known.add(d.id);
        for (String key : config.keySet()) {
            if (!known.contains(key)) return "未知任务项: " + key;
        }
        return "";
    }

    private Map<String, Boolean> readJson(String key) {
        try {
            String raw = prefs.getString(key, null);
            if (raw == null) return null;
            JSONObject o = new JSONObject(raw);
            Map<String, Boolean> m = new LinkedHashMap<>();
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                m.put(k, o.optBoolean(k, false));
            }
            // id 统一迁移：旧 free_shield → battle_shield（读取即迁移并回写，存量配置不丢开关值）
            if (m.containsKey(LEGACY_SHIELD_ID)) {
                if (!m.containsKey(SHIELD_ID)) m.put(SHIELD_ID, m.remove(LEGACY_SHIELD_ID));
                else m.remove(LEGACY_SHIELD_ID);
                writeJson(key, m);
            }
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    private void writeJson(String key, Map<String, Boolean> config) {
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, Boolean> e : config.entrySet()) {
                o.put(e.getKey(), e.getValue());
            }
            prefs.edit().putString(key, o.toString()).apply();
        } catch (Exception ignored) {
        }
    }
}
