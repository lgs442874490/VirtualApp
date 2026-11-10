/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.springframework.beans.factory.annotation.Qualifier
 *  org.springframework.security.access.prepost.PreAuthorize
 *  org.springframework.web.bind.annotation.PathVariable
 *  org.springframework.web.bind.annotation.PostMapping
 *  org.springframework.web.bind.annotation.RequestBody
 *  org.springframework.web.bind.annotation.RequestMapping
 *  org.springframework.web.bind.annotation.RestController
 */
package com.wjdr.tool.modules.bootstrap.web;

import com.wjdr.tool.core.account.GameAccountControllerSupport;
import com.wjdr.tool.core.account.GameAccountService;
import com.wjdr.tool.core.auth.SystemUserService;
import com.wjdr.tool.core.protocol.ProtocolValueSupport;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value={"/api/bootstrap/accounts"})
public class BootstrapAccountController
extends GameAccountControllerSupport {
    private final GameAccountService accountService;

    public BootstrapAccountController(@Qualifier(value="bootstrapGameAccountService") GameAccountService accountService, SystemUserService userService) {
        super(accountService, userService, "bootstrap");
        this.accountService = accountService;
    }

    @PostMapping(value={"/roles/{accountRoleId}/create-role"})
    @PreAuthorize(value="@gameAccountAuthorization.canAccessRole(authentication, #accountRoleId, 'bootstrap')")
    public Map<String, Object> createRole(@PathVariable long accountRoleId, @RequestBody Map<String, Object> request) {
        long serverId = ProtocolValueSupport.number(request.get("serverId"));
        Map<String, Object> created = this.accountService.createRoleInServer(accountRoleId, serverId);
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>(created);
        result.put("message", "\u5df2\u5728\u533a\u670d " + serverId + " \u65b0\u5efa\u89d2\u8272\u5e76\u767b\u5f55");
        result.put("account", created.get("accountStatus"));
        return result;
    }

    @Override
    protected void beforeAccountLogout(long accountRoleId) {
    }

    @Override
    protected void beforeAllAccountsLogout() {
    }

    @Override
    protected Optional<Runnable> removeModuleAccount(long accountRoleId) {
        return Optional.empty();
    }
}

