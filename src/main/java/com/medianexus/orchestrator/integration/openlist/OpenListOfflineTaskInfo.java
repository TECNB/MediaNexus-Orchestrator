package com.medianexus.orchestrator.integration.openlist;

public record OpenListOfflineTaskInfo(
        String id,
        Integer state,
        String error
) {
}
