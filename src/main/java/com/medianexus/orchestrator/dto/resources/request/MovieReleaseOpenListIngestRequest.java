package com.medianexus.orchestrator.dto.resources.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Positive;
import java.util.List;

public record MovieReleaseOpenListIngestRequest(
        String title,
        @JsonProperty("original_title")
        String originalTitle,
        Integer year,
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

    public MovieReleaseOpenListIngestRequest(
            String title,
            String originalTitle,
            Integer year,
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
                year,
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
