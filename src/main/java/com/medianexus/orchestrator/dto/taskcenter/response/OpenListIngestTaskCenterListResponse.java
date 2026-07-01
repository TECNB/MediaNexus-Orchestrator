package com.medianexus.orchestrator.dto.taskcenter.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "任务中心 OpenList 入库任务列表响应")
public record OpenListIngestTaskCenterListResponse(
        @Schema(description = "按更新时间倒序排列的任务列表")
        List<OpenListIngestTaskCenterItemResponse> items,
        @Schema(description = "返回任务数量")
        Integer total
) {
}
