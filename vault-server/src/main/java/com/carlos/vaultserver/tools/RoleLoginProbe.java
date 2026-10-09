package com.carlos.vaultserver.tools;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.ClasspathAssets;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 按角色登录实验台 / 运维诊断（本地或服务器跑）。
 *
 * 用法：java -cp vault-server-all.jar com.carlos.vaultserver.tools.RoleLoginProbe \
 *           &lt;fpid&gt; &lt;token&gt; &lt;变体&gt; [值]
 * 变体：plain | serverid=&lt;v&gt; | kid=&lt;v&gt; | uid=&lt;v&gt; | role_uid=&lt;v&gt; | 组合 kid:&lt;v&gt;+uid:&lt;v&gt;
 * 输出：loginauth 原文（截断）→ 实际绑定角色（昵称@kid 熔炉/战力）→ 角色清单。
 * 注意：每次 loginauth 会踢掉该账号既有会话（游戏按账号唯一会话），生产上会自愈。
 */
public final class RoleLoginProbe {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("用法: RoleLoginProbe <fpid> <token> [variant=value ...]");
            System.out.println("变体: plain serverid=913 kid=913 uid=20778222 role_uid=...");
            return;
        }
        String fpid = args[0];
        String token = args[1];

        Long serverid = null;
        Map<String, Object> extra = new LinkedHashMap<>();
        String variantDesc = "plain";
        for (int i = 2; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("serverid=")) {
                serverid = Long.parseLong(a.substring(9));
                variantDesc = a;
            } else if (a.contains("=")) {
                int eq = a.indexOf('=');
                extra.put(a.substring(0, eq), parse(a.substring(eq + 1)));
                variantDesc = variantDesc.equals("plain") ? a : variantDesc + "+" + a;
            }
        }

        WjdrEngine engine = new WjdrEngine(new ClasspathAssets());
        engine.initialize();
        System.out.println("== 变体: " + variantDesc + " (serverid=" + serverid + ", extra=" + extra + ")");
        WjdrEngine.LoginResult r = engine.login(fpid, token, serverid, extra);
        System.out.println("== auth 原文: " + trunc(String.valueOf(r.authContent), 600));
        System.out.println("== LoginResult uid=" + r.uid + " serverId=" + r.serverId
                + " level=" + r.level + " power=" + r.power);
        whoAmI(engine, r.uid);
        roleList(engine);
        System.exit(0);
    }

    static Object parse(String v) {
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return v;
        }
    }

    static void whoAmI(WjdrEngine engine, long uid) {
        try {
            GameMessage pbi = engine.request("req_players_base_info",
                    Collections.singletonMap("uids", Collections.singletonList(uid)));
            Object result = pbi == null || pbi.getContent() == null ? null : pbi.getContent().get("result");
            if (result instanceof Map) {
                for (Object v : ((Map<?, ?>) result).values()) {
                    if (v instanceof Map) {
                        Map<?, ?> p = (Map<?, ?>) v;
                        Object alliance = p.get("alliance") instanceof Map
                                ? ((Map<?, ?>) p.get("alliance")).get("abbr") : "-";
                        System.out.println("★★ 实际绑定角色: " + p.get("nickName") + " @kid=" + p.get("kid")
                                + " 熔炉=" + p.get("stove_lv") + " 战力=" + p.get("power")
                                + " 联盟=" + alliance);
                        return;
                    }
                }
            }
            System.out.println("== whoAmI 解析失败: " + trunc(String.valueOf(
                    pbi == null ? null : pbi.getContent()), 300));
        } catch (Throwable t) {
            System.out.println("== whoAmI 失败: " + t);
        }
    }

    static void roleList(WjdrEngine engine) {
        try {
            GameMessage rl = engine.request("req_role_list", null);
            System.out.println("== 角色清单: " + trunc(String.valueOf(
                    rl == null ? null : rl.getContent()), 600));
        } catch (Throwable t) {
            System.out.println("== 角色清单失败: " + t);
        }
    }

    static String trunc(String s, int n) {
        if (s == null) return "null";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private RoleLoginProbe() {
    }
}
