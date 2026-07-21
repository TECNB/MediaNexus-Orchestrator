package com.medianexus.orchestrator.integration.emby;

public record EmbyUserAccount(
        String id,
        String name,
        boolean administrator,
        boolean disabled
) {
}
