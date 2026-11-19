package com.medianexus.orchestrator.dto.resources.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "文件表目标范围与安全整理证据；画质及版本仅为文件名声明")
public record ReleaseFileEvidence(
        @Schema(description = "RECOMMENDABLE 可推荐、NEEDS_PROCESSING 包含目标待处理、UNSUITABLE 不适合目标、UNVERIFIED 未核验") String status,
        @Schema(description = "判断理由及执行缺口") String reason,
        @JsonProperty("total_bytes") @Schema(description = "整包大小；未取得文件表时为 null", nullable = true) Long totalBytes,
        @JsonProperty("target_bytes") @Schema(description = "已识别目标正片大小；未识别时为 null", nullable = true) Long targetBytes,
        @JsonProperty("target_files") @Schema(description = "已识别目标正片文件数；未核验时为 null，不代表 TMDB 精确齐集", nullable = true) Integer targetFiles,
        @Schema(description = "是否含允许的尝鲜或抢先版") boolean preview,
        @Schema(description = "是否存在明确枪版证据") boolean camera
) {
    public static ReleaseFileEvidence unverified(String reason) {
        return new ReleaseFileEvidence("UNVERIFIED", reason, null, null, null, false, false);
    }
}
