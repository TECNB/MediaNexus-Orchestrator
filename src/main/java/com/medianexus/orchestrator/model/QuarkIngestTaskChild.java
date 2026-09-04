package com.medianexus.orchestrator.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** One season/version execution unit under a Quark aggregate task. */
@TableName("quark_ingest_task_children")
public class QuarkIngestTaskChild {

    @TableId(type = IdType.INPUT)
    private String id;
    private String aggregateTaskId;
    private String taskName;
    private String sourceUrl;
    private String savePath;
    private String pattern;
    @TableField("replace_rule")
    private String replace;
    private String versionLabel;
    private String status;
    private String failureReason;
    private String taskReference;
    private Integer seasonNumber;
    private Integer retryCount;
    private Boolean subscriptionEnabled;
    private Integer plannedFileCount;
    private Integer processedFileCount;
    private Integer renamedFileCount;
    private Integer ignoredFileCount;
    private Integer failedFileCount;
    private Integer unknownFileCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAggregateTaskId() { return aggregateTaskId; }
    public void setAggregateTaskId(String aggregateTaskId) { this.aggregateTaskId = aggregateTaskId; }
    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
    public String getSavePath() { return savePath; }
    public void setSavePath(String savePath) { this.savePath = savePath; }
    public String getPattern() { return pattern; }
    public void setPattern(String pattern) { this.pattern = pattern; }
    public String getReplace() { return replace; }
    public void setReplace(String replace) { this.replace = replace; }
    public String getVersionLabel() { return versionLabel; }
    public void setVersionLabel(String versionLabel) { this.versionLabel = versionLabel; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public String getTaskReference() { return taskReference; }
    public void setTaskReference(String taskReference) { this.taskReference = taskReference; }
    public Integer getSeasonNumber() { return seasonNumber; }
    public void setSeasonNumber(Integer seasonNumber) { this.seasonNumber = seasonNumber; }
    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
    public Boolean getSubscriptionEnabled() { return subscriptionEnabled; }
    public void setSubscriptionEnabled(Boolean subscriptionEnabled) { this.subscriptionEnabled = subscriptionEnabled; }
    public Integer getPlannedFileCount() { return plannedFileCount; }
    public void setPlannedFileCount(Integer plannedFileCount) { this.plannedFileCount = plannedFileCount; }
    public Integer getProcessedFileCount() { return processedFileCount; }
    public void setProcessedFileCount(Integer processedFileCount) { this.processedFileCount = processedFileCount; }
    public Integer getRenamedFileCount() { return renamedFileCount; }
    public void setRenamedFileCount(Integer renamedFileCount) { this.renamedFileCount = renamedFileCount; }
    public Integer getIgnoredFileCount() { return ignoredFileCount; }
    public void setIgnoredFileCount(Integer ignoredFileCount) { this.ignoredFileCount = ignoredFileCount; }
    public Integer getFailedFileCount() { return failedFileCount; }
    public void setFailedFileCount(Integer failedFileCount) { this.failedFileCount = failedFileCount; }
    public Integer getUnknownFileCount() { return unknownFileCount; }
    public void setUnknownFileCount(Integer unknownFileCount) { this.unknownFileCount = unknownFileCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
