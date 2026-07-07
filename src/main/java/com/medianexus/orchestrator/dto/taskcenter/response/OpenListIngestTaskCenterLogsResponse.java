package com.medianexus.orchestrator.dto.taskcenter.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "任务中心 OpenList 入库任务日志窗口")
public record OpenListIngestTaskCenterLogsResponse(
        @Schema(description = "日志窗口，按日志 id 升序排列")
        List<OpenListIngestTaskCenterLogResponse> logs,
        @Schema(description = "当前窗口之前是否还有更早日志")
        @JsonProperty("has_older")
        Boolean hasOlder,
        @Schema(description = "当前窗口之后是否还有更新日志")
        @JsonProperty("has_newer")
        Boolean hasNewer,
        @Schema(description = "当前窗口第一条日志 id；无日志时为 null", nullable = true)
        @JsonProperty("min_log_id")
        Long minLogId,
        @Schema(description = "当前窗口最后一条日志 id；无日志时为 null", nullable = true)
        @JsonProperty("max_log_id")
        Long maxLogId
) {
}
