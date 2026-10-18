package com.carlos.vaultserver.automation;

import com.carlos.vaultserver.task.TaskOutcome;

import java.util.ArrayList;
import java.util.List;

/** 一个模块一轮执行的结果；动作级结果保留原 taskId，供 RoundRunner 落 log/st。 */
public final class ModuleResult {

    public static final class Entry {
        public final String actionId;
        public final TaskOutcome outcome;

        Entry(String actionId, TaskOutcome outcome) {
            this.actionId = actionId;
            this.outcome = outcome;
        }
    }

    public final String moduleId;
    public final List<Entry> entries = new ArrayList<>();

    public ModuleResult(String moduleId) {
        this.moduleId = moduleId;
    }

    public void add(String actionId, TaskOutcome outcome) {
        if (outcome != null) entries.add(new Entry(actionId, outcome));
    }

    public boolean hasFailure() {
        for (Entry e : entries) {
            if (e.outcome.category == TaskOutcome.Category.ERROR
                    || e.outcome.category == TaskOutcome.Category.DEGRADED) return true;
        }
        return false;
    }

    public int count(TaskOutcome.Category category) {
        int n = 0;
        for (Entry e : entries) if (e.outcome.category == category) n++;
        return n;
    }
}
