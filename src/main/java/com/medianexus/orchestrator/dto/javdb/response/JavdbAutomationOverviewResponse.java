package com.medianexus.orchestrator.dto.javdb.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record JavdbAutomationOverviewResponse(
        JavdbAutomationConfigResponse config,
        @JsonProperty("latest_run") JavdbAutomationRunResponse latestRun,
        @JsonProperty("current_run") JavdbAutomationRunResponse currentRun
) {
}
