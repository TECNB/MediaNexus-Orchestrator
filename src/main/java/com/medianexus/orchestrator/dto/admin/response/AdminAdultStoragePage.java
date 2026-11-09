package com.medianexus.orchestrator.dto.admin.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Adult 网盘目录的一层内容，包含未被 Emby 收录的文件")
public record AdminAdultStoragePage(List<String> roots, String directory, List<Item> items) {
    @Schema(description = "网盘文件或文件夹")
    public record Item(
            String path,
            boolean directory,
            @Schema(description = "网盘条目标识，缺失时无法确认删除目标")
            String id,
            @Schema(description = "文件自身字节数；文件夹为 null", nullable = true)
            @JsonProperty("file_bytes")
            Long fileBytes,
            @Schema(description = "逻辑容量字节数，统计中或未知时为 null", nullable = true)
            @JsonProperty("storage_bytes")
            Long storageBytes,
            @Schema(description = "容量状态：complete、pending 或 unknown")
            @JsonProperty("capacity_status")
            String capacityStatus
    ) { }
}
