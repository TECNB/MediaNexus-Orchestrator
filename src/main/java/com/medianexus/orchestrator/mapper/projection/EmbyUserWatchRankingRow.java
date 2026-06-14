package com.medianexus.orchestrator.mapper.projection;

import java.time.LocalDateTime;

public class EmbyUserWatchRankingRow {

    private String embyUserId;

    private String userName;

    private Long watchSeconds;

    private Long playCount;

    private LocalDateTime lastWatchedAt;

    private String lastItemName;

    public String getEmbyUserId() {
        return embyUserId;
    }

    public void setEmbyUserId(String embyUserId) {
        this.embyUserId = embyUserId;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public Long getWatchSeconds() {
        return watchSeconds;
    }

    public void setWatchSeconds(Long watchSeconds) {
        this.watchSeconds = watchSeconds;
    }

    public Long getPlayCount() {
        return playCount;
    }

    public void setPlayCount(Long playCount) {
        this.playCount = playCount;
    }

    public LocalDateTime getLastWatchedAt() {
        return lastWatchedAt;
    }

    public void setLastWatchedAt(LocalDateTime lastWatchedAt) {
        this.lastWatchedAt = lastWatchedAt;
    }

    public String getLastItemName() {
        return lastItemName;
    }

    public void setLastItemName(String lastItemName) {
        this.lastItemName = lastItemName;
    }
}
