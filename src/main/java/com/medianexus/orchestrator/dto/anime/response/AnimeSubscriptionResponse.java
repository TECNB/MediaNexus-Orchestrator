package com.medianexus.orchestrator.dto.anime.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Ani-RSS 订阅创建响应")
public record AnimeSubscriptionResponse(
        @Schema(description = "订阅处理状态：added 表示已创建，exists 表示同名同季订阅已存在")
        String status,
        @Schema(description = "本次请求是否新增订阅")
        boolean added,
        @Schema(description = "是否命中同名同季重复订阅")
        boolean duplicate,
        @Schema(description = "面向前端展示的处理消息")
        String message,
        @Schema(description = "创建前的订阅预览结果")
        AnimeSubscriptionPreviewResponse preview
) {
}
