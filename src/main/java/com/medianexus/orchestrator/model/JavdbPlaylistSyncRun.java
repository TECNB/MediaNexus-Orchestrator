package com.medianexus.orchestrator.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("javdb_playlist_sync_runs")
public class JavdbPlaylistSyncRun {

    @TableId(type = IdType.INPUT)
    private String id;
    private String triggerType;
    private String status;
    private Integer desiredCount;
    private Integer addedCount;
    private Integer existingCount;
    private Integer waitingCount;
    private Integer failedCount;
    private String detailJson;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getDesiredCount() { return desiredCount; }
    public void setDesiredCount(Integer desiredCount) { this.desiredCount = desiredCount; }
    public Integer getAddedCount() { return addedCount; }
    public void setAddedCount(Integer addedCount) { this.addedCount = addedCount; }
    public Integer getExistingCount() { return existingCount; }
    public void setExistingCount(Integer existingCount) { this.existingCount = existingCount; }
    public Integer getWaitingCount() { return waitingCount; }
    public void setWaitingCount(Integer waitingCount) { this.waitingCount = waitingCount; }
    public Integer getFailedCount() { return failedCount; }
    public void setFailedCount(Integer failedCount) { this.failedCount = failedCount; }
    public String getDetailJson() { return detailJson; }
    public void setDetailJson(String detailJson) { this.detailJson = detailJson; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
}
