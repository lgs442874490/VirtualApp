/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  lombok.Generated
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 *  org.springframework.stereotype.Service
 */
package com.wjdr.tool.modules.bootstrap.service;

import com.wjdr.tool.core.account.GameAccountSessionService;
import com.wjdr.tool.core.protocol.GameMessage;
import com.wjdr.tool.core.protocol.GameProtocolException;
import com.wjdr.tool.modules.bootstrap.service.BootstrapMailRegistry;
import com.wjdr.tool.modules.bootstrap.service.BootstrapMailState;
import com.wjdr.tool.modules.bootstrap.service.BootstrapProtocolSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.Generated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public final class BootstrapMailService {
    @Generated
    private static final Logger log = LoggerFactory.getLogger(BootstrapMailService.class);
    private static final String COVER_LIST = "req_mail_cover_list";
    private static final String BATCH_READ = "req_batch_mail_read";
    private static final long PAGE_SIZE = 40L;
    private static final long QUERY_INTERVAL_MILLIS = 15000L;
    private final BootstrapMailRegistry mailStates;

    BootstrapMailService(BootstrapMailRegistry mailStates) {
        this.mailStates = mailStates;
    }

    Optional<Map<String, Object>> advance(long accountRoleId, GameAccountSessionService.AccountSession session) {
        BootstrapMailState mail = this.mailStates.mailState(accountRoleId);
        if (!mail.mailReady()) {
            return Optional.empty();
        }
        for (long firstType : mail.pendingTypes()) {
            List<String> mailIds = mail.actionableMailIds(firstType);
            if (!mailIds.isEmpty()) {
                return this.requestBatchRead(accountRoleId, session, mail, firstType, mailIds);
            }
            Optional<Map<String, Object>> queried = this.queryCoverList(accountRoleId, session, mail, firstType);
            if (!queried.isPresent()) continue;
            return queried;
        }
        return Optional.empty();
    }

    private Optional<Map<String, Object>> requestBatchRead(long accountRoleId, GameAccountSessionService.AccountSession session, BootstrapMailState mail, long firstType, List<String> mailIds) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("first_type", firstType);
        payload.put("mid_list_new", mailIds);
        try {
            GameMessage response = session.request(BATCH_READ, payload);
            mail.markRead(firstType, mailIds);
            List<Map<String, Object>> rewards = BootstrapProtocolSupport.maps(BootstrapProtocolSupport.content(response).get("rewards"));
            LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("accountRoleId", accountRoleId);
            result.put("status", "STARTED");
            result.put("message", "\u5df2\u6279\u91cf\u5df2\u8bfb\u5e76\u9886\u53d6 " + mailIds.size() + " \u5c01\u90ae\u4ef6\u9644\u4ef6");
            result.put("mailAction", "BATCH_READ");
            result.put("mailType", firstType);
            result.put("mailCount", mailIds.size());
            if (!rewards.isEmpty()) {
                result.put("mailRewards", rewards);
            }
            log.info("[\u8d77\u53f7] \u5df2\u5904\u7406\u90ae\u4ef6\u5927\u7c7b {} \u7684 {} \u5c01\u90ae\u4ef6", (Object)firstType, (Object)mailIds.size());
            return Optional.of(result);
        }
        catch (GameProtocolException exception) {
            mail.markTypeHandled(firstType);
            log.info("[\u8d77\u53f7] \u90ae\u4ef6\u5927\u7c7b {} \u6279\u91cf\u5df2\u8bfb\u88ab\u62d2\u7edd code={}", (Object)firstType, (Object)exception.code());
            return Optional.empty();
        }
        catch (RuntimeException exception) {
            mail.markTypeHandled(firstType);
            log.info("[\u8d77\u53f7] \u90ae\u4ef6\u5927\u7c7b {} \u6279\u91cf\u5df2\u8bfb\u5931\u8d25 message={}", (Object)firstType, (Object)exception.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Map<String, Object>> queryCoverList(long accountRoleId, GameAccountSessionService.AccountSession session, BootstrapMailState mail, long firstType) {
        long now = System.currentTimeMillis();
        if (!mail.needsCoverQuery(firstType) || now - mail.lastQueryMillis(firstType) < 15000L) {
            return Optional.empty();
        }
        mail.markQueried(firstType, now);
        LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("first_type", firstType);
        payload.put("beginidx", 1L);
        payload.put("endidx", 40L);
        try {
            GameMessage response = session.request(COVER_LIST, payload);
            List<Map<String, Object>> covers = BootstrapProtocolSupport.maps(BootstrapProtocolSupport.content(response).get("coverlist"));
            for (Map<String, Object> cover : covers) {
                mail.addMail(cover);
            }
            if (mail.actionableMailIds(firstType).isEmpty()) {
                mail.markTypeHandled(firstType);
            }
            LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("accountRoleId", accountRoleId);
            result.put("status", "STARTED");
            result.put("message", "\u5df2\u62c9\u53d6\u90ae\u4ef6\u5927\u7c7b " + firstType + " \u7684 " + covers.size() + " \u5c01\u90ae\u4ef6\u5c01\u9762");
            result.put("mailAction", "COVER_LIST");
            result.put("mailType", firstType);
            return Optional.of(result);
        }
        catch (GameProtocolException exception) {
            mail.markTypeHandled(firstType);
            log.info("[\u8d77\u53f7] \u62c9\u53d6\u90ae\u4ef6\u5927\u7c7b {} \u5c01\u9762\u88ab\u62d2\u7edd code={}", (Object)firstType, (Object)exception.code());
            return Optional.empty();
        }
        catch (RuntimeException exception) {
            mail.markTypeHandled(firstType);
            log.info("[\u8d77\u53f7] \u62c9\u53d6\u90ae\u4ef6\u5927\u7c7b {} \u5c01\u9762\u5931\u8d25 message={}", (Object)firstType, (Object)exception.getMessage());
            return Optional.empty();
        }
    }

    public Map<String, Object> view(long accountRoleId) {
        BootstrapMailState mail = this.mailStates.mailState(accountRoleId);
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("mailReady", mail.mailReady());
        ArrayList types = new ArrayList();
        for (long firstType : mail.pendingTypes()) {
            LinkedHashMap<String, Number> entry = new LinkedHashMap<String, Number>();
            entry.put("firstType", firstType);
            entry.put("actionable", mail.actionableMailIds(firstType).size());
            types.add(entry);
        }
        result.put("pendingTypes", types);
        return result;
    }
}

