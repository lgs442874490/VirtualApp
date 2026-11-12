/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  jakarta.annotation.PreDestroy
 */
package com.wjdr.tool.modules.bootstrap.service;

import com.wjdr.tool.core.account.GameAccountSessionService;
import com.wjdr.tool.core.protocol.GameMessage;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

abstract class AbstractBootstrapStateRegistry<T> {
    private final Map<Long, T> states = new ConcurrentHashMap<Long, T>();
    private final Map<Long, GameAccountSessionService.AccountSession> boundSessions = new ConcurrentHashMap<Long, GameAccountSessionService.AccountSession>();
    private final AutoCloseable sessionCreatedRegistration;
    private final AutoCloseable sessionStoppedRegistration;

    AbstractBootstrapStateRegistry(GameAccountSessionService sessionService, Supplier<T> factory) {
        this.sessionCreatedRegistration = sessionService.addSessionCreatedListener(session -> {
            Object state = factory.get();
            this.boundSessions.put(session.accountRoleId(), (GameAccountSessionService.AccountSession)session);
            this.states.put(session.accountRoleId(), state);
            session.observeInbound(this.observer(state));
        });
        this.sessionStoppedRegistration = sessionService.addSessionStoppedListener(session -> {
            long accountRoleId = session.accountRoleId();
            if (this.boundSessions.remove(accountRoleId, session)) {
                this.states.remove(accountRoleId);
            }
        });
    }

    protected abstract Consumer<GameMessage> observer(T var1);

    @PreDestroy
    public void closeRegistrations() {
        AbstractBootstrapStateRegistry.closeQuietly(this.sessionCreatedRegistration);
        AbstractBootstrapStateRegistry.closeQuietly(this.sessionStoppedRegistration);
    }

    final T stateOf(long accountRoleId) {
        return (T)this.states.computeIfAbsent(accountRoleId, ignored -> this.newState());
    }

    abstract T newState();

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

