package com.carlos.vault;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.carlos.vault.daily.RoleStore;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * syncFields 角色行 JSON 组装纯函数（{@link VaultSync#roleFields}，M2b 抽出）单测：
 * 旧字段（sid/uid/identity/config）格式不回归 + 新增账号池元数据
 * name/level/power/enabled 的类型与空值兜底（M1 服务端 /v1/vault/accounts 的
 * 显示名/等级/战力与 enabled 批量动作依赖）。纯 JVM——roleFields 返回 Map、
 * 此处断言 Map（JSONObject(Map) 包装与 params 追加耦合 Context，由真机 M4 验证）；
 * 真实 JSON 解析的解析辅助（isRoleOnline 等）见 AccountPoolCacheTest
 * （org.json:json 测试依赖覆盖 android.jar 桩）。
 */
public class VaultSyncRoleFieldsTest {

    /** 测试角色：全字段显式可设（字段默认值见 {@link RoleStore.Role}）。 */
    private static RoleStore.Role role(long sid, long uid, String name,
                                       long level, long power, boolean enabled) {
        RoleStore.Role r = new RoleStore.Role();
        r.serverid = sid;
        r.uid = uid;
        r.name = name;
        r.level = level;
        r.power = power;
        r.enabled = enabled;
        return r;
    }

    // ---------- 完整元数据映射（类型逐一对齐 M1 契约） ----------

    @Test
    public void fields_fullMetadata_typesAndValues() {
        Map<String, Boolean> cfg = new LinkedHashMap<>();
        cfg.put("daily", Boolean.TRUE);

        Map<String, Object> m = VaultSync.roleFields(
                role(1234L, 5678L, "小鹿", 32L, 9_876_543L, true), "idn-json", cfg);

        // 旧字段格式不回归：sid/uid 字符串、identity 原样、config 整表
        assertEquals("1234", m.get("sid"));
        assertEquals("5678", m.get("uid"));
        assertEquals("idn-json", m.get("identity"));
        assertEquals(cfg, m.get("config"));
        // 新增元数据：name 字符串、level/power long、enabled boolean
        assertEquals("小鹿", m.get("name"));
        assertEquals(32L, m.get("level"));
        assertEquals(9_876_543L, m.get("power"));
        assertEquals(Boolean.TRUE, m.get("enabled"));
    }

    // ---------- 空值兜底 ----------

    @Test
    public void fields_nullAndUnknown_fallBackLikeServerContract() {
        Map<String, Object> m = VaultSync.roleFields(
                role(99L, 0L, null, 0L, 0L, false), null, null);

        assertEquals("", m.get("name"));
        assertEquals("", m.get("uid"));       // uid≤0 → 空串（与服务端字符串口径一致）
        assertEquals("", m.get("identity"));
        assertEquals(0L, m.get("level"));
        assertEquals(0L, m.get("power"));
        assertEquals(Boolean.FALSE, m.get("enabled"));
        assertFalse("config 为 null 时不得输出该键", m.containsKey("config"));
    }

    // ---------- 键序（LinkedHashMap → JSONObject(Map) 包装后线上字节序稳定） ----------

    @Test
    public void fields_keyOrder_oldFieldsFirst_newMetadataAfterUid() {
        Map<String, Object> m = VaultSync.roleFields(role(1L, 2L, "n", 3L, 4L, true), "i", null);

        assertEquals(Arrays.asList("sid", "uid", "name", "level", "power",
                "enabled", "identity"), new ArrayList<>(m.keySet()));
    }
}
