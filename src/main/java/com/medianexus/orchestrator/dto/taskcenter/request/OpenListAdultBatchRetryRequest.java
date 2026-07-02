package com.medianexus.orchestrator.dto.taskcenter.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "任务中心 Adult 整批重新提交请求")
public record OpenListAdultBatchRetryRequest(
        @Schema(description = "重新提交的完整 magnet 或 ed2k 链接列表，最多 50 条")
        @JsonProperty("download_links")
        @NotEmpty(message = "下载链接列表不能为空")
        @Size(max = 50, message = "单批最多提交 50 条下载链接")
        List<String> downloadLinks
) {
}
