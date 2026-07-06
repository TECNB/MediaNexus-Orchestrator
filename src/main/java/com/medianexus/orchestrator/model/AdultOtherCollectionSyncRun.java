package com.medianexus.orchestrator.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("adult_other_collection_sync_runs")
public class AdultOtherCollectionSyncRun {

    @TableId(type = IdType.INPUT)
    private String id;

    private Long createdByUserId;

    private String mode;

    private String status;

    private Integer minItemCount;

    private String sourceFolderPath;

    private Integer totalItemCount;

    private Integer groupedItemCount;

    private Integer skippedItemCount;

    private Integer groupCount;

    private Integer eligibleGroupCount;

    private Integer createdCollectionCount;

    private Integer updatedCollectionCount;

    private Integer unchangedCollectionCount;

    private Integer deletedCollectionCount;

    private Integer reviewCollectionCount;

    private Integer itemAddCount;

    private Integer observedItemCount;

    private Integer observedGroupCount;

    private String errorMessage;

    private LocalDateTime startedAt;

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

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getMinItemCount() {
        return minItemCount;
    }

    public void setMinItemCount(Integer minItemCount) {
        this.minItemCount = minItemCount;
    }

    public String getSourceFolderPath() {
        return sourceFolderPath;
    }

    public void setSourceFolderPath(String sourceFolderPath) {
        this.sourceFolderPath = sourceFolderPath;
    }

    public Integer getTotalItemCount() {
        return totalItemCount;
    }

    public void setTotalItemCount(Integer totalItemCount) {
        this.totalItemCount = totalItemCount;
    }

    public Integer getGroupedItemCount() {
        return groupedItemCount;
    }

    public void setGroupedItemCount(Integer groupedItemCount) {
        this.groupedItemCount = groupedItemCount;
    }

    public Integer getSkippedItemCount() {
        return skippedItemCount;
    }

    public void setSkippedItemCount(Integer skippedItemCount) {
        this.skippedItemCount = skippedItemCount;
    }

    public Integer getGroupCount() {
        return groupCount;
    }

    public void setGroupCount(Integer groupCount) {
        this.groupCount = groupCount;
    }

    public Integer getEligibleGroupCount() {
        return eligibleGroupCount;
    }

    public void setEligibleGroupCount(Integer eligibleGroupCount) {
        this.eligibleGroupCount = eligibleGroupCount;
    }

    public Integer getCreatedCollectionCount() {
        return createdCollectionCount;
    }

    public void setCreatedCollectionCount(Integer createdCollectionCount) {
        this.createdCollectionCount = createdCollectionCount;
    }

    public Integer getUpdatedCollectionCount() {
        return updatedCollectionCount;
    }

    public void setUpdatedCollectionCount(Integer updatedCollectionCount) {
        this.updatedCollectionCount = updatedCollectionCount;
    }

    public Integer getUnchangedCollectionCount() {
        return unchangedCollectionCount;
    }

    public void setUnchangedCollectionCount(Integer unchangedCollectionCount) {
        this.unchangedCollectionCount = unchangedCollectionCount;
    }

    public Integer getDeletedCollectionCount() {
        return deletedCollectionCount;
    }

    public void setDeletedCollectionCount(Integer deletedCollectionCount) {
        this.deletedCollectionCount = deletedCollectionCount;
    }

    public Integer getReviewCollectionCount() {
        return reviewCollectionCount;
    }

    public void setReviewCollectionCount(Integer reviewCollectionCount) {
        this.reviewCollectionCount = reviewCollectionCount;
    }

    public Integer getItemAddCount() {
        return itemAddCount;
    }

    public void setItemAddCount(Integer itemAddCount) {
        this.itemAddCount = itemAddCount;
    }

    public Integer getObservedItemCount() {
        return observedItemCount;
    }

    public void setObservedItemCount(Integer observedItemCount) {
        this.observedItemCount = observedItemCount;
    }

    public Integer getObservedGroupCount() {
        return observedGroupCount;
    }

    public void setObservedGroupCount(Integer observedGroupCount) {
        this.observedGroupCount = observedGroupCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }
}
