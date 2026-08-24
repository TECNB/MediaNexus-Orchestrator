package com.medianexus.orchestrator.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("quark_ingest_tasks")
public class QuarkIngestTask {

    @TableId(type = IdType.INPUT)
    private String id;
    private Long createdByUserId;
    private String mediaType;
    private String title;
    private String status;
    private String stage;
    private String taskNames;
    private String savePath;
    private Boolean immediateExecutionStarted;
    private Integer createdTaskCount;
    private Integer plannedTaskCount;
    private String message;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Long getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(Long createdByUserId) { this.createdByUserId = createdByUserId; }
    public String getMediaType() { return mediaType; }
    public void setMediaType(String mediaType) { this.mediaType = mediaType; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public String getTaskNames() { return taskNames; }
    public void setTaskNames(String taskNames) { this.taskNames = taskNames; }
    public String getSavePath() { return savePath; }
    public void setSavePath(String savePath) { this.savePath = savePath; }
    public Boolean getImmediateExecutionStarted() { return immediateExecutionStarted; }
    public void setImmediateExecutionStarted(Boolean immediateExecutionStarted) { this.immediateExecutionStarted = immediateExecutionStarted; }
    public Integer getCreatedTaskCount() { return createdTaskCount; }
    public void setCreatedTaskCount(Integer createdTaskCount) { this.createdTaskCount = createdTaskCount; }
    public Integer getPlannedTaskCount() { return plannedTaskCount; }
    public void setPlannedTaskCount(Integer plannedTaskCount) { this.plannedTaskCount = plannedTaskCount; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
