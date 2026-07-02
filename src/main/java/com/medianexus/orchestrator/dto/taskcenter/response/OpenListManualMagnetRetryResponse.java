package com.medianexus.orchestrator.dto.taskcenter.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "任务中心手动 magnet 重试响应")
public record OpenListManualMagnetRetryResponse(
        @Schema(description = "新任务底层类型：MOVIE、SERIES 或 ANIME")
        @JsonProperty("task_type")
        String taskType,
        @Schema(description = "新任务 id")
        String id,
        @Schema(description = "新任务详情路径")
        @JsonProperty("detail_path")
        String detailPath
) {
}
