package com.medianexus.orchestrator.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("media_deletion_tasks")
public class MediaDeletionTask {
    @TableId(type = IdType.INPUT)
    private String id;
    private String library;
    private String itemId;
    private String seasonId;
    private Integer seasonNumber;
    private String title;
    private String targetLabel;
    private String status;
    private String stage;
    private String sourcePaths;
    private String strmPaths;
    private String embyItemIds;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime finishedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getLibrary() { return library; }
    public void setLibrary(String library) { this.library = library; }
    public String getItemId() { return itemId; }
    public void setItemId(String itemId) { this.itemId = itemId; }
    public String getSeasonId() { return seasonId; }
    public void setSeasonId(String seasonId) { this.seasonId = seasonId; }
    public Integer getSeasonNumber() { return seasonNumber; }
    public void setSeasonNumber(Integer seasonNumber) { this.seasonNumber = seasonNumber; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getTargetLabel() { return targetLabel; }
    public void setTargetLabel(String targetLabel) { this.targetLabel = targetLabel; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public String getSourcePaths() { return sourcePaths; }
    public void setSourcePaths(String sourcePaths) { this.sourcePaths = sourcePaths; }
    public String getStrmPaths() { return strmPaths; }
    public void setStrmPaths(String strmPaths) { this.strmPaths = strmPaths; }
    public String getEmbyItemIds() { return embyItemIds; }
    public void setEmbyItemIds(String embyItemIds) { this.embyItemIds = embyItemIds; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
}
