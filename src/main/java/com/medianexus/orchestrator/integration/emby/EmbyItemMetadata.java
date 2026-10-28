package com.medianexus.orchestrator.integration.emby;

public record EmbyItemMetadata(
        String itemId,
        String itemType,
        String itemName,
        String seriesId,
        String seriesName,
        Integer seasonNumber,
        Integer episodeNumber,
        String runtimeTicks,
        String watchCategory,
        String libraryId,
        String libraryName
) {
    public EmbyItemMetadata(String itemId, String itemType, String itemName, String seriesId,
            String seriesName, Integer seasonNumber, Integer episodeNumber, String runtimeTicks) {
        this(itemId, itemType, itemName, seriesId, seriesName, seasonNumber, episodeNumber,
                runtimeTicks, "unknown", null, null);
    }
}
