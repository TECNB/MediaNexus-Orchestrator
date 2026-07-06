package com.medianexus.orchestrator.dto.emby.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

@Schema(description = "Adult-Other 合集同步请求")
public record AdultOtherCollectionSyncRequest(
        @Schema(description = "自动处理的最小媒体数量；默认 2")
        @Min(value = 2, message = "最小媒体数量不能小于 2")
        @Max(value = 100, message = "最小媒体数量不能大于 100")
        Integer minItemCount,

        @Schema(description = "只同步 Adult - Other 下的指定相对文件夹；为空时全量预览或同步")
        @Size(max = 1024, message = "同步文件夹路径不能超过 1024 个字符")
        String sourceFolderPath
) {
}
