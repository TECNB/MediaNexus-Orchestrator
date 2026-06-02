package com.medianexus.orchestrator.dto.magnet.response;

import java.util.List;

public record AnimeMagnetSearchResponse(
        List<AnimeMagnetSearchItem> items,
        Integer total
) {
}
