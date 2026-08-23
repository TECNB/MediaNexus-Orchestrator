package com.medianexus.orchestrator.dto.resources.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record QuarkReleaseItemResponse(
        String id,
        String title,
        @JsonProperty("share_url")
        String shareUrl,
        String source,
        @JsonProperty("published_at")
        String publishedAt,
        String availability,
        @JsonProperty("availability_summary")
        String availabilitySummary,
        String relevance,
        @JsonProperty("match_reasons")
        List<String> matchReasons,
        List<String> conflicts,
        List<String> tags
) {

    public QuarkReleaseItemResponse {
        matchReasons = matchReasons == null ? List.of() : List.copyOf(matchReasons);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
