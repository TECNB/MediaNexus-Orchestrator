package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.emby.response.EmbyPlaybackSessionsResponse;
import com.medianexus.orchestrator.service.AuthService;
import com.medianexus.orchestrator.service.EmbyPlaybackSnapshotCollector;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/emby")
@Tag(name = "Emby 实时观看")
public class AdminEmbyPlaybackController {
    private final EmbyPlaybackSnapshotCollector snapshotCollector;
    private final AuthService authService;

    public AdminEmbyPlaybackController(EmbyPlaybackSnapshotCollector snapshotCollector, AuthService authService) {
        this.snapshotCollector = snapshotCollector;
        this.authService = authService;
    }

    @GetMapping("/playback-sessions")
    @Operation(summary = "读取当前视频播放会话", description = "沿用管理员权限，只读后台共享快照，不触发 Emby 查询。采集失败保留旧快照并标记 stale；首份成功快照之前沿用 502。")
    public ApiResponse<EmbyPlaybackSessionsResponse> getPlaybackSessions() {
        authService.requireAdminUser();
        EmbyPlaybackSessionsResponse snapshot = snapshotCollector.currentSnapshot();
        if (snapshot.collectedAt() == null) {
            throw new BusinessException(ErrorCode.BAD_GATEWAY, "实时观看刷新失败，请稍后重试", HttpStatus.BAD_GATEWAY);
        }
        return ApiResponse.success(snapshot);
    }
}
