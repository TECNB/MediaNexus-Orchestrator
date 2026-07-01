package com.medianexus.orchestrator.dto.taskcenter.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "任务中心 OpenList 入库任务日志")
public record OpenListIngestTaskCenterLogResponse(
        @Schema(description = "日志自增 id")
        Long id,
        @Schema(description = "所属任务 id")
        @JsonProperty("task_id")
        String taskId,
        @Schema(description = "日志级别：INFO、WARN 或 ERROR")
        String level,
        @Schema(description = "日志阶段")
        String stage,
        @Schema(description = "日志消息")
        String message,
        @Schema(description = "日志附加详情；没有附加信息时为 null", nullable = true)
        String detail,
        @Schema(description = "日志创建时间")
        @JsonProperty("created_at")
        LocalDateTime createdAt
) {
}
