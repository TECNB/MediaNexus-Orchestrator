package com.medianexus.orchestrator.dto.anime.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Mikan 番剧搜索响应")
public record AnimeSearchResponse(
        @Schema(description = "搜索结果条目")
        List<AnimeSearchItem> items,
        @Schema(description = "返回条目总数")
        int total
) {
}
