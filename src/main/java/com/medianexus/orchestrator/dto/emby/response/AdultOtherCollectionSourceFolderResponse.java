package com.medianexus.orchestrator.dto.emby.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Adult-Other 可同步源文件夹")
public record AdultOtherCollectionSourceFolderResponse(
        String path,
        String label,
        Integer itemCount,
        Integer groupCount,
        Integer healthyGroupCount,
        Integer pendingCreateGroupCount,
        Integer pendingMemberGroupCount,
        Integer skippedGroupCount,
        String healthStatus
) {
}
