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
import com.wjdr.tool.modules.bootstrap.service.BootstrapSurvivorState;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
class BootstrapSurvivorRegistry {
    private final Map<Long, BootstrapSurvivorState> states = new ConcurrentHashMap<Long, BootstrapSurvivorState>();
    private final Map<Long, GameAccountSessionService.AccountSession> boundSessions = new ConcurrentHashMap<Long, GameAccountSessionService.AccountSession>();
    private final AutoCloseable sessionCreatedRegistration;
    private final AutoCloseable sessionStoppedRegistration;

    public BootstrapSurvivorRegistry(@Qualifier(value="bootstrapGameAccountSessionService") GameAccountSessionService sessionService) {
        this.sessionCreatedRegistration = sessionService.addSessionCreatedListener(session -> {
            BootstrapSurvivorState state = new BootstrapSurvivorState();
            this.boundSessions.put(session.accountRoleId(), (GameAccountSessionService.AccountSession)session);
            this.states.put(session.accountRoleId(), state);
            session.observeInbound(state::accept);
        });
        this.sessionStoppedRegistration = sessionService.addSessionStoppedListener(session -> {
            long accountRoleId = session.accountRoleId();
            if (this.boundSessions.remove(accountRoleId, session)) {
                this.states.remove(accountRoleId);
            }
        });
    }

    @PreDestroy
    public void closeRegistrations() {
        BootstrapSurvivorRegistry.closeQuietly(this.sessionCreatedRegistration);
        BootstrapSurvivorRegistry.closeQuietly(this.sessionStoppedRegistration);
    }

    public BootstrapSurvivorState survivorState(long accountRoleId) {
        return this.states.computeIfAbsent(accountRoleId, ignored -> new BootstrapSurvivorState());
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

