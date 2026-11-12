/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  jakarta.annotation.PreDestroy
 *  org.springframework.beans.factory.annotation.Qualifier
 *  org.springframework.stereotype.Service
 */
package com.wjdr.tool.modules.bootstrap.service;

import com.wjdr.tool.core.account.GameAccountSessionService;
import com.wjdr.tool.modules.behemoth.service.AbstractBehemothStateRegistry;
import com.wjdr.tool.modules.behemoth.service.BehemothAccountStateHub;
import com.wjdr.tool.modules.daily.service.DailyIntelligenceState;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class BootstrapIntelligenceRegistry
extends AbstractBehemothStateRegistry {
    private final Map<Long, DailyIntelligenceState> intelligenceStates = new ConcurrentHashMap<Long, DailyIntelligenceState>();
    private final Map<Long, GameAccountSessionService.AccountSession> boundSessions = new ConcurrentHashMap<Long, GameAccountSessionService.AccountSession>();
    private final AutoCloseable sessionCreatedRegistration;
    private final AutoCloseable sessionStoppedRegistration;

    public BootstrapIntelligenceRegistry(@Qualifier(value="bootstrapGameAccountSessionService") GameAccountSessionService sessionService, BehemothAccountStateHub accountStateHub) {
        super(sessionService, accountStateHub);
        this.sessionCreatedRegistration = sessionService.addSessionCreatedListener(session -> {
            DailyIntelligenceState state = new DailyIntelligenceState();
            this.boundSessions.put(session.accountRoleId(), (GameAccountSessionService.AccountSession)session);
            this.intelligenceStates.put(session.accountRoleId(), state);
            session.observeInbound(state::accept);
        });
        this.sessionStoppedRegistration = sessionService.addSessionStoppedListener(session -> {
            long accountRoleId = session.accountRoleId();
            if (this.boundSessions.remove(accountRoleId, session)) {
                this.intelligenceStates.remove(accountRoleId);
            }
        });
    }

    @PreDestroy
    public void closeRegistrations() {
        BootstrapIntelligenceRegistry.closeQuietly(this.sessionCreatedRegistration);
        BootstrapIntelligenceRegistry.closeQuietly(this.sessionStoppedRegistration);
    }

    public DailyIntelligenceState intelligenceState(long accountRoleId) {
        return this.intelligenceStates.computeIfAbsent(accountRoleId, ignored -> new DailyIntelligenceState());
    }

    private static void closeQuietly(AutoCloseable registration) {
        if (registration == null) {
            return;
        }
        try {
            registration.close();
        }
        catch (Exception exception) {
            // empty catch block
        }
    }
}

