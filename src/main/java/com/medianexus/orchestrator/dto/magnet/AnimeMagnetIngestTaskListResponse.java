package com.medianexus.orchestrator.dto.magnet;

import java.util.List;

public record AnimeMagnetIngestTaskListResponse(
        List<AnimeMagnetIngestTaskResponse> items,
        Integer total
) {
}
