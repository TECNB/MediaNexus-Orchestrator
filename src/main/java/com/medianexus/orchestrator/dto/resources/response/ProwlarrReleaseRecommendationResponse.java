package com.medianexus.orchestrator.dto.resources.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "电影发布资源推荐结果")
public record ProwlarrReleaseRecommendationResponse(
        @Schema(description = "首选推荐资源命中的查询内容")
        String query,
        @Schema(description = "用户请求的分辨率标签")
        @JsonProperty("requested_quality")
        String requestedQuality,
        @Schema(description = "实际用于推荐的分辨率标签；2160p 没有合格资源时可回退为 1080p")
        @JsonProperty("selected_quality")
        String selectedQuality,
        @Schema(description = "首选推荐发布资源")
        ProwlarrReleaseItemResponse item,
        @Schema(description = "可供用户确认选择的推荐发布资源列表")
        List<ProwlarrReleaseItemResponse> items
) {
}
