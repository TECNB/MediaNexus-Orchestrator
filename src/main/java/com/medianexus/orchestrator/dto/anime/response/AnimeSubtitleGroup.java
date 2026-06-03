package com.medianexus.orchestrator.dto.anime.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Mikan 字幕组候选")
public record AnimeSubtitleGroup(
        @Schema(description = "前端使用的稳定字幕组标识，优先由 subgroupId 生成")
        String id,
        @Schema(description = "字幕组显示名称")
        String label,
        @Schema(description = "字幕组 RSS 地址，订阅预览和创建时回传")
        String rss,
        @Schema(description = "Bangumi 条目地址，订阅预览和创建时回传")
        @JsonProperty("bgm_url")
        String bgmUrl,
        @Schema(description = "根据标签和种子标题推断的字幕语言，只返回简体、繁体或简繁候选")
        String language,
        @Schema(description = "字幕组候选下的条目数量")
        @JsonProperty("item_count")
        int itemCount,
        @Schema(description = "上游返回的最近更新日期文本；上游未返回时为 null", nullable = true)
        @JsonProperty("update_day")
        String updateDay
) {
}
