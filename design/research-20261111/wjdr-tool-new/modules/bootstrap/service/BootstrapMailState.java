/*
 * Decompiled with CFR 0.152.
 */
package com.wjdr.tool.modules.bootstrap.service;

import com.wjdr.tool.core.protocol.GameMessage;
import com.wjdr.tool.modules.bootstrap.service.BootstrapProtocolSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BootstrapMailState {
    private static final String TYPE_INFO = "notify_mail_type_info_syn";
    private static final String NEW_MAIL = "notify_new_mail_syn";
    private static final long ATTACH_CLAIMABLE = 1L;
    private final Map<Long, Long> unreadCounts = new LinkedHashMap<Long, Long>();
    private final Map<Long, Boolean> claimableAttachments = new LinkedHashMap<Long, Boolean>();
    private final Map<Long, List<Mail>> mails = new LinkedHashMap<Long, List<Mail>>();
    private static final long REQUERY_INTERVAL_MILLIS = 60000L;
    private final Map<Long, Long> lastQueryMillis = new LinkedHashMap<Long, Long>();
    private final Set<Long> handledTypes = new LinkedHashSet<Long>();
    private boolean mailReady;

    BootstrapMailState() {
    }

    void accept(GameMessage message) {
        if (!GameMessage.hasProtocolContent(message)) {
            return;
        }
        Map<String, Object> data = message.getContent();
        switch (message.getProto()) {
            case "notify_mail_type_info_syn": {
                this.applyTypeInfo(data.get("infolist"));
                break;
            }
            case "notify_new_mail_syn": {
                this.applyNewMail(data.get("mail_cover_info"));
                break;
            }
        }
    }

    void applyTypeInfo(Object raw) {
        this.unreadCounts.clear();
        this.claimableAttachments.clear();
        for (Map<String, Object> entry : BootstrapProtocolSupport.maps(raw)) {
            long type = BootstrapProtocolSupport.number(entry.get("type"));
            if (type <= 0L) continue;
            this.unreadCounts.put(type, BootstrapProtocolSupport.number(entry.get("un_readnum")));
            this.claimableAttachments.put(type, BootstrapProtocolSupport.number(entry.get("can_get_attach")) > 0L);
        }
        this.mailReady = true;
    }

    private void applyNewMail(Object raw) {
        Map<String, Object> cover = BootstrapProtocolSupport.map(raw);
        if (cover.isEmpty()) {
            return;
        }
        this.addMail(cover);
        this.mailReady = true;
    }

    void addMail(Map<String, Object> cover) {
        long type = BootstrapProtocolSupport.number(cover.get("first_type"));
        String mailId = BootstrapProtocolSupport.text(cover.get("mailid_new"));
        if (type <= 0L || mailId.isEmpty()) {
            return;
        }
        boolean read = BootstrapProtocolSupport.number(cover.get("isread")) > 0L;
        boolean attach = BootstrapProtocolSupport.number(cover.get("attach_statue")) == 1L;
        List list = this.mails.computeIfAbsent(type, ignored -> new ArrayList());
        for (int index = 0; index < list.size(); ++index) {
            if (!((Mail)list.get(index)).mailIdNew().equals(mailId)) continue;
            list.set(index, new Mail(mailId, read, attach));
            return;
        }
        list.add(new Mail(mailId, read, attach));
    }

    boolean mailReady() {
        return this.mailReady;
    }

    List<Long> pendingTypes() {
        LinkedHashSet types = new LinkedHashSet();
        this.unreadCounts.forEach((type, count) -> {
            if (count > 0L) {
                types.add(type);
            }
        });
        this.claimableAttachments.forEach((type, claimable) -> {
            if (Boolean.TRUE.equals(claimable)) {
                types.add(type);
            }
        });
        this.mails.forEach((type, list) -> {
            if (list.stream().anyMatch(Mail::actionable)) {
                types.add(type);
            }
        });
        return List.copyOf(types);
    }

    List<String> actionableMailIds(long firstType) {
        ArrayList<String> result = new ArrayList<String>();
        for (Mail mail : this.mails.getOrDefault(firstType, List.of())) {
            if (!mail.actionable()) continue;
            result.add(mail.mailIdNew());
        }
        return result;
    }

    boolean needsCoverQuery(long firstType) {
        if (!this.actionableMailIds(firstType).isEmpty()) {
            return false;
        }
        boolean serverPending = this.unreadCounts.getOrDefault(firstType, 0L) > 0L || Boolean.TRUE.equals(this.claimableAttachments.get(firstType));
        return serverPending || !this.handledTypes.contains(firstType) || System.currentTimeMillis() - this.lastQueryMillis.getOrDefault(firstType, 0L) >= 60000L;
    }

    long lastQueryMillis(long firstType) {
        return this.lastQueryMillis.getOrDefault(firstType, 0L);
    }

    void markQueried(long firstType, long nowMillis) {
        this.lastQueryMillis.put(firstType, nowMillis);
    }

    void markTypeHandled(long firstType) {
        this.handledTypes.add(firstType);
        this.mails.remove(firstType);
        this.unreadCounts.remove(firstType);
        this.claimableAttachments.put(firstType, false);
    }

    void markRead(long firstType, List<String> mailIds) {
        List<Mail> list = this.mails.get(firstType);
        if (list == null) {
            return;
        }
        Set<String> done = Set.copyOf(mailIds);
        list.removeIf(mail -> done.contains(mail.mailIdNew()));
        if (list.isEmpty()) {
            this.handledTypes.add(firstType);
            this.unreadCounts.put(firstType, 0L);
            this.claimableAttachments.put(firstType, false);
        }
    }

    record Mail(String mailIdNew, boolean read, boolean claimableAttachment) {
        boolean actionable() {
            return !this.read || this.claimableAttachment;
        }
    }
}

