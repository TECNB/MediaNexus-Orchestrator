package com.medianexus.orchestrator.dto.javdb.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record JavdbAutomationRunListResponse(
        List<JavdbAutomationRunResponse> items,
        int total,
        int page,
        @JsonProperty("page_size") int pageSize
) {
}
