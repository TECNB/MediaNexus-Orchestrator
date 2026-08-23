package com.medianexus.orchestrator.dto.resources.response;

import java.util.List;

public record QuarkReleaseSearchResponse(
        String query,
        List<QuarkReleaseItemResponse> items,
        List<String> warnings
) {

    public QuarkReleaseSearchResponse {
        items = items == null ? List.of() : List.copyOf(items);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
