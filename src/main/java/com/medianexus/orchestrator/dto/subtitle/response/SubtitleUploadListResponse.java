package com.medianexus.orchestrator.dto.subtitle.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "字幕上传批次列表响应")
public record SubtitleUploadListResponse(
        @Schema(description = "上传批次，按创建时间倒序排列")
        List<SubtitleUploadResponse> items,
        @Schema(description = "返回批次数量")
        Integer total
) {
}
