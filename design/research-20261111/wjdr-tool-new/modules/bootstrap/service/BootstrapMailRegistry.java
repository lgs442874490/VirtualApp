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
import com.wjdr.tool.modules.bootstrap.service.BootstrapMailState;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
class BootstrapMailRegistry
extends AbstractBootstrapStateRegistry<BootstrapMailState> {
    public BootstrapMailRegistry(@Qualifier(value="bootstrapGameAccountSessionService") GameAccountSessionService sessionService) {
        super(sessionService, BootstrapMailState::new);
    }

    @Override
    protected Consumer<GameMessage> observer(BootstrapMailState state) {
        return state::accept;
    }

    @Override
    BootstrapMailState newState() {
        return new BootstrapMailState();
    }

    public BootstrapMailState mailState(long accountRoleId) {
        return (BootstrapMailState)this.stateOf(accountRoleId);
    }
}

