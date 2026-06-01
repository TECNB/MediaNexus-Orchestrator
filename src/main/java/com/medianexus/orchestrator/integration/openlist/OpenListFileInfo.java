package com.medianexus.orchestrator.integration.openlist;

public record OpenListFileInfo(
        String name,
        Long size,
        Boolean isDir,
        String path
) {
}
