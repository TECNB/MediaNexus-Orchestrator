package com.medianexus.orchestrator.integration.emby;

import java.util.Set;

/**
 * 只读电影身份快照，值相等用于写前复核。repairAllowed 仅表示上游证据完整且未锁定；
 * 调用方仍须核对准确媒体路径集合及预期身份，不能将其视为独立写入授权。
 */
public record EmbyMovieIdentitySnapshot(
        String itemId,
        String path,
        String tmdbId,
        Integer year,
        Set<String> mediaPaths,
        boolean repairAllowed,
        String revisionEvidence
) {
    public EmbyMovieIdentitySnapshot {
        mediaPaths = Set.copyOf(mediaPaths);
    }
}
