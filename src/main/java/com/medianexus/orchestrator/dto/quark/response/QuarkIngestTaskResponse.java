package com.medianexus.orchestrator.dto.quark.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "QAS 任务创建与即时触发结果")
public record QuarkIngestTaskResponse(
        @Schema(description = "结果状态：STARTED、SCHEDULED 或 PARTIAL")
        String status,
        @JsonProperty("media_type")
        @Schema(description = "媒体类型：MOVIE、SERIES 或 VARIETY")
        String mediaType,
        @JsonProperty("task_name")
        @Schema(description = "写入 QAS 的任务名称")
        String taskName,
        @JsonProperty("save_path")
        @Schema(description = "夸克云盘目标保存路径")
        String savePath,
        @JsonProperty("immediate_execution_started")
        @Schema(description = "是否已经由 QAS 接受即时执行请求")
        boolean immediateExecutionStarted,
        @JsonProperty("created_task_count")
        @Schema(description = "已成功创建的 QAS 任务数")
        int createdTaskCount,
        @JsonProperty("planned_task_count")
        @Schema(description = "规划的 QAS 任务数")
        int plannedTaskCount,
        @Schema(description = "规划或执行警告")
        java.util.List<String> warnings,
        @Schema(description = "面向用户的创建结果说明")
        String message
) {
}
