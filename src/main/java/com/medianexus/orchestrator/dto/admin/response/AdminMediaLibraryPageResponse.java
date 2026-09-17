package com.medianexus.orchestrator.dto.admin.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "管理员媒体库分页响应")
public record AdminMediaLibraryPageResponse(
        @Schema(description = "当前页媒体条目")
        List<AdminMediaLibraryItemResponse> items,

        @Schema(description = "当前页码，从 1 开始")
        int page,

        @Schema(description = "固定每页 24 条")
        @JsonProperty("page_size")
        int pageSize,

        @Schema(description = "当前媒体库中匹配搜索词的总条目数")
        int total
) {
}
