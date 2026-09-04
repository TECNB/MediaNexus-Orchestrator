package com.medianexus.orchestrator.dto.javdb.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "JAVDB 自动化运行影片明细")
public record JavdbAutomationRunItemResponse(
        String code,
        String title,
        @JsonProperty("detail_url") String detailUrl,
        List<JavdbRankingAppearanceResponse> appearances,
        @Schema(description = "CROSS_RANK_DUPLICATE、ALREADY_IN_EMBY、HISTORY_SUBMITTED、ADULT_IN_PROGRESS、SUBMITTED、NO_MAGNET、DETAIL_FAILED 或 SUBMIT_FAILED")
        String status,
        String reason,
        List<JavdbMagnetCandidateResponse> candidates,
        @JsonProperty("selected_infohash") String selectedInfohash,
        @JsonProperty("selected_magnet") String selectedMagnet,
        @JsonProperty("selected_reason") String selectedReason,
        @JsonProperty("adult_task_id") String adultTaskId,
        @JsonProperty("error_message") String errorMessage
) {
}
