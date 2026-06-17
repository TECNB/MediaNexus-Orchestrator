package com.medianexus.orchestrator.dto.resources.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "剧集搜索响应")
public record SeriesSearchResponse(
        List<SeriesSearchItem> items
) {
}
