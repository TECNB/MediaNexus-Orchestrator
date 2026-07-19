package com.medianexus.orchestrator.dto.emby.response;

public record EmbyLibraryRefreshResponse(
        String libraryId,
        String libraryName,
        String message
) {
}
