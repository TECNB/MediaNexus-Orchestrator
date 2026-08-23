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
        Integer year
) {
}
