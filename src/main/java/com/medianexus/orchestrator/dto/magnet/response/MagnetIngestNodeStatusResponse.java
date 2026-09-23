package com.medianexus.orchestrator.dto.magnet.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "手动磁力入库使用的 OpenList 云盘节点状态")
public record MagnetIngestNodeStatusResponse(
        String provider,
        boolean online,
        @JsonProperty("used_bytes") Long usedBytes,
        @JsonProperty("total_bytes") Long totalBytes,
        @JsonProperty("free_bytes") Long freeBytes
) {
}
