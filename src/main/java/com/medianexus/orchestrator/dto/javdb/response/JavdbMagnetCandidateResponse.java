package com.medianexus.orchestrator.dto.javdb.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "JAVDB 详情页磁力候选")
public record JavdbMagnetCandidateResponse(
        String magnet,
        @JsonProperty("original_name") String originalName,
        String infohash,
        @JsonProperty("has_subtitle") boolean hasSubtitle,
        @JsonProperty("is_cracked") boolean cracked,
        List<String> labels,
        @JsonProperty("detection_source") String detectionSource
) {
}
