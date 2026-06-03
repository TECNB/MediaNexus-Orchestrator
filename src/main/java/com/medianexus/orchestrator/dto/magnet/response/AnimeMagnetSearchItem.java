package com.medianexus.orchestrator.dto.magnet.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Bangumi 搜索条目，用于整季 magnet 导入前选择番剧")
public record AnimeMagnetSearchItem(
        @Schema(description = "前端使用的稳定条目标识")
        String id,
        @Schema(description = "Bangumi 条目 id")
        @JsonProperty("bgm_id")
        String bgmId,
        @Schema(description = "Bangumi 条目地址")
        @JsonProperty("bgm_url")
        String bgmUrl,
        @Schema(description = "展示标题，优先中文名，其次原名")
        String title,
        @Schema(description = "Bangumi 中文名；上游未返回时为 null", nullable = true)
        @JsonProperty("name_cn")
        String nameCn,
        @Schema(description = "Bangumi 原名；上游未返回时为 null", nullable = true)
        String name,
        @Schema(description = "封面图片地址；上游未返回时为 null", nullable = true)
        String cover,
        @Schema(description = "Bangumi 评分；上游未返回时为 null", nullable = true)
        Double score,
        @Schema(description = "总集数；上游未返回时为 null", nullable = true)
        Integer eps,
        @Schema(description = "开播日期文本；上游未返回时为 null", nullable = true)
        @JsonProperty("air_date")
        String airDate,
        @Schema(description = "季度编号；上游未返回时为 null", nullable = true)
        Integer season,
        @Schema(description = "播放平台或放送类型；上游未返回时为 null", nullable = true)
        String platform
) {
}
