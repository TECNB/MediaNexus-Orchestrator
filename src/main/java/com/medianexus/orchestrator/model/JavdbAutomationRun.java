package com.medianexus.orchestrator.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("javdb_automation_runs")
public class JavdbAutomationRun {

    @TableId(type = IdType.INPUT)
    private String id;
    private String triggerType;
    private Long triggeredByUserId;
    private String executionMode;
    private String status;
    private String stage;
    private String configSnapshot;
    private Integer rankingEntries;
    private Integer uniqueMovies;
    private Integer duplicateEntriesRemoved;
    private Integer alreadyInEmby;
    private Integer historyDuplicates;
    private Integer activeDuplicates;
    private Integer remainingMovies;
    private Integer submittedCount;
    private Integer adultTaskCount;
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
    public String getConfigSnapshot() { return configSnapshot; }
    public void setConfigSnapshot(String configSnapshot) { this.configSnapshot = configSnapshot; }
    public Integer getRankingEntries() { return rankingEntries; }
    public void setRankingEntries(Integer rankingEntries) { this.rankingEntries = rankingEntries; }
    public Integer getUniqueMovies() { return uniqueMovies; }
    public void setUniqueMovies(Integer uniqueMovies) { this.uniqueMovies = uniqueMovies; }
    public Integer getDuplicateEntriesRemoved() { return duplicateEntriesRemoved; }
    public void setDuplicateEntriesRemoved(Integer duplicateEntriesRemoved) { this.duplicateEntriesRemoved = duplicateEntriesRemoved; }
    public Integer getAlreadyInEmby() { return alreadyInEmby; }
    public void setAlreadyInEmby(Integer alreadyInEmby) { this.alreadyInEmby = alreadyInEmby; }
    public Integer getHistoryDuplicates() { return historyDuplicates; }
    public void setHistoryDuplicates(Integer historyDuplicates) { this.historyDuplicates = historyDuplicates; }
    public Integer getActiveDuplicates() { return activeDuplicates; }
    public void setActiveDuplicates(Integer activeDuplicates) { this.activeDuplicates = activeDuplicates; }
    public Integer getRemainingMovies() { return remainingMovies; }
    public void setRemainingMovies(Integer remainingMovies) { this.remainingMovies = remainingMovies; }
    public Integer getSubmittedCount() { return submittedCount; }
    public void setSubmittedCount(Integer submittedCount) { this.submittedCount = submittedCount; }
    public Integer getAdultTaskCount() { return adultTaskCount; }
    public void setAdultTaskCount(Integer adultTaskCount) { this.adultTaskCount = adultTaskCount; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
