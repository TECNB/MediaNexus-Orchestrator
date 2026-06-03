package com.medianexus.orchestrator.dto.magnet.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Bangumi 条目搜索响应")
public record AnimeMagnetSearchResponse(
        @Schema(description = "搜索结果条目")
        List<AnimeMagnetSearchItem> items,
        @Schema(description = "返回条目总数")
        Integer total
) {
}
