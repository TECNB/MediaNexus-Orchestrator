package com.medianexus.orchestrator.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("adult_other_automation_runs")
public class AdultOtherAutomationRun {

    @TableId(type = IdType.INPUT)
    private String id;
    private String triggerType;
    private String status;
    private String stage;
    private Integer eventCount;
    private Integer targetItemCount;
    private Integer naturalPrimaryReadyCount;
    private Integer targetedRefreshCount;
    private Integer finalPrimaryReadyCount;
    private Integer finalPrimaryMissingCount;
    private Integer affectedCollectionCount;
    private Integer createdCollectionCount;
    private Integer updatedCollectionCount;
    private Integer collectionImageReadyCount;
    private Integer deletedCollectionCount;
    private String message;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public Integer getEventCount() { return eventCount; }
    public void setEventCount(Integer eventCount) { this.eventCount = eventCount; }
    public Integer getTargetItemCount() { return targetItemCount; }
    public void setTargetItemCount(Integer targetItemCount) { this.targetItemCount = targetItemCount; }
    public Integer getNaturalPrimaryReadyCount() { return naturalPrimaryReadyCount; }
    public void setNaturalPrimaryReadyCount(Integer naturalPrimaryReadyCount) { this.naturalPrimaryReadyCount = naturalPrimaryReadyCount; }
    public Integer getTargetedRefreshCount() { return targetedRefreshCount; }
    public void setTargetedRefreshCount(Integer targetedRefreshCount) { this.targetedRefreshCount = targetedRefreshCount; }
    public Integer getFinalPrimaryReadyCount() { return finalPrimaryReadyCount; }
    public void setFinalPrimaryReadyCount(Integer finalPrimaryReadyCount) { this.finalPrimaryReadyCount = finalPrimaryReadyCount; }
    public Integer getFinalPrimaryMissingCount() { return finalPrimaryMissingCount; }
    public void setFinalPrimaryMissingCount(Integer finalPrimaryMissingCount) { this.finalPrimaryMissingCount = finalPrimaryMissingCount; }
    public Integer getAffectedCollectionCount() { return affectedCollectionCount; }
    public void setAffectedCollectionCount(Integer affectedCollectionCount) { this.affectedCollectionCount = affectedCollectionCount; }
    public Integer getCreatedCollectionCount() { return createdCollectionCount; }
    public void setCreatedCollectionCount(Integer createdCollectionCount) { this.createdCollectionCount = createdCollectionCount; }
    public Integer getUpdatedCollectionCount() { return updatedCollectionCount; }
    public void setUpdatedCollectionCount(Integer updatedCollectionCount) { this.updatedCollectionCount = updatedCollectionCount; }
    public Integer getCollectionImageReadyCount() { return collectionImageReadyCount; }
    public void setCollectionImageReadyCount(Integer collectionImageReadyCount) { this.collectionImageReadyCount = collectionImageReadyCount; }
    public Integer getDeletedCollectionCount() { return deletedCollectionCount; }
    public void setDeletedCollectionCount(Integer deletedCollectionCount) { this.deletedCollectionCount = deletedCollectionCount; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
}
