package com.medianexus.orchestrator.dto.resources.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "种子文件表的正片覆盖辅助信息，不保证视频内容或可播放性")
public record ReleaseEpisodeCoverageResponse(
        @Schema(description = "核验状态：CHECKING 排队或核验中、VERIFIED 已核验、UNVERIFIED 未核验") String status,
        @Schema(description = "唯一正片集号命中数；非 VERIFIED 时为 null", nullable = true) Integer matched,
        @Schema(description = "TMDB 目标季度预期正片集数；非 VERIFIED 时为 null", nullable = true) Integer expected
) {
}
