package com.medianexus.orchestrator.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("adult_other_collection_sync_groups")
public class AdultOtherCollectionSyncGroup {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String runId;

    private String collectionName;

    private String sourceFolderPath;

    private Integer itemCount;

    private Boolean eligible;

    private String action;

    private String embyCollectionId;

    private Integer addedItemCount;

    private String skipReason;

    private String sampleItemNamesJson;

    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getCollectionName() {
        return collectionName;
    }

    public void setCollectionName(String collectionName) {
        this.collectionName = collectionName;
    }

    public String getSourceFolderPath() {
        return sourceFolderPath;
    }

    public void setSourceFolderPath(String sourceFolderPath) {
        this.sourceFolderPath = sourceFolderPath;
    }

    public Integer getItemCount() {
        return itemCount;
    }

    public void setItemCount(Integer itemCount) {
        this.itemCount = itemCount;
    }

    public Boolean getEligible() {
        return eligible;
    }

    public void setEligible(Boolean eligible) {
        this.eligible = eligible;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getEmbyCollectionId() {
        return embyCollectionId;
    }

    public void setEmbyCollectionId(String embyCollectionId) {
        this.embyCollectionId = embyCollectionId;
    }

    public Integer getAddedItemCount() {
        return addedItemCount;
    }

    public void setAddedItemCount(Integer addedItemCount) {
        this.addedItemCount = addedItemCount;
    }

    public String getSkipReason() {
        return skipReason;
    }

    public void setSkipReason(String skipReason) {
        this.skipReason = skipReason;
    }

    public String getSampleItemNamesJson() {
        return sampleItemNamesJson;
    }

    public void setSampleItemNamesJson(String sampleItemNamesJson) {
        this.sampleItemNamesJson = sampleItemNamesJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
