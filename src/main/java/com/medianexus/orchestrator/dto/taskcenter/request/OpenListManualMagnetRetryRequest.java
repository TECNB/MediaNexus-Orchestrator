package com.medianexus.orchestrator.dto.taskcenter.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "任务中心手动 magnet 替换重试请求")
public record OpenListManualMagnetRetryRequest(
        @Schema(description = "新的 magnet 链接，必须以 magnet:? 开头")
        @NotBlank(message = "magnet 链接不能为空")
        String magnet,
        @Schema(description = "显式接受未核验风险；默认 false，已知冲突及整理不安全不可绕过")
        @JsonProperty("accept_unverified_risk")
        boolean acceptUnverifiedRisk
) {

    public OpenListManualMagnetRetryRequest(String magnet) {
        this(magnet, false);
    }

    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object ignored) {
        throw new IllegalArgumentException("未知字段: " + fieldName);
    }
}
