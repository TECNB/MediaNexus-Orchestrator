package com.medianexus.orchestrator.dto.admin.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(description = "管理员更新全局默认额度请求")
public record AdminDefaultQuotaUpdateRequest(
        @Schema(description = "全局默认每日创建额度，必须为 0-9 的整数")
        @NotNull(message = "额度不能为空")
        @Min(value = 0, message = "额度必须为 0-9 的整数")
        @Max(value = 9, message = "额度必须为 0-9 的整数")
        @JsonProperty("daily_content_create_limit")
        @JsonAlias("dailyContentCreateLimit")
        Integer dailyContentCreateLimit
) {
}
