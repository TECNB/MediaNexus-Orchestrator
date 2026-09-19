package com.medianexus.orchestrator.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("javdb_playlist_memberships")
public class JavdbPlaylistMembership {

    @TableId(type = IdType.INPUT)
    private String id;
    private String code;
    private String playlistKey;
    private String sourceRunId;
    private String adultTaskId;
    private Integer sourceRank;
    private Integer sourceYear;
    private String embyItemId;
    private String status;
    private String lastSyncRunId;
    private LocalDateTime syncedAt;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getPlaylistKey() { return playlistKey; }
    public void setPlaylistKey(String playlistKey) { this.playlistKey = playlistKey; }
    public String getSourceRunId() { return sourceRunId; }
    public void setSourceRunId(String sourceRunId) { this.sourceRunId = sourceRunId; }
    public String getAdultTaskId() { return adultTaskId; }
    public void setAdultTaskId(String adultTaskId) { this.adultTaskId = adultTaskId; }
    public Integer getSourceRank() { return sourceRank; }
    public void setSourceRank(Integer sourceRank) { this.sourceRank = sourceRank; }
    public Integer getSourceYear() { return sourceYear; }
    public void setSourceYear(Integer sourceYear) { this.sourceYear = sourceYear; }
    public String getEmbyItemId() { return embyItemId; }
    public void setEmbyItemId(String embyItemId) { this.embyItemId = embyItemId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getLastSyncRunId() { return lastSyncRunId; }
    public void setLastSyncRunId(String lastSyncRunId) { this.lastSyncRunId = lastSyncRunId; }
    public LocalDateTime getSyncedAt() { return syncedAt; }
    public void setSyncedAt(LocalDateTime syncedAt) { this.syncedAt = syncedAt; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
