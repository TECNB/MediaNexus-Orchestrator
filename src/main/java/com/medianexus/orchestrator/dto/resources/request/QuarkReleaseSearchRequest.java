package com.medianexus.orchestrator.dto.resources.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

@JsonIgnoreProperties(ignoreUnknown = false)
public record QuarkReleaseSearchRequest(
        @JsonProperty("media_type")
        @NotBlank(message = "媒体类型不能为空")
        String mediaType,
        @NotBlank(message = "标题不能为空")
        String title,
        @JsonProperty("original_title")
        String originalTitle,
        @Min(value = 1888, message = "年份无效")
        Integer year,
        @JsonProperty("season_number")
        @Min(value = 1, message = "季编号必须大于 0")
        Integer seasonNumber,
        @JsonProperty("tmdb_id")
        @Min(value = 1, message = "TMDB ID 必须大于 0")
        Integer tmdbId,
        Boolean refresh
) {

    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object ignored) {
        throw new IllegalArgumentException("未知字段: " + fieldName);
    }
}
