package com.medianexus.orchestrator.dto.admin.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "管理员全局默认额度响应")
public record AdminDefaultQuotaResponse(
        @Schema(description = "全局默认每日创建额度")
        @JsonProperty("daily_content_create_limit")
        int dailyContentCreateLimit
) {
}
