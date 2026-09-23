package com.medianexus.orchestrator.integration.openlist;

public record OpenListStorageStatus(
        String provider,
        boolean online,
        Long usedBytes,
        Long totalBytes,
        Long freeBytes
) {
}
