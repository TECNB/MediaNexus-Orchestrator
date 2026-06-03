package com.medianexus.orchestrator.dto.anime.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Mikan 番剧搜索条目")
public record AnimeSearchItem(
        @Schema(description = "前端使用的稳定条目标识，优先由 Mikan URL 数字 id 生成")
        String id,
        @Schema(description = "番剧标题；上游缺失时返回“未知番剧”")
        String title,
        @Schema(description = "封面图片地址；上游未返回时为 null", nullable = true)
        String cover,
        @Schema(description = "Mikan 番剧来源页面地址，后续加载字幕组时使用")
        @JsonProperty("source_url")
        String sourceUrl,
        @Schema(description = "上游返回的评分；上游未返回时为 null", nullable = true)
        Double score,
        @Schema(description = "Ani-RSS 上游标记的本地存在状态，缺失时为 false")
        Boolean exists,
        @Schema(description = "Mikan 周期分组标签")
        @JsonProperty("week_label")
        String weekLabel
) {
}
