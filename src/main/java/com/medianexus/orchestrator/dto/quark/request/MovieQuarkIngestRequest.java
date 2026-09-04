package com.medianexus.orchestrator.dto.quark.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(description = "电影 Quark 分享链接入库请求")
public record MovieQuarkIngestRequest(
        @NotBlank(message = "Quark 分享链接不能为空")
        @JsonProperty("share_url")
        @Schema(description = "pan.quark.cn 分享链接")
        String shareUrl,
        @NotBlank(message = "电影标题不能为空")
        @Schema(description = "搜索结果中的电影标题")
        String title,
        @JsonProperty("original_title")
        @Schema(description = "电影原始标题；可不传", nullable = true)
        String originalTitle,
        @NotNull(message = "电影年份不能为空")
        @Schema(description = "电影上映年份")
        Integer year,
        @JsonProperty("source_type")
        @Schema(description = "任务来源：MANUAL_QUARK 或 PANSOU_SEARCH", nullable = true)
        String sourceType
) {
    public MovieQuarkIngestRequest(
            String shareUrl,
            String title,
            String originalTitle,
            Integer year
    ) {
        this(shareUrl, title, originalTitle, year, "MANUAL_QUARK");
    }

    public MovieQuarkIngestRequest {
        sourceType = sourceType == null || sourceType.isBlank() ? "MANUAL_QUARK" : sourceType.trim().toUpperCase();
    }
}
