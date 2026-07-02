package com.medianexus.orchestrator.dto.taskcenter.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "任务中心 OpenList 入库任务尝试链")
public record OpenListIngestTaskCenterAttemptChainResponse(
        @Schema(description = "稳定的任务尝试链 id")
        @JsonProperty("attempt_group_id")
        String attemptGroupId,
        @Schema(description = "当前详情任务")
        @JsonProperty("current_attempt")
        OpenListIngestTaskCenterAttemptResponse currentAttempt,
        @Schema(description = "当前任务的直接来源；不可访问或原始尝试时为 null", nullable = true)
        @JsonProperty("retry_of")
        OpenListIngestTaskCenterAttemptResponse retryOf,
        @Schema(description = "同一尝试链中当前用户可访问的任务尝试，按时间升序排列")
        List<OpenListIngestTaskCenterAttemptResponse> attempts
) {
}
