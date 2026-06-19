package com.medianexus.orchestrator.dto.resources.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ProwlarrReleaseItemResponse(
        String title,
        Long size,
        Integer seeders,
        Integer leechers,
        Integer grabs,
        String indexer,
        @JsonProperty("publish_date")
        String publishDate,
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
