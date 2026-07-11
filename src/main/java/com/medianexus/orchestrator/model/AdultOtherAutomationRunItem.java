package com.medianexus.orchestrator.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("adult_other_automation_run_items")
public class AdultOtherAutomationRunItem {

    @TableId(type = IdType.INPUT)
    private String id;
    private String runId;
    private String embyItemId;
    private String itemName;
    private String itemPath;
    private String collectionName;
    private Boolean primaryBefore;
    private Boolean refreshRequested;
    private Boolean primaryAfter;
    private String status;
    private String message;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getEmbyItemId() { return embyItemId; }
    public void setEmbyItemId(String embyItemId) { this.embyItemId = embyItemId; }
    public String getItemName() { return itemName; }
    public void setItemName(String itemName) { this.itemName = itemName; }
    public String getItemPath() { return itemPath; }
    public void setItemPath(String itemPath) { this.itemPath = itemPath; }
    public String getCollectionName() { return collectionName; }
    public void setCollectionName(String collectionName) { this.collectionName = collectionName; }
    public Boolean getPrimaryBefore() { return primaryBefore; }
    public void setPrimaryBefore(Boolean primaryBefore) { this.primaryBefore = primaryBefore; }
    public Boolean getRefreshRequested() { return refreshRequested; }
    public void setRefreshRequested(Boolean refreshRequested) { this.refreshRequested = refreshRequested; }
    public Boolean getPrimaryAfter() { return primaryAfter; }
    public void setPrimaryAfter(Boolean primaryAfter) { this.primaryAfter = primaryAfter; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
