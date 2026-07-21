package com.medianexus.orchestrator.dto.magnet.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;

@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(description = "剧集 magnet 导入任务创建请求")
public record SeriesMagnetIngestRequest(
        @Schema(description = "单条 magnet 链接，必须以 magnet:? 开头")
        String magnet,
        @Schema(description = "剧集中文或展示标题；original_title 为空时使用", requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true)
        String title,
        @Schema(description = "剧集原始标题；优先用于生成目录名", requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true)
        @JsonProperty("original_title")
        String originalTitle,
        @Schema(description = "季编号，从 1 开始")
        @JsonProperty("season_number")
        Integer seasonNumber,
        @Schema(description = "TMDB 剧集 id；来自目录搜索结果", requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true)
        @JsonProperty("tmdb_id")
        @Positive(message = "TMDB id 必须大于 0")
        Integer tmdbId
) {

    public SeriesMagnetIngestRequest(String magnet, String title, String originalTitle, Integer seasonNumber) {
        this(magnet, title, originalTitle, seasonNumber, null);
    }

    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object ignored) {
        throw new IllegalArgumentException("未知字段: " + fieldName);
    }
}
