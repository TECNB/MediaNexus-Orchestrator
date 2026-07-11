package com.medianexus.orchestrator.dto.emby.response;

public record AdultOtherAutomationCollectionResponse(
        String embyCollectionId,
        String collectionName,
        String action,
        Integer addedItemCount,
        Boolean imageReady,
        String status,
        String message
) {
}
