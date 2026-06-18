package com.medianexus.orchestrator.dto.magnet.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "剧集 magnet 导入任务日志列表响应")
public record SeriesMagnetIngestTaskLogListResponse(
        @Schema(description = "任务日志，按日志 id 升序排列")
        List<SeriesMagnetIngestTaskLogResponse> items,
        @Schema(description = "返回日志数量")
        Integer total
) {
}
