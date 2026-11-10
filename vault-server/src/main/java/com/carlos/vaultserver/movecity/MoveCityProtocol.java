package com.carlos.vaultserver.movecity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 迁城协议注册表（1:1 复刻网页版 {@code MoveCityService.PROTOCOLS}，2026-11-06）。
 *
 * <p>基准：{@code wjdr-review/src-tool/com/wjdr/tool/modules/movecity/service/MoveCityService.java:100}
 * 的 12 项 {@code MoveCityServiceMoveProtocol(label, proto, needsPoint, needsKid, requiredItemId,
 * itemShortageCode)}。字段语义：
 * <ul>
 *   <li>{@code needsPoint}：payload 需带 {@code point:{x,y}}（目标坐标）；</li>
 *   <li>{@code needsKid}：payload 需带 {@code kid}（目标王国/服务器 ID，跨王国族）；</li>
 *   <li>{@code requiredItemId}：消耗的迁城道具（仅展示/说明用，如 610104 高级迁城）；</li>
 *   <li>{@code itemShortageCode}：该协议「道具不足」专属错误码（命中即短路停止重试，
 *       绝不钻石补购——对齐原版 {@code MOVE_ITEM_SHORTAGE_MESSAGE} 语义）。</li>
 * </ul>
 *
 * <p>本项目寻址差异：原版按 {@code accountRoleId} 寻址账号，本项目按号库 {@code fpid}
 * 寻址（active 角色会话），语义等价（需求文档 H2/H3）。
 */
public record MoveCityProtocol(String label, String proto, boolean needsPoint, boolean needsKid,
                               long requiredItemId, int itemShortageCode) {

    /** 12 项协议（顺序、字段与原版逐一对应）。 */
    public static final List<MoveCityProtocol> PROTOCOLS = List.of(
            new MoveCityProtocol("迁城", "req_world_move_city", true, false, 610104L, 2835),
            new MoveCityProtocol("随机迁城", "req_world_random_move_city", false, false, 610101L, 0),
            new MoveCityProtocol("霜龙迁城", "req_supremacy_war_move_city", true, false, 0L, 0),
            new MoveCityProtocol("燃霜迁城", "req_frostfire_war_move_city", true, false, 610104L, 0),
            new MoveCityProtocol("大乱斗迁城", "req_brawl_war_move_city", true, false, 610104L, 40652),
            new MoveCityProtocol("联盟迁城", "req_world_alliance_move_city", true, false, 610102L, 2875),
            new MoveCityProtocol("领地迁城", "req_world_alliance_territory_move_city", true, false, 610103L, 5108),
            new MoveCityProtocol("兵工厂迁城", "req_arsenal_war_move_city", true, false, 610104L, 9003),
            new MoveCityProtocol("跨王国随机迁城", "req_world_wormhole_jumping_random", false, true, 0L, 0),
            new MoveCityProtocol("跨王国回家", "req_world_gohome", false, false, 0L, 0),
            new MoveCityProtocol("跨王国高级迁城", "req_world_wormhole_jumping_to_point", true, true, 0L, 0),
            new MoveCityProtocol("跨王国高级回家", "req_world_gohome_to_point", true, false, 0L, 0));

    /** 按 proto 名（App 传协议标识）查表；找不到返回 null（上层报「不支持的迁城协议」）。 */
    public static MoveCityProtocol byProto(String proto) {
        if (proto == null || proto.isEmpty()) return null;
        for (MoveCityProtocol p : PROTOCOLS) {
            if (p.proto.equals(proto)) return p;
        }
        return null;
    }

    /** 按中文名查表（原版 resolveProtocol 兼容 label 入参）。 */
    public static MoveCityProtocol byLabel(String label) {
        if (label == null || label.isEmpty()) return null;
        for (MoveCityProtocol p : PROTOCOLS) {
            if (p.label.equals(label)) return p;
        }
        return null;
    }

    /** proto 或 label 任一命中（对齐原版 resolveProtocol：先 proto 后 label）。 */
    public static MoveCityProtocol resolve(String value) {
        MoveCityProtocol p = byProto(value);
        return p != null ? p : byLabel(value);
    }

    /**
     * 构造协议 payload（1:1 原版 buildPayload：needsPoint→point{x,y}；needsKid→kid；可并存）。
     */
    public Map<String, Object> buildPayload(int x, int y, int kid) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (needsKid) payload.put("kid", kid);
        if (needsPoint) payload.put("point", Map.of("x", x, "y", y));
        return payload;
    }

    /** API 展示形状（字段名对齐原版 toMap）。 */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("proto", proto);
        m.put("needs_point", needsPoint);
        m.put("needs_kid", needsKid);
        m.put("required_item_id", requiredItemId);
        m.put("item_shortage_code", itemShortageCode);
        return m;
    }

    /** 协议表 API 形状。 */
    public static List<Map<String, Object>> protocolsMaps() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MoveCityProtocol p : PROTOCOLS) out.add(p.toMap());
        return out;
    }
}
