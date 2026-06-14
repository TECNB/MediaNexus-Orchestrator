package com.medianexus.orchestrator.mapper.projection;

import java.time.LocalDateTime;

public class EmbyMediaWatchRankingRow {

    private String mediaId;

    private String title;

    private Long watchSeconds;

    private Long playCount;

    private LocalDateTime lastPlayedAt;

    public String getMediaId() {
        return mediaId;
    }

    public void setMediaId(String mediaId) {
        this.mediaId = mediaId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
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

    public LocalDateTime getLastPlayedAt() {
        return lastPlayedAt;
    }

    public void setLastPlayedAt(LocalDateTime lastPlayedAt) {
        this.lastPlayedAt = lastPlayedAt;
    }
}
