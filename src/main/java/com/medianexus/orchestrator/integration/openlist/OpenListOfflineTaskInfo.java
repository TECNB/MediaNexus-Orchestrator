package com.medianexus.orchestrator.integration.openlist;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenListOfflineTaskInfo(
        String id,
        Integer state,
        String status,
        @Min(0)
        @Max(100)
        Integer progress,
        @PositiveOrZero
        @JsonProperty("total_bytes")
        Long totalBytes,
        String error
) {
}
