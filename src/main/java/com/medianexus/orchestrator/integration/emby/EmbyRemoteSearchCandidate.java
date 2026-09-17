package com.medianexus.orchestrator.integration.emby;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

public record EmbyRemoteSearchCandidate(
        String candidateId,
        String name,
        String originalTitle,
        Integer productionYear,
        Map<String, String> providerIds,
        String searchProviderName,
        String overview,
        String imageUrl,
        JsonNode raw
) {
}
