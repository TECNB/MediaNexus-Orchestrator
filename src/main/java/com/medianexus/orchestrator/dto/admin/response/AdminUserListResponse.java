package com.medianexus.orchestrator.dto.admin.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "管理员用户分页列表响应")
public record AdminUserListResponse(
        @Schema(description = "当前页用户")
        List<AdminUserResponse> items,
        @Schema(description = "当前页码，从 1 开始")
        int page,
        @Schema(description = "每页条数")
        @JsonProperty("page_size")
        int pageSize,
        @Schema(description = "匹配条件的总用户数")
        long total
) {
}
