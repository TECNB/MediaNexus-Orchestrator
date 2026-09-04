package com.medianexus.orchestrator.dto.taskcenter.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record QuarkTaskCenterLogsResponse(
        List<OpenListIngestTaskCenterLogResponse> logs,
        @JsonProperty("has_older") boolean hasOlder,
        @JsonProperty("has_newer") boolean hasNewer,
        @JsonProperty("min_log_id") Long minLogId,
        @JsonProperty("max_log_id") Long maxLogId
) {
    public QuarkTaskCenterLogsResponse {
        logs = logs == null ? List.of() : List.copyOf(logs);
    }
}
