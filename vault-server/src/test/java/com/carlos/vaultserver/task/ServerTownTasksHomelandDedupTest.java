package com.carlos.vaultserver.task;

import static org.junit.Assert.assertEquals;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.StateStore;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 晨曦岛 15018/15071 当日/当周去重（2026-11-11 修复回归）。
 *
 * <p>生产实证：免费商店/每周钻石连续 3 天每天每角色失败 15–21 次；修复后同一轮只请求一次，
 * 同日/同周不再重复请求，避免 ERROR 噪声与无效协议。
 */
public class ServerTownTasksHomelandDedupTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final class FakeEngine extends WjdrEngine {
        final List<String> calls = new ArrayList<>();

        FakeEngine() {
            super(null);
        }

        @Override
        public void collectNotifies(long millis) {
        }

        @Override
        public Map<String, Object> notifyContent(String proto) {
            return null;
        }

        @Override
        public Map<String, Object> notifyData(String proto) {
            return null;
        }

        @Override
        public GameMessage request(String proto, Map<String, Object> args) {
            calls.add(proto);
            Map<String, Object> c = new LinkedHashMap<>();
            if ("req_homeland_buy_store_item".equals(proto)) {
                c.put("code", 15018);
            } else if ("req_homeland_token_shop_weekly_recv".equals(proto)) {
                c.put("code", 15071);
            } else {
                c.put("code", 0);
            }
            GameMessage g = new GameMessage();
            g.getContent().putAll(c);
            return g;
        }
    }

    private static int count(FakeEngine e, String proto) {
        int n = 0;
        for (String p : e.calls) {
            if (proto.equals(p)) n++;
        }
        return n;
    }

    @Test
    public void homeland15018And15071_dedupeSameDayAndWeek() throws Exception {
        StateStore store = new StateStore(tmp.getRoot().toPath());
        FakeEngine engine = new FakeEngine();
        TaskContext tc = new TaskContext(engine, "48460403", 888L, store,
                new HashMap<>(), "测试");

        ServerTownTasks.homeland(tc);
        assertEquals("首轮免费商店应请求一次", 1, count(engine, "req_homeland_buy_store_item"));
        assertEquals("首轮每周钻石应请求一次", 1, count(engine, "req_homeland_token_shop_weekly_recv"));

        ServerTownTasks.homeland(tc);
        assertEquals("同日不再重复请求免费商店", 1, count(engine, "req_homeland_buy_store_item"));
        assertEquals("同周不再重复请求每周钻石", 1, count(engine, "req_homeland_token_shop_weekly_recv"));
    }
}
