package com.medianexus.orchestrator.dto.anime.response;

public record AnimeSubscriptionResponse(
        String status,
        boolean added,
        boolean duplicate,
        String message,
        AnimeSubscriptionPreviewResponse preview
) {
}
