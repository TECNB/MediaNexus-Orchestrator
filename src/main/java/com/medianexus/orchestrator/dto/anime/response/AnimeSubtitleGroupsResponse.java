package com.medianexus.orchestrator.dto.anime.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Mikan 字幕组候选响应")
public record AnimeSubtitleGroupsResponse(
        @Schema(description = "按语言、条目数量、更新时间排序后的字幕组候选")
        List<AnimeSubtitleGroup> groups,
        @Schema(description = "返回候选总数")
        int total
) {
}
