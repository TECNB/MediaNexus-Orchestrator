package com.medianexus.orchestrator.dto.taskcenter.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record QuarkTaskCenterListResponse(
        List<QuarkTaskCenterItemResponse> items,
        int total,
        int page,
        @JsonProperty("page_size") int pageSize,
        @JsonProperty("all_count") int allCount,
        @JsonProperty("in_progress_count") int inProgressCount,
        @JsonProperty("needs_attention_count") int needsAttentionCount,
        @JsonProperty("succeeded_count") int succeededCount,
        @JsonProperty("subscribed_count") int subscribedCount
) {
    public QuarkTaskCenterListResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
