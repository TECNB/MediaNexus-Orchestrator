package com.medianexus.orchestrator.dto.taskcenter.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

public record QuarkTaskCenterSubscriptionRequest(
        @NotNull(message = "自动更新开关不能为空")
        @JsonProperty("enabled") Boolean enabled
) {
}
