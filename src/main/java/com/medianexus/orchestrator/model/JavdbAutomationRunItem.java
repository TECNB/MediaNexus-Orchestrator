package com.medianexus.orchestrator.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("javdb_automation_run_items")
public class JavdbAutomationRunItem {

    @TableId(type = IdType.INPUT)
    private String id;
    private String runId;
    private String code;
    private String title;
    private String detailUrl;
    private String appearancesJson;
    private String status;
    private String reason;
    private String candidatesJson;
    private String selectedInfohash;
    private String selectedMagnet;
    private String selectedReason;
    private String adultTaskId;
    private String errorMessage;
    private LocalDateTime createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDetailUrl() { return detailUrl; }
    public void setDetailUrl(String detailUrl) { this.detailUrl = detailUrl; }
    public String getAppearancesJson() { return appearancesJson; }
    public void setAppearancesJson(String appearancesJson) { this.appearancesJson = appearancesJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getCandidatesJson() { return candidatesJson; }
    public void setCandidatesJson(String candidatesJson) { this.candidatesJson = candidatesJson; }
    public String getSelectedInfohash() { return selectedInfohash; }
    public void setSelectedInfohash(String selectedInfohash) { this.selectedInfohash = selectedInfohash; }
    public String getSelectedMagnet() { return selectedMagnet; }
    public void setSelectedMagnet(String selectedMagnet) { this.selectedMagnet = selectedMagnet; }
    public String getSelectedReason() { return selectedReason; }
    public void setSelectedReason(String selectedReason) { this.selectedReason = selectedReason; }
    public String getAdultTaskId() { return adultTaskId; }
    public void setAdultTaskId(String adultTaskId) { this.adultTaskId = adultTaskId; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
