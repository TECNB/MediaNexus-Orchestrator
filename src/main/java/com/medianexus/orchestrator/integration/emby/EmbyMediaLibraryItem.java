package com.medianexus.orchestrator.integration.emby;

public record EmbyMediaLibraryItem(
        String id,
        String title,
        String type,
        Integer year,
        String dateCreated,
        boolean hasPrimaryImage
) {
}
