package com.medianexus.orchestrator.dto.magnet.response;

import java.util.List;

public record AnimeMagnetIngestTaskLogListResponse(
        List<AnimeMagnetIngestTaskLogResponse> items,
        Integer total
) {
}
