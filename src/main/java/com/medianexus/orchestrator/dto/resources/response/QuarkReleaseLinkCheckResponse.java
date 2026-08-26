package com.medianexus.orchestrator.dto.resources.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record QuarkReleaseLinkCheckResponse(
        @JsonProperty("view_token")
        String viewToken,
        List<QuarkReleaseLinkCheckItemResponse> items
) {

    public QuarkReleaseLinkCheckResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
