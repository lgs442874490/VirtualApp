/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.springframework.security.access.prepost.PreAuthorize
 *  org.springframework.web.bind.annotation.GetMapping
 *  org.springframework.web.bind.annotation.PathVariable
 *  org.springframework.web.bind.annotation.PostMapping
 *  org.springframework.web.bind.annotation.RequestBody
 *  org.springframework.web.bind.annotation.RequestMapping
 *  org.springframework.web.bind.annotation.RequestParam
 *  org.springframework.web.bind.annotation.RestController
 */
package com.wjdr.tool.modules.bootstrap.web;

import com.wjdr.tool.modules.bootstrap.service.BootstrapService;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value={"/api/bootstrap/roles/{accountRoleId}"})
public class BootstrapController {
    private final BootstrapService service;

    public BootstrapController(BootstrapService service) {
        this.service = service;
    }

    @PostMapping(value={"/adventure-probe"})
    @PreAuthorize(value="@gameAccountAuthorization.canAccessRole(authentication, #accountRoleId, 'bootstrap')")
    public Map<String, Object> adventureProbe(@PathVariable long accountRoleId) {
        return this.service.probeAdventureCheckpoint(accountRoleId);
    }

    @GetMapping
    @PreAuthorize(value="@gameAccountAuthorization.canAccessRole(authentication, #accountRoleId, 'bootstrap')")
    public Map<String, Object> status(@PathVariable long accountRoleId) {
        return this.service.status(accountRoleId);
    }

    @GetMapping(value={"/progress"})
    @PreAuthorize(value="@gameAccountAuthorization.canAccessRole(authentication, #accountRoleId, 'bootstrap')")
    public Map<String, Object> progress(@PathVariable long accountRoleId, @RequestParam(name="after", required=false) Long after) {
        return this.service.progressView(accountRoleId, after == null ? 0L : after);
    }

    @PostMapping(value={"/start"})
    @PreAuthorize(value="@gameAccountAuthorization.canAccessRole(authentication, #accountRoleId, 'bootstrap')")
    public Map<String, Object> start(@PathVariable long accountRoleId) {
        return this.service.startRun(accountRoleId);
    }

    @PostMapping(value={"/stop"})
    @PreAuthorize(value="@gameAccountAuthorization.canAccessRole(authentication, #accountRoleId, 'bootstrap')")
    public Map<String, Object> stop(@PathVariable long accountRoleId) {
        return this.service.stopRun(accountRoleId);
    }

    @PostMapping(value={"/settings"})
    @PreAuthorize(value="@gameAccountAuthorization.canAccessRole(authentication, #accountRoleId, 'bootstrap')")
    public Map<String, Object> saveSettings(@PathVariable long accountRoleId, @RequestBody Map<String, Object> request) {
        return this.service.saveSettings(accountRoleId, request);
    }

    @PostMapping(value={"/run-next"})
    @PreAuthorize(value="@gameAccountAuthorization.canAccessRole(authentication, #accountRoleId, 'bootstrap')")
    public Map<String, Object> runNext(@PathVariable long accountRoleId) {
        return this.service.runNextStep(accountRoleId);
    }

    @PostMapping(value={"/run-until"})
    @PreAuthorize(value="@gameAccountAuthorization.canAccessRole(authentication, #accountRoleId, 'bootstrap')")
    public Map<String, Object> runUntil(@PathVariable long accountRoleId, @RequestBody(required=false) Map<String, Object> request) {
        int maxSteps = BootstrapController.parseMaxSteps(request == null ? null : request.get("maxSteps"));
        return this.service.runUntil(accountRoleId, maxSteps);
    }

    private static int parseMaxSteps(Object raw) {
        if (raw == null) {
            return 20;
        }
        try {
            return Integer.parseInt(String.valueOf(raw));
        }
        catch (NumberFormatException ignored) {
            return 20;
        }
    }

    private static long longValue(Object raw) {
        if (raw == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        }
        catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    @GetMapping(value={"/shop-packs"})
    @PreAuthorize(value="@gameAccountAuthorization.canAccessRole(authentication, #accountRoleId, 'bootstrap')")
    public Map<String, Object> shopPacks(@PathVariable long accountRoleId) {
        return Map.of("packs", this.service.shopResourcePacks(accountRoleId), "pendingRewards", this.service.pendingRewardCount(accountRoleId));
    }

    @PostMapping(value={"/level-building"})
    @PreAuthorize(value="@gameAccountAuthorization.canAccessRole(authentication, #accountRoleId, 'bootstrap')")
    public Map<String, Object> levelBuilding(@PathVariable long accountRoleId, @RequestBody Map<String, Object> request) {
        long buildingId = BootstrapController.longValue(request == null ? null : request.get("buildingId"));
        return this.service.levelBuilding(accountRoleId, buildingId);
    }

    @PostMapping(value={"/join-alliance"})
    @PreAuthorize(value="@gameAccountAuthorization.canAccessRole(authentication, #accountRoleId, 'bootstrap')")
    public Map<String, Object> joinAlliance(@PathVariable long accountRoleId) {
        return this.service.joinAlliance(accountRoleId);
    }

    @PostMapping(value={"/resolve-alliance"})
    @PreAuthorize(value="@gameAccountAuthorization.canAccessRole(authentication, #accountRoleId, 'bootstrap')")
    public Map<String, Object> resolveAlliance(@PathVariable long accountRoleId, @RequestBody Map<String, Object> request) {
        Object keyword = request == null ? null : request.get("keyword");
        return this.service.resolveAlliance(accountRoleId, keyword == null ? "" : String.valueOf(keyword));
    }
}

