package com.medianexus.orchestrator.dto.resources.response;

import java.util.List;

public record ProwlarrReleaseSearchResponse(
        String query,
        List<ProwlarrReleaseItemResponse> items,
        ReleaseComparisonProgress comparison
) {
    public ProwlarrReleaseSearchResponse(String query, List<ProwlarrReleaseItemResponse> items) {
        this(query, items, null);
    }
}
