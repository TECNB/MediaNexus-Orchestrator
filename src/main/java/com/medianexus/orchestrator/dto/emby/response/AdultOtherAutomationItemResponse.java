package com.medianexus.orchestrator.dto.emby.response;

public record AdultOtherAutomationItemResponse(
        String embyItemId,
        String itemName,
        String itemPath,
        String collectionName,
        Boolean primaryBefore,
        Boolean refreshRequested,
        Boolean primaryAfter,
        String status,
        String message
) {
}
