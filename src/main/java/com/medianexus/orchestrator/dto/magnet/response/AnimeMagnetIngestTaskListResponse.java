package com.medianexus.orchestrator.dto.magnet.response;

import java.util.List;

public record AnimeMagnetIngestTaskListResponse(
        List<AnimeMagnetIngestTaskResponse> items,
        Integer total
) {
}
