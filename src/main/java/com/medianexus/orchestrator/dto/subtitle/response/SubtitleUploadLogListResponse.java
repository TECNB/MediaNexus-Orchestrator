package com.medianexus.orchestrator.dto.subtitle.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "字幕上传批次日志列表响应")
public record SubtitleUploadLogListResponse(
        @Schema(description = "上传批次日志，按日志 id 升序排列")
        List<SubtitleUploadLogResponse> items,
        @Schema(description = "返回日志数量")
        Integer total
) {
}
