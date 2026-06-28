package com.medianexus.orchestrator.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("adult_magnet_ingest_tasks")
public class AdultMagnetIngestTask {

    @TableId(type = IdType.INPUT)
    private String id;

    private Long createdByUserId;

    private String category;

    private String status;

    private String stage;

    private String dateFolder;

    private String targetPath;

    private String magnetHashes;

    private String openlistTaskIds;

    private Integer magnetCount;

    private Integer submittedCount;

    private Integer succeededCount;

    private Integer failedCount;

    private Integer duplicateCount;

    private Integer keptCount;

    private Integer deletedCount;

    private String errorMessage;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime finishedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Long getCreatedByUserId() {
        return createdByUserId;
    }

    public void setCreatedByUserId(Long createdByUserId) {
        this.createdByUserId = createdByUserId;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public String getDateFolder() {
        return dateFolder;
    }

    public void setDateFolder(String dateFolder) {
        this.dateFolder = dateFolder;
    }

    public String getTargetPath() {
        return targetPath;
    }

    public void setTargetPath(String targetPath) {
        this.targetPath = targetPath;
    }

    public String getMagnetHashes() {
        return magnetHashes;
    }

    public void setMagnetHashes(String magnetHashes) {
        this.magnetHashes = magnetHashes;
    }

    public String getOpenlistTaskIds() {
        return openlistTaskIds;
    }

    public void setOpenlistTaskIds(String openlistTaskIds) {
        this.openlistTaskIds = openlistTaskIds;
    }

    public Integer getMagnetCount() {
        return magnetCount;
    }

    public void setMagnetCount(Integer magnetCount) {
        this.magnetCount = magnetCount;
    }

    public Integer getSubmittedCount() {
        return submittedCount;
    }

    public void setSubmittedCount(Integer submittedCount) {
        this.submittedCount = submittedCount;
    }

    public Integer getSucceededCount() {
        return succeededCount;
    }

    public void setSucceededCount(Integer succeededCount) {
        this.succeededCount = succeededCount;
    }

    public Integer getFailedCount() {
        return failedCount;
    }

    public void setFailedCount(Integer failedCount) {
        this.failedCount = failedCount;
    }

    public Integer getDuplicateCount() {
        return duplicateCount;
    }

    public void setDuplicateCount(Integer duplicateCount) {
        this.duplicateCount = duplicateCount;
    }

    public Integer getKeptCount() {
        return keptCount;
    }

    public void setKeptCount(Integer keptCount) {
        this.keptCount = keptCount;
    }

    public Integer getDeletedCount() {
        return deletedCount;
    }

    public void setDeletedCount(Integer deletedCount) {
        this.deletedCount = deletedCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }
}
