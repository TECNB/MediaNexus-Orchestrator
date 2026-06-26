package com.medianexus.orchestrator.dto.resources.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(description = "资源页电影发布资源推荐请求")
public record MovieReleaseRecommendationRequest(
        @Schema(description = "电影 TMDB ID；用于优先执行 ID 搜索", nullable = true)
        @JsonProperty("tmdb_id")
        @Min(1)
        Integer tmdbId,
        @Schema(description = "电影 IMDB ID；用于优先执行 ID 搜索", nullable = true)
        @JsonProperty("imdb_id")
        String imdbId,
        @Schema(description = "电影中文或展示标题")
        @NotBlank(message = "电影标题不能为空")
        String title,
        @Schema(description = "电影原始标题；没有时可为 null", nullable = true)
        @JsonProperty("original_title")
        String originalTitle,
        @Schema(description = "电影年份")
        @NotNull(message = "电影年份不能为空")
        @Min(value = 1888, message = "电影年份无效")
        Integer year,
        @Schema(description = "期望分辨率标签，例如 2160p、1080p 或 720p")
        @NotBlank(message = "请选择分辨率")
        String quality
) {

    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object ignored) {
        throw new IllegalArgumentException("未知字段: " + fieldName);
    }
}
