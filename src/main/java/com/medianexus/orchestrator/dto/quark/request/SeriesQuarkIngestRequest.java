package com.medianexus.orchestrator.dto.quark.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(description = "电视剧或综艺 Quark 分享链接入库请求")
public record SeriesQuarkIngestRequest(
        @NotBlank(message = "Quark 分享链接不能为空")
        @JsonProperty("share_url")
        @Schema(description = "pan.quark.cn 分享链接")
        String shareUrl,
        @NotBlank(message = "标题不能为空")
        @Schema(description = "搜索结果中的电视剧或综艺标题")
        String title,
        @JsonProperty("original_title")
        @Schema(description = "原始标题；可不传", nullable = true)
        String originalTitle,
        @NotNull(message = "目标季数不能为空")
        @JsonProperty("season_number")
        @Schema(description = "目标季数，从 1 开始")
        Integer seasonNumber,
        @JsonProperty("tmdb_id")
        @Schema(description = "TMDB 剧集 ID；仅在日期型多版本映射集号时使用", nullable = true)
        Integer tmdbId
) {
}
