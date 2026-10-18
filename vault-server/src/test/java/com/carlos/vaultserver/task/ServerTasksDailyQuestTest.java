package com.carlos.vaultserver.task;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/** 每日任务：finishs 未领解析 + 通知 key 的 ".0" 浮点格式兼容。 */
public class ServerTasksDailyQuestTest {

    @Test
    public void unclaimedIds_parseValueAndFloatKey() {
        Map<String, Object> finishs = new LinkedHashMap<>();

        Map<String, Object> claimed = new LinkedHashMap<>();
        claimed.put("id", 1320000.0);
        claimed.put("isgetaward", true);
        finishs.put("1320000.0", claimed);

        Map<String, Object> unclaimedValue = new LinkedHashMap<>();
        unclaimedValue.put("id", 1320090.0);
        unclaimedValue.put("isgetaward", false);
        finishs.put("1320090.0", unclaimedValue);

        // 只有 key、没有 id 字段：也必须能从 "1320210.0" 解析出 1320210
        Map<String, Object> unclaimedKeyOnly = new LinkedHashMap<>();
        unclaimedKeyOnly.put("isgetaward", false);
        finishs.put("1320210.0", unclaimedKeyOnly);

        Map<String, Object> notify = new LinkedHashMap<>();
        notify.put("finishs", finishs);

        List<Long> ids = ServerTasks.unclaimedQuestIds(notify);
        assertEquals(2, ids.size());
        assertEquals(Long.valueOf(1320090L), ids.get(0));
        assertEquals(Long.valueOf(1320210L), ids.get(1));
        assertEquals(2, ServerTasks.countUnclaimedQuestAwards(notify));
        assertEquals(3, ServerTasks.finishCount(finishs));
    }

    /** 专家额外情报/曙光市集：finishs 是数组，已领/重复 id 必须过滤。 */
    @Test
    public void unclaimedFinishIds_arrayShape() {
        Map<String, Object> open = new LinkedHashMap<>();
        open.put("id", 7001);
        open.put("isgetaward", false);
        Map<String, Object> done = new LinkedHashMap<>();
        done.put("id", 7002);
        done.put("isgetaward", true);
        Map<String, Object> missingFlag = new LinkedHashMap<>();
        missingFlag.put("id", 7003);
        Map<String, Object> duplicate = new LinkedHashMap<>();
        duplicate.put("id", 7001);
        duplicate.put("isgetaward", false);

        List<Object> finishs = new java.util.ArrayList<>();
        finishs.add(open);
        finishs.add(done);
        finishs.add(missingFlag);
        finishs.add(duplicate);

        List<Long> ids = ServerTasks.unclaimedFinishIds(finishs);
        assertEquals(2, ids.size());
        assertEquals(Long.valueOf(7001L), ids.get(0));
        assertEquals(Long.valueOf(7003L), ids.get(1));
        assertEquals(4, ServerTasks.finishCount(finishs));
    }

    /** 数组里 isgetaward 有时被解成字符串 "true"，同样不能重复请求。 */
    @Test
    public void unclaimedFinishIds_stringFlag() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("id", 8001);
        f.put("isgetaward", "true");
        List<Object> finishs = new java.util.ArrayList<>();
        finishs.add(f);
        assertEquals(0, ServerTasks.unclaimedFinishIds(finishs).size());
    }

    @Test
    public void allClaimed_returnsEmpty() {
        Map<String, Object> finishs = new LinkedHashMap<>();
        Map<String, Object> claimed = new LinkedHashMap<>();
        claimed.put("id", 1320081.0);
        claimed.put("isgetaward", true);
        finishs.put("1320081.0", claimed);
        Map<String, Object> notify = new LinkedHashMap<>();
        notify.put("finishs", finishs);
        assertEquals(0, ServerTasks.unclaimedQuestIds(notify).size());
    }
}
