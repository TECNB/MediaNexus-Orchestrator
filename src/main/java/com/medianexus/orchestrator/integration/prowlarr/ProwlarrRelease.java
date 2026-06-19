package com.medianexus.orchestrator.integration.prowlarr;

public record ProwlarrRelease(
        String title,
        Long size,
        Integer seeders,
        Integer leechers,
        Integer grabs,
        String indexer,
        String publishDate,
        Integer indexerId,
        String guid,
        String downloadRef
) {
}
