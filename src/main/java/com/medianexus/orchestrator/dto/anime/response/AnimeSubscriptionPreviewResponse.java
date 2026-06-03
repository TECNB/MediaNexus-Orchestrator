package com.medianexus.orchestrator.dto.anime.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Ani-RSS 订阅预览结果")
public record AnimeSubscriptionPreviewResponse(
        @Schema(description = "Ani-RSS 解析出的订阅标题")
        String title,
        @Schema(description = "Ani-RSS 解析出的季度编号；上游未解析出时为 null", nullable = true)
        Integer season,
        @Schema(description = "选中的字幕组名称")
        String subgroup,
        @Schema(description = "预览可下载条目数量；小于等于 0 时不会允许创建订阅")
        @JsonProperty("preview_count")
        int previewCount,
        @Schema(description = "Ani-RSS 预览识别出的缺失集数列表")
        @JsonProperty("missing_episodes")
        List<Integer> missingEpisodes,
        @Schema(description = "面向前端展示的缺集摘要；无缺集时为 null", nullable = true)
        @JsonProperty("missing_summary")
        String missingSummary,
        @Schema(description = "是否存在缺集")
        @JsonProperty("has_missing_episodes")
        boolean hasMissingEpisodes
) {
}
