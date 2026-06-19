package com.medianexus.orchestrator.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("movie_magnet_ingest_tasks")
public class MovieMagnetIngestTask {

    @TableId(type = IdType.INPUT)
    private String id;

    private String status;

    private String stage;

    private String magnet;

    private String magnetHash;

    private String title;

    private String originalTitle;

    private Integer year;

    private String sourceType;

    private String releaseTitle;

    private String releaseIndexer;

    private Long releaseSize;

    private Integer releaseIndexerId;

    private String releaseGuid;

    private String resolutionTags;

    private String qualityTag;

    private String dynamicRangeTags;

    private String savePath;

    private String tempPath;

    private String openlistTaskId;

    private Long createdByUserId;

    private Integer organizedCount;

    private Integer skippedCount;

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

    public String getMagnet() {
        return magnet;
    }

    public void setMagnet(String magnet) {
        this.magnet = magnet;
    }

    public String getMagnetHash() {
        return magnetHash;
    }

    public void setMagnetHash(String magnetHash) {
        this.magnetHash = magnetHash;
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

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getReleaseTitle() {
        return releaseTitle;
    }

    public void setReleaseTitle(String releaseTitle) {
        this.releaseTitle = releaseTitle;
    }

    public String getReleaseIndexer() {
        return releaseIndexer;
    }

    public void setReleaseIndexer(String releaseIndexer) {
        this.releaseIndexer = releaseIndexer;
    }

    public Long getReleaseSize() {
        return releaseSize;
    }

    public void setReleaseSize(Long releaseSize) {
        this.releaseSize = releaseSize;
    }

    public Integer getReleaseIndexerId() {
        return releaseIndexerId;
    }

    public void setReleaseIndexerId(Integer releaseIndexerId) {
        this.releaseIndexerId = releaseIndexerId;
    }

    public String getReleaseGuid() {
        return releaseGuid;
    }

    public void setReleaseGuid(String releaseGuid) {
        this.releaseGuid = releaseGuid;
    }

    public String getResolutionTags() {
        return resolutionTags;
    }

    public void setResolutionTags(String resolutionTags) {
        this.resolutionTags = resolutionTags;
    }

    public String getQualityTag() {
        return qualityTag;
    }

    public void setQualityTag(String qualityTag) {
        this.qualityTag = qualityTag;
    }

    public String getDynamicRangeTags() {
        return dynamicRangeTags;
    }

    public void setDynamicRangeTags(String dynamicRangeTags) {
        this.dynamicRangeTags = dynamicRangeTags;
    }

    public String getSavePath() {
        return savePath;
    }

    public void setSavePath(String savePath) {
        this.savePath = savePath;
    }

    public String getTempPath() {
        return tempPath;
    }

    public void setTempPath(String tempPath) {
        this.tempPath = tempPath;
    }

    public String getOpenlistTaskId() {
        return openlistTaskId;
    }

    public void setOpenlistTaskId(String openlistTaskId) {
        this.openlistTaskId = openlistTaskId;
    }

    public Long getCreatedByUserId() {
        return createdByUserId;
    }

    public void setCreatedByUserId(Long createdByUserId) {
        this.createdByUserId = createdByUserId;
    }

    public Integer getOrganizedCount() {
        return organizedCount;
    }

    public void setOrganizedCount(Integer organizedCount) {
        this.organizedCount = organizedCount;
    }

    public Integer getSkippedCount() {
        return skippedCount;
    }

    public void setSkippedCount(Integer skippedCount) {
        this.skippedCount = skippedCount;
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
