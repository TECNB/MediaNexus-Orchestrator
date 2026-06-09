package com.medianexus.orchestrator.dto.admin.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "管理员更新全局默认额度请求")
public record AdminDefaultQuotaUpdateRequest(
        @Schema(description = "全局默认每日创建额度，必须为 0-9 的整数")
        @JsonProperty("daily_content_create_limit")
        @JsonAlias("dailyContentCreateLimit")
        Integer dailyContentCreateLimit
) {
}
