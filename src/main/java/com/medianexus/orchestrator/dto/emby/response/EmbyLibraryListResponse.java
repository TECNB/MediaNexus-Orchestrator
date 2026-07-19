package com.medianexus.orchestrator.dto.emby.response;

import java.util.List;

public record EmbyLibraryListResponse(
        List<EmbyLibrarySummaryResponse> items
) {
}
