package com.medianexus.orchestrator.dto.quark.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record QuarkSourceTaskResultResponse(
        @JsonProperty("source_candidate_id") String sourceCandidateId,
        @JsonProperty("task_name") String taskName,
        String status,
        String message
) {
}
