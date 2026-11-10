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
import com.wjdr.tool.modules.bootstrap.service.BootstrapMilestoneState;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
class BootstrapMilestoneRegistry
extends AbstractBootstrapStateRegistry<BootstrapMilestoneState> {
    public BootstrapMilestoneRegistry(@Qualifier(value="bootstrapGameAccountSessionService") GameAccountSessionService sessionService) {
        super(sessionService, BootstrapMilestoneState::new);
    }

    @Override
    protected Consumer<GameMessage> observer(BootstrapMilestoneState state) {
        return state::accept;
    }

    @Override
    BootstrapMilestoneState newState() {
        return new BootstrapMilestoneState();
    }

    public BootstrapMilestoneState milestoneState(long accountRoleId) {
        return (BootstrapMilestoneState)this.stateOf(accountRoleId);
    }
}

