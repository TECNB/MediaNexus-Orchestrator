package com.medianexus.orchestrator.integration.emby;

public record EmbyItemState(
        String id,
        String name,
        String type,
        String path,
        boolean hasPrimaryImage,
        int mediaStreamCount
) {
}
