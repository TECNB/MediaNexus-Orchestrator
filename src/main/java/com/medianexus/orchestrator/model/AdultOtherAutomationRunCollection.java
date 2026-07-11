package com.medianexus.orchestrator.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("adult_other_automation_run_collections")
public class AdultOtherAutomationRunCollection {

    @TableId(type = IdType.INPUT)
    private String id;
    private String runId;
    private String embyCollectionId;
    private String collectionName;
    private String action;
    private Integer addedItemCount;
    private Boolean imageReady;
    private String status;
    private String message;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getEmbyCollectionId() { return embyCollectionId; }
    public void setEmbyCollectionId(String embyCollectionId) { this.embyCollectionId = embyCollectionId; }
    public String getCollectionName() { return collectionName; }
    public void setCollectionName(String collectionName) { this.collectionName = collectionName; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public Integer getAddedItemCount() { return addedItemCount; }
    public void setAddedItemCount(Integer addedItemCount) { this.addedItemCount = addedItemCount; }
    public Boolean getImageReady() { return imageReady; }
    public void setImageReady(Boolean imageReady) { this.imageReady = imageReady; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
