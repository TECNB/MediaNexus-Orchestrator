package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.emby.response.EmbyPlaybackSessionsResponse;
import com.medianexus.orchestrator.integration.emby.EmbyClient;
import com.medianexus.orchestrator.integration.emby.EmbyClientException;
import com.medianexus.orchestrator.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/emby")
@Tag(name = "Emby 实时观看")
public class AdminEmbyPlaybackController {
    private final EmbyClient embyClient;
    private final AuthService authService;

    public AdminEmbyPlaybackController(EmbyClient embyClient, AuthService authService) {
        this.embyClient = embyClient;
        this.authService = authService;
    }

    @GetMapping("/playback-sessions")
    @Operation(summary = "读取当前视频播放会话", description = "沿用管理员权限。只读 Emby 会话和已有媒体详情；缺项返回 null，上游会话读取失败返回 502。")
    public ApiResponse<EmbyPlaybackSessionsResponse> getPlaybackSessions() {
        authService.requireAdminUser();
        Instant collectedAt = Instant.now();
        try {
            return ApiResponse.success(new EmbyPlaybackSessionsResponse(collectedAt, embyClient.listPlaybackSessions()));
        } catch (EmbyClientException exception) {
            throw new BusinessException(ErrorCode.BAD_GATEWAY, "实时观看刷新失败，请稍后重试", HttpStatus.BAD_GATEWAY);
        }
    }
}
