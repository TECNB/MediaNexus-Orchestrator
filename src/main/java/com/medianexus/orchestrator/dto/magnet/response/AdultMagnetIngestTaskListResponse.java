package com.medianexus.orchestrator.dto.magnet.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Adult 批量 magnet 导入任务列表响应")
public record AdultMagnetIngestTaskListResponse(
        List<AdultMagnetIngestTaskResponse> items,
        int total
) {
}
