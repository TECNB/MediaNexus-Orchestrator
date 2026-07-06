package com.medianexus.orchestrator.integration.emby;

public record EmbyItemMetadata(
        String itemId,
        String itemType,
        String itemName,
        String seriesId,
        String seriesName,
        Integer seasonNumber,
        Integer episodeNumber,
        String runtimeTicks
) {
}
