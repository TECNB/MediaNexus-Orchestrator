package com.medianexus.orchestrator.mapper.projection;

import java.time.LocalDateTime;

public class EmbyWatchSummaryRow {

    private Long activeUserCount;

    private Long totalWatchSeconds;

    private Long totalPlayCount;

    private LocalDateTime lastWatchedAt;

    public Long getActiveUserCount() {
        return activeUserCount;
    }

    public void setActiveUserCount(Long activeUserCount) {
        this.activeUserCount = activeUserCount;
    }

    public Long getTotalWatchSeconds() {
        return totalWatchSeconds;
    }

    public void setTotalWatchSeconds(Long totalWatchSeconds) {
        this.totalWatchSeconds = totalWatchSeconds;
    }

    public Long getTotalPlayCount() {
        return totalPlayCount;
    }

    public void setTotalPlayCount(Long totalPlayCount) {
        this.totalPlayCount = totalPlayCount;
    }

    public LocalDateTime getLastWatchedAt() {
        return lastWatchedAt;
    }

    public void setLastWatchedAt(LocalDateTime lastWatchedAt) {
        this.lastWatchedAt = lastWatchedAt;
    }
}
