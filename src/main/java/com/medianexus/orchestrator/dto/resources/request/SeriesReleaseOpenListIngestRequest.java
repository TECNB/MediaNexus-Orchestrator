package com.medianexus.orchestrator.dto.resources.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.List;

public record SeriesReleaseOpenListIngestRequest(
        String title,
        @JsonProperty("original_title")
        String originalTitle,
        @JsonProperty("season_number")
        Integer seasonNumber,
        @JsonProperty("task_product_type")
        @Pattern(regexp = "SERIES|ANIME", message = "任务产品类别只能是 SERIES 或 ANIME")
        String taskProductType,
        @JsonProperty("release_title")
        String releaseTitle,
        String indexer,
        Long size,
        @JsonProperty("indexer_id")
        Integer indexerId,
        @JsonProperty("download_ref")
        String downloadRef,
        @JsonProperty("resolution_tags")
        List<String> resolutionTags,
        @JsonProperty("dynamic_range_tags")
        List<String> dynamicRangeTags,
        @JsonProperty("tmdb_id")
        @Positive(message = "TMDB id 必须大于 0")
        Integer tmdbId
) {

    public SeriesReleaseOpenListIngestRequest(
            String title,
            String originalTitle,
            Integer seasonNumber,
            String taskProductType,
            String releaseTitle,
            String indexer,
            Long size,
            Integer indexerId,
            String downloadRef,
            List<String> resolutionTags,
            List<String> dynamicRangeTags
    ) {
        this(
                title,
                originalTitle,
                seasonNumber,
                taskProductType,
                releaseTitle,
                indexer,
                size,
                indexerId,
                downloadRef,
                resolutionTags,
                dynamicRangeTags,
                null
        );
    }
}
