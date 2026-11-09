package com.medianexus.orchestrator.dto.admin.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

@Schema(description = "从 Adult 网盘文件夹视图选择的单个删除目标")
public record AdminAdultStorageDeletionRequest(
        @NotBlank String library,
        @NotBlank @Size(max = 4096) String path,
        @NotNull Boolean directory,
        @Schema(description = "目录列表中的网盘条目标识")
        @NotBlank @Size(max = 4096) String id,
        @Schema(description = "列表返回的文件字节数；目录省略", nullable = true)
        @JsonProperty("file_bytes")
        @PositiveOrZero Long fileBytes
) { }
