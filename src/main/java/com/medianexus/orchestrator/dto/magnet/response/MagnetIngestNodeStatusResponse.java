package com.medianexus.orchestrator.dto.magnet.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "手动磁力入库使用的 OpenList 云盘节点状态")
public record MagnetIngestNodeStatusResponse(
        String provider,
        boolean online,
        @JsonProperty("used_bytes") Long usedBytes,
        @JsonProperty("total_bytes") Long totalBytes,
        @JsonProperty("free_bytes") Long freeBytes,
        @Schema(description = "服务端登记的稳定目标 ID；未接管时为 legacy")
        @JsonProperty("target_id") String targetId,
        @Schema(description = "目标显示名；未接管时为现有媒体库")
        @JsonProperty("target_name") String targetName,
        @Schema(description = "媒体类别：MOVIE、TV、ANIME 或 ADULT")
        @JsonProperty("category") String category
) {
    public MagnetIngestNodeStatusResponse(String provider, boolean online, Long usedBytes, Long totalBytes, Long freeBytes) {
        this(provider, online, usedBytes, totalBytes, freeBytes, null, null, null);
    }
}
