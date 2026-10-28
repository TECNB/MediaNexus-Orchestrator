package com.medianexus.orchestrator.mapper.projection;

import java.time.LocalDateTime;

public class EmbyWatchSummaryRow {

    private Long calendarSessionCount;
    private Long legacySessionCount;
    private Long totalWorkCount;
    public Long getCalendarSessionCount() { return calendarSessionCount; }
    public void setCalendarSessionCount(Long value) { calendarSessionCount = value; }
    public Long getLegacySessionCount() { return legacySessionCount; }
    public void setLegacySessionCount(Long value) { legacySessionCount = value; }
    public Long getTotalWorkCount() { return totalWorkCount; }
    public void setTotalWorkCount(Long value) { totalWorkCount = value; }

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
