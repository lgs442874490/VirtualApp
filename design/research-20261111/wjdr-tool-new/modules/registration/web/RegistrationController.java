/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.springframework.web.bind.annotation.GetMapping
 *  org.springframework.web.bind.annotation.PathVariable
 *  org.springframework.web.bind.annotation.PostMapping
 *  org.springframework.web.bind.annotation.RequestBody
 *  org.springframework.web.bind.annotation.RequestMapping
 *  org.springframework.web.bind.annotation.RequestParam
 *  org.springframework.web.bind.annotation.RestController
 */
package com.wjdr.tool.modules.registration.web;

import com.wjdr.tool.core.auth.SystemUserService;
import com.wjdr.tool.core.common.util.ConvertUtils;
import com.wjdr.tool.modules.registration.service.ExpressRegistrationService;
import com.wjdr.tool.modules.registration.service.PhoneRegistrationService;
import com.wjdr.tool.modules.registration.service.RegistrationRoleCreationService;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value={"/api/registration"})
public class RegistrationController {
    private final ExpressRegistrationService registrationService;
    private final RegistrationRoleCreationService roleCreationService;
    private final PhoneRegistrationService phoneRegistrationService;
    private final SystemUserService userService;

    public RegistrationController(ExpressRegistrationService registrationService, RegistrationRoleCreationService roleCreationService, PhoneRegistrationService phoneRegistrationService, SystemUserService userService) {
        this.registrationService = registrationService;
        this.roleCreationService = roleCreationService;
        this.phoneRegistrationService = phoneRegistrationService;
        this.userService = userService;
    }

    @PostMapping(value={"/express"})
    public Map<String, Object> createExpressAccount() {
        return this.registrationService.createExpressAccount();
    }

    @PostMapping(value={"/express/batch"})
    public Map<String, Object> createExpressAccountBatch(@RequestBody(required=false) Map<String, Object> payload) {
        Map<Object, Object> source = payload == null ? Collections.emptyMap() : payload;
        return this.registrationService.createExpressAccounts(ConvertUtils.toInt(source.get("count"), 0));
    }

    @PostMapping(value={"/real-name"})
    public Map<String, Object> verifyRealName(@RequestBody(required=false) Map<String, Object> payload) {
        return this.registrationService.verifyRealName(payload == null ? Collections.emptyMap() : payload);
    }

    @PostMapping(value={"/create-role"})
    public Map<String, Object> createRoleInServer(@RequestBody(required=false) Map<String, Object> payload) {
        return this.roleCreationService.createRole(payload == null ? Collections.emptyMap() : payload);
    }

    @PostMapping(value={"/phone/provider/test"})
    public Map<String, Object> testProvider(@RequestBody(required=false) Map<String, Object> payload) {
        return this.phoneRegistrationService.testProvider(payload == null ? Collections.emptyMap() : payload);
    }

    @PostMapping(value={"/phone/batches"})
    public Map<String, Object> startPhoneBatch(@RequestBody(required=false) Map<String, Object> payload) {
        long ownerUserId = this.userService.requireCurrent().id();
        return this.phoneRegistrationService.start(ownerUserId, payload == null ? Collections.emptyMap() : payload);
    }

    @GetMapping(value={"/phone/batches"})
    public Map<String, Object> listPhoneBatches(@RequestParam(defaultValue="20") int limit) {
        long ownerUserId = this.userService.requireCurrent().id();
        return Map.of("batches", this.phoneRegistrationService.list(ownerUserId, limit));
    }

    @GetMapping(value={"/phone/batches/active"})
    public Map<String, Object> activePhoneBatch() {
        long ownerUserId = this.userService.requireCurrent().id();
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("batch", this.phoneRegistrationService.active(ownerUserId).orElse(null));
        return result;
    }

    @GetMapping(value={"/phone/batches/{batchId}"})
    public Map<String, Object> phoneBatchDetail(@PathVariable long batchId) {
        long ownerUserId = this.userService.requireCurrent().id();
        return this.phoneRegistrationService.detail(ownerUserId, batchId);
    }
}

