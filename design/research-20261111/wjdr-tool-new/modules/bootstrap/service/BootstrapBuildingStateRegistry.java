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
import com.wjdr.tool.modules.bootstrap.service.BootstrapBuildingState;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
class BootstrapBuildingStateRegistry
extends AbstractBehemothStateRegistry {
    private final Map<Long, BootstrapBuildingState> buildingStates = new ConcurrentHashMap<Long, BootstrapBuildingState>();
    private final Map<Long, GameAccountSessionService.AccountSession> boundSessions = new ConcurrentHashMap<Long, GameAccountSessionService.AccountSession>();
    private final AutoCloseable sessionCreatedRegistration;
    private final AutoCloseable sessionStoppedRegistration;

    public BootstrapBuildingStateRegistry(@Qualifier(value="bootstrapGameAccountSessionService") GameAccountSessionService sessionService, BehemothAccountStateHub accountStateHub) {
        super(sessionService, accountStateHub);
        this.sessionCreatedRegistration = sessionService.addSessionCreatedListener(session -> {
            BootstrapBuildingState state = new BootstrapBuildingState();
            this.boundSessions.put(session.accountRoleId(), (GameAccountSessionService.AccountSession)session);
            this.buildingStates.put(session.accountRoleId(), state);
            session.observeInbound(state::accept);
        });
        this.sessionStoppedRegistration = sessionService.addSessionStoppedListener(session -> {
            long accountRoleId = session.accountRoleId();
            if (this.boundSessions.remove(accountRoleId, session)) {
                this.buildingStates.remove(accountRoleId);
            }
        });
    }

    @PreDestroy
    public void closeRegistrations() {
        BootstrapBuildingStateRegistry.closeQuietly(this.sessionCreatedRegistration);
        BootstrapBuildingStateRegistry.closeQuietly(this.sessionStoppedRegistration);
    }

    public BootstrapBuildingState buildingState(long accountRoleId) {
        return this.buildingStates.computeIfAbsent(accountRoleId, ignored -> new BootstrapBuildingState());
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

