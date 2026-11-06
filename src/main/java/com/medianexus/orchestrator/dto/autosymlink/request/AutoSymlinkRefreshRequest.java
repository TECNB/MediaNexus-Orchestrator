package com.medianexus.orchestrator.dto.autosymlink.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "AutoSymlink 手动刷新请求")
public record AutoSymlinkRefreshRequest(
        @Schema(description = "刷新目标：MOVIE、TV、ANIME、ADULT")
        @NotBlank(message = "刷新目标不能为空")
        String target,
        @Schema(description = "下载目标稳定 ID；不传时保留现有全局刷新语义", requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true)
        String targetId
) {
    public AutoSymlinkRefreshRequest(String target) {
        this(target, null);
    }
}
