package com.medianexus.orchestrator.dto.taskcenter.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "任务中心 OpenList 入库任务尝试链条目")
public record OpenListIngestTaskCenterAttemptResponse(
        @Schema(description = "底层任务类型：MOVIE、SERIES、ANIME 或 ADULT")
        @JsonProperty("task_type")
        String taskType,
        @Schema(description = "底层任务 id")
        String id,
        @Schema(description = "任务中心产品类别：MOVIE、SERIES、ANIME 或 ADULT")
        @JsonProperty("product_type")
        String productType,
        @Schema(description = "任务标题")
        String title,
        @Schema(description = "任务状态")
        String status,
        @Schema(description = "任务阶段")
        String stage,
        @Schema(description = "任务来源：MANUAL_MAGNET 或 PROWLARR_RELEASE")
        @JsonProperty("source_type")
        String sourceType,
        @Schema(description = "创建任务的用户 id；历史无归属任务可能为 null", nullable = true)
        @JsonProperty("created_by_user_id")
        Long createdByUserId,
        @Schema(description = "创建任务的用户名；历史无归属任务可能为 null", nullable = true)
        @JsonProperty("created_by_username")
        String createdByUsername,
        @Schema(description = "直接来源任务类型；来源不可访问或原始尝试时为 null", nullable = true)
        @JsonProperty("retry_of_task_type")
        String retryOfTaskType,
        @Schema(description = "直接来源任务 id；来源不可访问或原始尝试时为 null", nullable = true)
        @JsonProperty("retry_of_task_id")
        String retryOfTaskId,
        @Schema(description = "是否为当前详情任务")
        @JsonProperty("is_current")
        Boolean current,
        @Schema(description = "统一任务详情路径")
        @JsonProperty("detail_path")
        String detailPath,
        @Schema(description = "任务创建时间")
        @JsonProperty("created_at")
        LocalDateTime createdAt,
        @Schema(description = "任务更新时间")
        @JsonProperty("updated_at")
        LocalDateTime updatedAt
) {
}
