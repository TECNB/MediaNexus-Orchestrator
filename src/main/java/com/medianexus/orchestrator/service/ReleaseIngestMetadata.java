package com.medianexus.orchestrator.service;

import java.util.List;

public record ReleaseIngestMetadata(
        String sourceType,
        String releaseTitle,
        String releaseIndexer,
        Long releaseSize,
        Integer releaseIndexerId,
        String releaseGuid,
        List<String> resolutionTags,
        List<String> dynamicRangeTags
) {

    public static ReleaseIngestMetadata manual() {
        return new ReleaseIngestMetadata(
                "MANUAL_MAGNET",
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of()
        );
    }
}
