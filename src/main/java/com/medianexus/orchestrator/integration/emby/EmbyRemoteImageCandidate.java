package com.medianexus.orchestrator.integration.emby;

public record EmbyRemoteImageCandidate(
        String candidateId,
        String providerName,
        String url,
        String thumbnailUrl,
        Integer width,
        Integer height,
        String language
) {
}
