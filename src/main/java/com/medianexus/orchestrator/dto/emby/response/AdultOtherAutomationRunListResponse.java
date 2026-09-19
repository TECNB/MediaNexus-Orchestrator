package com.medianexus.orchestrator.dto.emby.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record AdultOtherAutomationRunListResponse(
        List<AdultOtherAutomationRunResponse> items,
        int total,
        int page,
        @JsonProperty("page_size") int pageSize
) {
}
