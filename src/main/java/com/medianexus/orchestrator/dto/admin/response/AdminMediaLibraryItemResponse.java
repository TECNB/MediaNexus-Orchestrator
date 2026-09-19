package com.medianexus.orchestrator.dto.admin.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "管理员媒体库顶层条目")
public record AdminMediaLibraryItemResponse(
        @Schema(description = "Emby 条目 id")
        @JsonProperty("item_id")
        String itemId,

        @Schema(description = "媒体标题")
        String title,

        @Schema(description = "Emby 媒体路径中的真实文件名；路径缺失时为 null", nullable = true)
        @JsonProperty("file_name")
        String fileName,

        @Schema(description = "媒体年份；Emby 未提供时为 null", nullable = true)
        Integer year,

        @Schema(description = "Emby 入库时间；Emby 未提供时为 null", nullable = true)
        @JsonProperty("date_created")
        String dateCreated,

        @Schema(description = "是否有 Primary 封面")
        @JsonProperty("has_primary_image")
        boolean hasPrimaryImage,

        @JsonProperty("primary_image_tag")
        String primaryImageTag,

        @Schema(description = "Emby 虚拟媒体库 id")
        @JsonProperty("library_id")
        String libraryId,

        @Schema(description = "Emby 虚拟媒体库名称")
        @JsonProperty("library_name")
        String libraryName,

        @Schema(description = "Emby 媒体类型：Movie、Series 或 BoxSet")
        String type
) {
}
