package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.integration.wecom.WeComClient.Directory;
import com.medianexus.orchestrator.integration.wecom.WeComClient.Scope;
import com.medianexus.orchestrator.service.AuthService;
import com.medianexus.orchestrator.service.WeComBindings;
import com.medianexus.orchestrator.service.WeComBindings.Accounts;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/settings/wecom")
public class AdminWeComBindingsController {
    private final AuthService auth;
    private final WeComBindings bindings;

    public AdminWeComBindingsController(AuthService auth, WeComBindings bindings) {
        this.auth = auth;
        this.bindings = bindings;
    }

    @GetMapping("/bindings")
    public ApiResponse<Accounts> accounts() {
        auth.requireAdminUser();
        return ApiResponse.success(bindings.accounts());
    }

    @GetMapping("/members")
    public ApiResponse<Directory> members() {
        auth.requireAdminUser();
        return ApiResponse.success(bindings.visibleMembers());
    }

    @PutMapping("/bindings/{userId}")
    public ApiResponse<Boolean> bind(@PathVariable Long userId, @RequestBody BindMember request) {
        auth.requireAdminUser();
        bindings.bind(userId, request.scope(), request.memberId());
        return ApiResponse.success(true);
    }

    @DeleteMapping("/bindings/{userId}")
    public ApiResponse<Boolean> unbind(@PathVariable Long userId, @RequestParam String corpId, @RequestParam Long agentId) {
        auth.requireAdminUser();
        bindings.unbind(userId, new Scope(corpId, agentId));
        return ApiResponse.success(true);
    }

    public record BindMember(Scope scope, String memberId) { }
}
