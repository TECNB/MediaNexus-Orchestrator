package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.emby.response.EmbyCredentialResponse;
import com.medianexus.orchestrator.model.User;
import com.medianexus.orchestrator.service.AuthService;
import com.medianexus.orchestrator.service.EmbyAccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/emby/account")
@Tag(name = "Emby 账号", description = "登录用户查看自己的 Emby 托管凭据")
public class EmbyAccountController {

    private final AuthService authService;
    private final EmbyAccountService embyAccountService;

    public EmbyAccountController(AuthService authService, EmbyAccountService embyAccountService) {
        this.authService = authService;
        this.embyAccountService = embyAccountService;
    }

    @GetMapping("/credentials")
    @Operation(summary = "查看自己的 Emby 凭据", description = "历史未托管用户返回 managed=false。")
    public ResponseEntity<ApiResponse<EmbyCredentialResponse>> getCredentials() {
        User user = authService.requireCurrentUser();
        EmbyCredentialResponse credential = embyAccountService.credentialsFor(user);
        EmbyCredentialResponse selfCredential = credential.managed()
                ? new EmbyCredentialResponse(true, credential.username(), credential.password(), null)
                : credential;
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(selfCredential));
    }
}
