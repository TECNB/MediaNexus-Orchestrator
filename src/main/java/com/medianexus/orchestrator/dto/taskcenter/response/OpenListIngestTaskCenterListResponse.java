package com.medianexus.orchestrator.dto.taskcenter.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "任务中心 OpenList 入库任务列表响应")
public record OpenListIngestTaskCenterListResponse(
        @Schema(description = "当前页任务列表，按更新时间倒序排列")
        List<OpenListIngestTaskCenterItemResponse> items,
        @Schema(description = "当前筛选与状态视图下的任务总数")
        Integer total,
        @Schema(description = "当前页码，从 1 开始")
        Integer page,
        @Schema(description = "每页条数")
        @JsonProperty("page_size")
        Integer pageSize,
        @Schema(description = "当前产品、来源和关键词条件下的全部任务数量")
        @JsonProperty("all_count")
        Integer allCount,
        @Schema(description = "当前产品、来源和关键词条件下的进行中任务数量")
        @JsonProperty("in_progress_count")
        Integer inProgressCount,
        @Schema(description = "当前产品、来源和关键词条件下的需要处理任务数量")
        @JsonProperty("needs_attention_count")
        Integer needsAttentionCount,
        @Schema(description = "当前产品、来源和关键词条件下的已完成任务数量")
        @JsonProperty("succeeded_count")
        Integer succeededCount
) {
}
