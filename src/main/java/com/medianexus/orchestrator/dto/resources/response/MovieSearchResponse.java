package com.medianexus.orchestrator.dto.resources.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "电影搜索响应")
public record MovieSearchResponse(
        List<MovieSearchItem> items
) {
}
