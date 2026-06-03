package com.medianexus.orchestrator.dto.anime.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Ani-RSS 订阅预览和创建请求")
public record AnimeSubscriptionPreviewRequest(
        @Schema(description = "字幕组 RSS 地址，来自 Mikan 字幕组候选")
        String rss,
        @Schema(description = "Bangumi 条目地址；同时兼容 bgm_url 和 bgmUrl 入参")
        @JsonProperty("bgm_url")
        @JsonAlias("bgmUrl")
        String bgmUrl,
        @Schema(description = "字幕组显示名称，必须与候选字幕组一致")
        String subgroup
) {
}
