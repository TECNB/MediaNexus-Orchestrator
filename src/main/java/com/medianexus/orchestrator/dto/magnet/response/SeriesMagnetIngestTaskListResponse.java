package com.medianexus.orchestrator.dto.magnet.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "剧集 magnet 导入任务列表响应")
public record SeriesMagnetIngestTaskListResponse(
        @Schema(description = "最近创建的导入任务，按创建时间倒序排列")
        List<SeriesMagnetIngestTaskResponse> items,
        @Schema(description = "返回任务数量")
        Integer total
) {
}
