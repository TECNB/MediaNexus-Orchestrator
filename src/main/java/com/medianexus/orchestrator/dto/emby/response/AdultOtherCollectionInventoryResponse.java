package com.medianexus.orchestrator.dto.emby.response;

import java.util.List;

public record AdultOtherCollectionInventoryResponse(
        Integer sourceFolderCount,
        Integer groupCount,
        Integer healthyGroupCount,
        Integer pendingCreateGroupCount,
        Integer pendingMemberGroupCount,
        Integer skippedGroupCount,
        List<AdultOtherCollectionSourceFolderResponse> sourceFolders
) {
}
