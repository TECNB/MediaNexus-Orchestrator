package com.medianexus.orchestrator.dto.admin.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "用户今日共享额度动作拆分")
public record AdminUserUsageBreakdownResponse(
        @Schema(description = "今日磁链入库创建次数")
        @JsonProperty("magnet_ingest_create")
        int magnetIngestCreate,
        @Schema(description = "今日动漫订阅创建次数")
        @JsonProperty("anime_subscribe_create")
        int animeSubscribeCreate
) {
}
