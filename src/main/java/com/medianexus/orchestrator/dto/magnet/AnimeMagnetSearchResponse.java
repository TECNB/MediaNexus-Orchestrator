package com.medianexus.orchestrator.dto.magnet;

import java.util.List;

public record AnimeMagnetSearchResponse(
        List<AnimeMagnetSearchItem> items,
        Integer total
) {
}
