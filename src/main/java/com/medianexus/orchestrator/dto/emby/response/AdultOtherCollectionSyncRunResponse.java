package com.medianexus.orchestrator.dto.emby.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "Adult-Other 合集同步运行结果")
public record AdultOtherCollectionSyncRunResponse(
        String id,
        String mode,
        String status,
        Integer minItemCount,
        String sourceFolderPath,
        Integer totalItemCount,
        Integer groupedItemCount,
        Integer skippedItemCount,
        Integer groupCount,
        Integer eligibleGroupCount,
        Integer createdCollectionCount,
        Integer updatedCollectionCount,
        Integer unchangedCollectionCount,
        Integer deletedCollectionCount,
        Integer reviewCollectionCount,
        Integer itemAddCount,
        String errorMessage,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        List<AdultOtherCollectionSyncGroupResponse> groups
) {
}
