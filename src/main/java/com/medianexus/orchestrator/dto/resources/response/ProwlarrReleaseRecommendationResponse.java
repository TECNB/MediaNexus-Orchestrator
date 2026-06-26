package com.medianexus.orchestrator.dto.resources.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "电影发布资源推荐结果")
public record ProwlarrReleaseRecommendationResponse(
        @Schema(description = "首选推荐资源命中的查询内容")
        String query,
        @Schema(description = "首选推荐发布资源")
        ProwlarrReleaseItemResponse item,
        @Schema(description = "可供用户确认选择的推荐发布资源列表")
        List<ProwlarrReleaseItemResponse> items
) {
}
