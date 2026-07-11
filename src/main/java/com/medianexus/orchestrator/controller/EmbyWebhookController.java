package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.service.AdultOtherLibraryWebhookService;
import com.medianexus.orchestrator.service.EmbyPlaybackWebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/emby/webhooks")
@Tag(name = "Emby Webhook", description = "接收 Emby Webhooks 播放与媒体库事件")
public class EmbyWebhookController {

    private final EmbyPlaybackWebhookService playbackWebhookService;
    private final AdultOtherLibraryWebhookService libraryWebhookService;

    public EmbyWebhookController(
            EmbyPlaybackWebhookService playbackWebhookService,
            AdultOtherLibraryWebhookService libraryWebhookService
    ) {
        this.playbackWebhookService = playbackWebhookService;
        this.libraryWebhookService = libraryWebhookService;
    }

    @PostMapping(value = "/playback", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "接收 Emby 播放事件", description = "接收 playback.start 和 playback.stop，并在 stop 后结算有效观看会话。")
    public ApiResponse<Void> receivePlaybackEvent(
            @Parameter(description = "MediaNexus 为 Emby Webhook 配置的 query secret")
            @RequestParam(name = "secret", required = false) String secret,
            @Parameter(description = "Emby Webhooks 原生 application/json 负载")
            @RequestBody(required = false) String body
    ) {
        playbackWebhookService.receivePlaybackEvent(secret, body);
        return ApiResponse.success();
    }

    @PostMapping(value = "/library", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "接收 Emby 媒体库事件", description = "合并 library.new 和 library.deleted 事件并在后台处理 Adult - Other 自动化。")
    public ResponseEntity<Void> receiveLibraryEvent(
            @Parameter(description = "MediaNexus 为 Emby Webhook 配置的 query secret")
            @RequestParam(name = "secret", required = false) String secret,
            @Parameter(description = "Emby Webhooks 原生 application/json 负载")
            @RequestBody(required = false) String body
    ) {
        libraryWebhookService.receiveLibraryEvent(secret, body);
        return ResponseEntity.noContent().build();
    }
}
