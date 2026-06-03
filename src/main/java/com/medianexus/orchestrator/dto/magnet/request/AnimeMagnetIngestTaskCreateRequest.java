package com.medianexus.orchestrator.dto.magnet.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "动漫整季 magnet 导入任务创建请求")
public record AnimeMagnetIngestTaskCreateRequest(
        @Schema(description = "整季 magnet 链接，必须包含 btih hash")
        String magnet,
        @Schema(description = "Bangumi 条目 id，来自导入搜索结果")
        @JsonProperty("bgm_id")
        String bgmId,
        @Schema(description = "Bangumi 条目地址；可不传", requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true)
        @JsonProperty("bgm_url")
        String bgmUrl,
        @Schema(description = "导入任务使用的展示标题；未提供时回退到中文名或原名", requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true)
        String title,
        @Schema(description = "Bangumi 中文名；可不传", requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true)
        @JsonProperty("name_cn")
        String nameCn,
        @Schema(description = "Bangumi 原名；可不传", requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true)
        String name,
        @Schema(description = "季度编号，未传时默认 1")
        @JsonProperty("season_number")
        Integer seasonNumber,
        @Schema(description = "TMDB 媒体目录名；未提供时用标题渲染 OpenList 保存路径", requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true)
        @JsonProperty("themoviedb_name")
        String themoviedbName
) {
}
