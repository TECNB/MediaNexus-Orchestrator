package com.medianexus.orchestrator.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** A durable execution attempt belonging to one Quark aggregate task. */
@TableName("quark_ingest_task_attempts")
public class QuarkIngestTaskAttempt {

    @TableId(type = IdType.INPUT)
    private String id;
    private String aggregateTaskId;
    private Integer attemptNo;
    private String triggerType;
    private String targetChildTaskIds;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private String message;
    private Long createdByUserId;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAggregateTaskId() { return aggregateTaskId; }
    public void setAggregateTaskId(String aggregateTaskId) { this.aggregateTaskId = aggregateTaskId; }
    public Integer getAttemptNo() { return attemptNo; }
    public void setAttemptNo(Integer attemptNo) { this.attemptNo = attemptNo; }
    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }
    public String getTargetChildTaskIds() { return targetChildTaskIds; }
    public void setTargetChildTaskIds(String targetChildTaskIds) { this.targetChildTaskIds = targetChildTaskIds; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getEndedAt() { return endedAt; }
    public void setEndedAt(LocalDateTime endedAt) { this.endedAt = endedAt; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Long getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(Long createdByUserId) { this.createdByUserId = createdByUserId; }
}
