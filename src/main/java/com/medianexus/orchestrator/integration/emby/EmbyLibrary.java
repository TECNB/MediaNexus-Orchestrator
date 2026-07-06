package com.medianexus.orchestrator.integration.emby;

import java.util.List;

public record EmbyLibrary(
        String id,
        String name,
        List<String> locations
) {
}
