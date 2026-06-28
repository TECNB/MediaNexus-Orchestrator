package com.medianexus.orchestrator.dto.magnet.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "Adult 批量 magnet 导入任务日志")
public record AdultMagnetIngestTaskLogResponse(
        Long id,

        @JsonProperty("task_id")
        String taskId,

        String level,

        String stage,

        String message,

        String detail,

        @JsonProperty("created_at")
        LocalDateTime createdAt
) {
}
