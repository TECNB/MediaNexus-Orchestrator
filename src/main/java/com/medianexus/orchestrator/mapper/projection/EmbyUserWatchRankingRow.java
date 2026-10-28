package com.medianexus.orchestrator.mapper.projection;

import java.time.LocalDateTime;

public class EmbyUserWatchRankingRow {

    private String embyUserId;

    private String userName;

    private Long watchSeconds;

    private Long playCount;

    private LocalDateTime lastWatchedAt;

    private String lastItemName;
    private String lastItemId;
    private String lastMediaId;
    private String lastCategory;
    public String getLastItemId() { return lastItemId; }
    public void setLastItemId(String value) { lastItemId = value; }
    public String getLastMediaId() { return lastMediaId; }
    public void setLastMediaId(String value) { lastMediaId = value; }
    public String getLastCategory() { return lastCategory; }
    public void setLastCategory(String value) { lastCategory = value; }


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
