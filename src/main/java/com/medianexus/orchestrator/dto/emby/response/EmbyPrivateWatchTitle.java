package com.medianexus.orchestrator.dto.emby.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "管理员主动请求的私密作品片名；未知分类不返回")
public record EmbyPrivateWatchTitle(
        @JsonProperty("item_id") String itemId,
        @JsonProperty("media_id") String mediaId,
        @Schema(description = "Adult 实际视频片名；原记录缺失时为 null", nullable = true) String title
) { }
