package com.medianexus.orchestrator.integration.emby;

public record EmbyMediaLibraryItem(
        String id,
        String title,
        String fileName,
        String type,
        Integer year,
        String dateCreated,
        String primaryImageTag,
        String path
) {
    public boolean hasPrimaryImage() {
        return primaryImageTag != null && !primaryImageTag.isBlank();
    }
}
