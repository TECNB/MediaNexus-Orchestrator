package com.medianexus.orchestrator.integration.javdb;

import java.util.List;

public record JavdbMagnet(
        String magnet,
        String originalName,
        String infohash,
        Long sizeBytes,
        boolean hasSubtitle,
        boolean isCracked,
        List<String> labels,
        String detectionSource
) {
}
