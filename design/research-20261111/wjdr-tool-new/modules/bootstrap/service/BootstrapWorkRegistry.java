/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.springframework.beans.factory.annotation.Qualifier
 *  org.springframework.stereotype.Service
 */
package com.wjdr.tool.modules.bootstrap.service;

import com.wjdr.tool.core.account.GameAccountSessionService;
import com.wjdr.tool.core.protocol.GameMessage;
import com.wjdr.tool.modules.bootstrap.service.AbstractBootstrapStateRegistry;
import com.wjdr.tool.modules.bootstrap.service.BootstrapWorkState;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
class BootstrapWorkRegistry
extends AbstractBootstrapStateRegistry<BootstrapWorkState> {
    public BootstrapWorkRegistry(@Qualifier(value="bootstrapGameAccountSessionService") GameAccountSessionService sessionService) {
        super(sessionService, BootstrapWorkState::new);
    }

    @Override
    protected Consumer<GameMessage> observer(BootstrapWorkState state) {
        return state::accept;
    }

    @Override
    BootstrapWorkState newState() {
        return new BootstrapWorkState();
    }

    public BootstrapWorkState workState(long accountRoleId) {
        return (BootstrapWorkState)this.stateOf(accountRoleId);
    }
}

