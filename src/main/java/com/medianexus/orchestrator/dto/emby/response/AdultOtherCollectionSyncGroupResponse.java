package com.medianexus.orchestrator.dto.emby.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Adult-Other 合集同步候选组")
public record AdultOtherCollectionSyncGroupResponse(
        String collectionName,
        String sourceFolderPath,
        Integer itemCount,
        Boolean eligible,
        String action,
        String embyCollectionId,
        Integer addedItemCount,
        String skipReason,
        List<String> sampleItemNames
) {
}
