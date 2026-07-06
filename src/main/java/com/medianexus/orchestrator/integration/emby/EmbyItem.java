package com.medianexus.orchestrator.integration.emby;

public record EmbyItem(
        String id,
        String name,
        String type,
        String path,
        String dateCreated
) {
}
