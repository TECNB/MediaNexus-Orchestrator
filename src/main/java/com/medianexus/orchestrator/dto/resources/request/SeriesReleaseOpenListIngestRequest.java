package com.medianexus.orchestrator.dto.resources.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record SeriesReleaseOpenListIngestRequest(
        String title,
        @JsonProperty("original_title")
        String originalTitle,
        @JsonProperty("season_number")
        Integer seasonNumber,
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
        List<String> dynamicRangeTags
) {
}
