package com.medianexus.orchestrator.dto.emby.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "Adult-Other 可同步源文件夹")
public record AdultOtherCollectionSourceFolderResponse(
        String path,
        String label,
        Integer itemCount,
        Integer groupCount,
        LocalDateTime latestPreviewAt,
        LocalDateTime latestSyncAt,
        Integer lastSyncedItemCount,
        Integer lastSyncedGroupCount,
        Integer itemDelta,
        Integer groupDelta,
        String changeStatus
) {
}
