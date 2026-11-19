package com.medianexus.orchestrator.service;

import java.util.List;

public record ReleaseIngestMetadata(
        String sourceType,
        String releaseTitle,
        String releaseIndexer,
        Long releaseSize,
        Integer releaseIndexerId,
        String releaseGuid,
        List<String> resolutionTags,
        List<String> dynamicRangeTags,
        boolean acceptUnverifiedRisk
) {

    public ReleaseIngestMetadata(String sourceType, String releaseTitle, String releaseIndexer, Long releaseSize,
            Integer releaseIndexerId, String releaseGuid, List<String> resolutionTags, List<String> dynamicRangeTags) {
        this(sourceType, releaseTitle, releaseIndexer, releaseSize, releaseIndexerId, releaseGuid,
                resolutionTags, dynamicRangeTags, false);
    }

    /** 许可仅覆盖尚未取得的证据；已知冲突及执行不安全始终由共同受理检查拒绝。 */
    public ReleaseIngestMetadata withUnverifiedRisk(boolean accepted) {
        return new ReleaseIngestMetadata(sourceType, releaseTitle, releaseIndexer, releaseSize,
                releaseIndexerId, releaseGuid, resolutionTags, dynamicRangeTags, accepted);
    }

    public static ReleaseIngestMetadata manual() {
        return new ReleaseIngestMetadata(
                "MANUAL_MAGNET",
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of()
        );
    }
}
