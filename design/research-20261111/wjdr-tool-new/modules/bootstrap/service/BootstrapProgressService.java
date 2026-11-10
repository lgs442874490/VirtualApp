/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.springframework.stereotype.Service
 */
package com.wjdr.tool.modules.bootstrap.service;

import com.wjdr.tool.core.model.GameModule;
import com.wjdr.tool.core.realtime.RealtimeUpdateService;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public final class BootstrapProgressService {
    public static final String SCOPE_PROGRESS = "progress";
    public static final String SCOPE_STATUS = "status";
    static final int MAX_ENTRIES = 3000;
    private final RealtimeUpdateService realtime;
    private final Map<Long, RoleProgress> roles = new ConcurrentHashMap<Long, RoleProgress>();

    public BootstrapProgressService(RealtimeUpdateService realtime) {
        this.realtime = realtime;
    }

    public void note(long accountRoleId, String level, String message) {
        String text;
        if (accountRoleId <= 0L) {
            return;
        }
        String string = text = message == null ? "" : message.trim();
        if (text.isEmpty()) {
            return;
        }
        RoleProgress progress = this.roles.computeIfAbsent(accountRoleId, ignored -> new RoleProgress());
        if (progress.isDuplicate(text)) {
            return;
        }
        progress.append(level, text);
        this.broadcast(accountRoleId, Set.of(SCOPE_PROGRESS));
    }

    public boolean markFinished(long accountRoleId, String summary) {
        if (accountRoleId <= 0L) {
            return false;
        }
        RoleProgress progress = this.roles.computeIfAbsent(accountRoleId, ignored -> new RoleProgress());
        if (progress.isFinished()) {
            return false;
        }
        progress.markFinished();
        this.note(accountRoleId, "success", summary == null || summary.isBlank() ? "\u8d77\u53f7\u5b8c\u6bd5" : summary);
        this.broadcast(accountRoleId, Set.of(SCOPE_STATUS, SCOPE_PROGRESS));
        return true;
    }

    public void clearFinished(long accountRoleId) {
        RoleProgress progress = this.roles.get(accountRoleId);
        if (progress != null) {
            progress.clearFinished();
        }
    }

    public boolean finished(long accountRoleId) {
        RoleProgress progress = this.roles.get(accountRoleId);
        return progress != null && progress.isFinished();
    }

    public long lastSeq(long accountRoleId) {
        RoleProgress progress = this.roles.get(accountRoleId);
        return progress == null ? 0L : progress.lastSeq();
    }

    public Map<String, Object> snapshot(long accountRoleId, long afterSeq) {
        RoleProgress progress = this.roles.get(accountRoleId);
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("accountRoleId", accountRoleId);
        if (progress == null) {
            result.put("entries", List.of());
            result.put("lastSeq", 0L);
            result.put("finished", false);
            return result;
        }
        ArrayList entries = new ArrayList();
        for (Entry entry : progress.entriesAfter(afterSeq)) {
            LinkedHashMap<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("seq", entry.seq());
            item.put("at", entry.at());
            item.put("level", entry.level());
            item.put("message", BootstrapProgressService.normalize(entry.message()));
            entries.add(item);
        }
        result.put("entries", entries);
        result.put("lastSeq", progress.lastSeq());
        result.put("startedAt", progress.startedAt());
        result.put("finished", progress.isFinished());
        result.put("finishedAt", progress.finishedAt());
        return result;
    }

    static String normalize(String message) {
        if (message == null) {
            return "";
        }
        String text = message.trim();
        text = text.replaceAll("\\s*\u7684\u961f\u5217\\s*\\d+", "");
        text = text.replace("\u5df2\u8bf7\u6c42", "");
        text = text.replaceAll("\uff08instantDiamond=[a-z]+\uff09", "");
        text = text.replaceAll("\\s{2,}", " ");
        return text;
    }

    private void broadcast(long accountRoleId, Set<String> scopes) {
        this.realtime.invalidateRole(GameModule.BOOTSTRAP.key(), accountRoleId, 0L, 0L, scopes);
    }

    private static final class RoleProgress {
        private final Deque<Entry> entries = new ArrayDeque<Entry>();
        private long seq;
        private long startedAt;
        private boolean finished;
        private long finishedAt;

        private RoleProgress() {
        }

        private synchronized boolean isDuplicate(String message) {
            if (this.entries.isEmpty()) {
                return false;
            }
            Entry last = this.entries.peekLast();
            return last != null && last.message().equals(message) && System.currentTimeMillis() - last.at() < 90000L;
        }

        private synchronized void append(String level, String message) {
            if (this.entries.isEmpty()) {
                this.startedAt = System.currentTimeMillis();
            }
            this.entries.addLast(new Entry(++this.seq, System.currentTimeMillis(), level == null || level.isBlank() ? "info" : level, message));
            while (this.entries.size() > 3000) {
                this.entries.removeFirst();
            }
        }

        private synchronized void markFinished() {
            this.finished = true;
            this.finishedAt = System.currentTimeMillis();
        }

        private synchronized void clearFinished() {
            this.finished = false;
            this.finishedAt = 0L;
        }

        private synchronized boolean isFinished() {
            return this.finished;
        }

        private synchronized long lastSeq() {
            return this.seq;
        }

        private synchronized long startedAt() {
            return this.startedAt;
        }

        private synchronized long finishedAt() {
            return this.finishedAt;
        }

        private synchronized List<Entry> entriesAfter(long afterSeq) {
            ArrayList<Entry> result = new ArrayList<Entry>();
            for (Entry entry : this.entries) {
                if (entry.seq() <= afterSeq) continue;
                result.add(entry);
            }
            return result;
        }
    }

    record Entry(long seq, long at, String level, String message) {
    }
}

