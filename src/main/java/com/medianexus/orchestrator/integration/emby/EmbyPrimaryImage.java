package com.medianexus.orchestrator.integration.emby;

public record EmbyPrimaryImage(
        byte[] bytes,
        String contentType
) {
}
