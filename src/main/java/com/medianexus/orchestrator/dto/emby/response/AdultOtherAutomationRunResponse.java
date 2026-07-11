package com.medianexus.orchestrator.dto.emby.response;

import java.time.LocalDateTime;
import java.util.List;

public record AdultOtherAutomationRunResponse(
        String id,
        String triggerType,
        String status,
        String stage,
        Integer eventCount,
        Integer targetItemCount,
        Integer naturalPrimaryReadyCount,
        Integer targetedRefreshCount,
        Integer finalPrimaryReadyCount,
        Integer finalPrimaryMissingCount,
        Integer affectedCollectionCount,
        Integer createdCollectionCount,
        Integer updatedCollectionCount,
        Integer collectionImageReadyCount,
        Integer deletedCollectionCount,
        String message,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        List<AdultOtherAutomationItemResponse> items,
        List<AdultOtherAutomationCollectionResponse> collections
) {
}
