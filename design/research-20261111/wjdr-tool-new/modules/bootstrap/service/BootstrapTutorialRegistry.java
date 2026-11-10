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
import com.wjdr.tool.modules.bootstrap.service.BootstrapTutorialState;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
class BootstrapTutorialRegistry
extends AbstractBootstrapStateRegistry<BootstrapTutorialState> {
    public BootstrapTutorialRegistry(@Qualifier(value="bootstrapGameAccountSessionService") GameAccountSessionService sessionService) {
        super(sessionService, BootstrapTutorialState::new);
    }

    @Override
    protected Consumer<GameMessage> observer(BootstrapTutorialState state) {
        return state::accept;
    }

    @Override
    BootstrapTutorialState newState() {
        return new BootstrapTutorialState();
    }

    public BootstrapTutorialState tutorialState(long accountRoleId) {
        return (BootstrapTutorialState)this.stateOf(accountRoleId);
    }
}

