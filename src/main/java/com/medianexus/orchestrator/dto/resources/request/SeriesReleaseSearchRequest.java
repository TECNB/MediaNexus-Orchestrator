package com.medianexus.orchestrator.dto.resources.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(description = "资源页剧集发布资源列表请求")
public record SeriesReleaseSearchRequest(
        @Schema(description = "剧集 TVDB 稳定身份；不用于 Prowlarr ID 搜索", nullable = true)
        @JsonProperty("tvdb_id")
        @Min(1)
        Integer tvdbId,
        @Schema(description = "剧集 TMDB 稳定身份；不用于 Prowlarr ID 搜索", nullable = true)
        @JsonProperty("tmdb_id")
        @Min(1)
        Integer tmdbId,
        @Schema(description = "剧集 IMDB 稳定身份；不用于 Prowlarr ID 搜索", nullable = true)
        @JsonProperty("imdb_id")
        String imdbId,
        @Schema(description = "剧集中文或展示标题")
        @NotBlank(message = "剧集标题不能为空")
        String title,
        @Schema(description = "剧集原始标题；没有时可为 null", nullable = true)
        @JsonProperty("original_title")
        String originalTitle,
        @Schema(description = "目标季编号")
        @JsonProperty("season_number")
        @NotNull(message = "季编号不能为空")
        @Min(value = 1, message = "季编号必须大于 0")
        Integer seasonNumber,
        @Schema(description = "当前选择的分辨率标签；用于列表推荐排序，不会过滤其他分辨率", nullable = true)
        String quality
) {

    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object ignored) {
        throw new IllegalArgumentException("未知字段: " + fieldName);
    }
}
