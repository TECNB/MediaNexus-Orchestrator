package com.medianexus.orchestrator.dto.resources.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "当前搜索快照的比较进度；只有 COMPLETE 表示队列全部评估，暂停不是无资源")
public record ReleaseComparisonProgress(
        String id,
        @Schema(description = "RUNNING 比较中、COMPLETE 本轮完成、PAUSED 预算暂停、BLOCKED 认证或上游阻塞") String status,
        @JsonProperty("snapshot_at") String snapshotAt,
        int checked,
        int pending,
        @JsonProperty("resolve_requests") int resolveRequests,
        @JsonProperty("search_queries") int searchQueries,
        String message,
        @JsonProperty("recommended_ref") String recommendedRef
) {}
