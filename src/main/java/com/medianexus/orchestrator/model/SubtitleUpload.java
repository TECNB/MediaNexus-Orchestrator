package com.medianexus.orchestrator.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("subtitle_uploads")
public class SubtitleUpload {

    @TableId(type = IdType.INPUT)
    private String id;

    private Long createdByUserId;

    private String mediaType;

    private String status;

    private String stage;

    private String title;

    private String originalTitle;

    private Integer year;

    private Integer seasonNumber;

    private String targetPath;

    private String selectedVideoName;

    private String sourceFileName;

    private Long sourceSize;

    private String sourceSha256;

    private Integer fileCount;

    private Boolean overwriteEnabled;

    private String fileManifest;

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

    public String getMediaType() {
        return mediaType;
    }

    public void setMediaType(String mediaType) {
        this.mediaType = mediaType;
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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getOriginalTitle() {
        return originalTitle;
    }

    public void setOriginalTitle(String originalTitle) {
        this.originalTitle = originalTitle;
    }

    public Integer getYear() {
        return year;
    }

    public void setYear(Integer year) {
        this.year = year;
    }

    public Integer getSeasonNumber() {
        return seasonNumber;
    }

    public void setSeasonNumber(Integer seasonNumber) {
        this.seasonNumber = seasonNumber;
    }

    public String getTargetPath() {
        return targetPath;
    }

    public void setTargetPath(String targetPath) {
        this.targetPath = targetPath;
    }

    public String getSelectedVideoName() {
        return selectedVideoName;
    }

    public void setSelectedVideoName(String selectedVideoName) {
        this.selectedVideoName = selectedVideoName;
    }

    public String getSourceFileName() {
        return sourceFileName;
    }

    public void setSourceFileName(String sourceFileName) {
        this.sourceFileName = sourceFileName;
    }

    public Long getSourceSize() {
        return sourceSize;
    }

    public void setSourceSize(Long sourceSize) {
        this.sourceSize = sourceSize;
    }

    public String getSourceSha256() {
        return sourceSha256;
    }

    public void setSourceSha256(String sourceSha256) {
        this.sourceSha256 = sourceSha256;
    }

    public Integer getFileCount() {
        return fileCount;
    }

    public void setFileCount(Integer fileCount) {
        this.fileCount = fileCount;
    }

    public Boolean getOverwriteEnabled() {
        return overwriteEnabled;
    }

    public void setOverwriteEnabled(Boolean overwriteEnabled) {
        this.overwriteEnabled = overwriteEnabled;
    }

    public String getFileManifest() {
        return fileManifest;
    }

    public void setFileManifest(String fileManifest) {
        this.fileManifest = fileManifest;
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
