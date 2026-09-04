package com.medianexus.orchestrator.integration.javdb;

import java.util.List;

public record JavdbMagnet(
        String magnet,
        String originalName,
        String infohash,
        boolean hasSubtitle,
        boolean isCracked,
        List<String> labels,
        String detectionSource
) {
}
