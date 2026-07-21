package com.medianexus.orchestrator.integration.emby;

public record EmbyCatalogItem(
        String id,
        String name,
        String type,
        String path,
        Integer indexNumber
) {
}
