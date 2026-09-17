package com.medianexus.orchestrator.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("telegram_automation_runs")
public class TelegramAutomationRun {

    @TableId(type = IdType.INPUT)
    private String id;
    private String triggerType;
    private Long triggeredByUserId;
    private String executionMode;
    private String status;
    private String stage;
    private Integer channelCount;
    private Integer succeededChannelCount;
    private Integer failedChannelCount;
    private Integer selectedResourceCount;
    private Integer duplicateResourceCount;
    private Integer forwardedResourceCount;
    private Integer forwardedMessageCount;
    private String resultJson;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }
    public Long getTriggeredByUserId() { return triggeredByUserId; }
    public void setTriggeredByUserId(Long triggeredByUserId) { this.triggeredByUserId = triggeredByUserId; }
    public String getExecutionMode() { return executionMode; }
    public void setExecutionMode(String executionMode) { this.executionMode = executionMode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public Integer getChannelCount() { return channelCount; }
    public void setChannelCount(Integer channelCount) { this.channelCount = channelCount; }
    public Integer getSucceededChannelCount() { return succeededChannelCount; }
    public void setSucceededChannelCount(Integer succeededChannelCount) { this.succeededChannelCount = succeededChannelCount; }
    public Integer getFailedChannelCount() { return failedChannelCount; }
    public void setFailedChannelCount(Integer failedChannelCount) { this.failedChannelCount = failedChannelCount; }
    public Integer getSelectedResourceCount() { return selectedResourceCount; }
    public void setSelectedResourceCount(Integer selectedResourceCount) { this.selectedResourceCount = selectedResourceCount; }
    public Integer getDuplicateResourceCount() { return duplicateResourceCount; }
    public void setDuplicateResourceCount(Integer duplicateResourceCount) { this.duplicateResourceCount = duplicateResourceCount; }
    public Integer getForwardedResourceCount() { return forwardedResourceCount; }
    public void setForwardedResourceCount(Integer forwardedResourceCount) { this.forwardedResourceCount = forwardedResourceCount; }
    public Integer getForwardedMessageCount() { return forwardedMessageCount; }
    public void setForwardedMessageCount(Integer forwardedMessageCount) { this.forwardedMessageCount = forwardedMessageCount; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
