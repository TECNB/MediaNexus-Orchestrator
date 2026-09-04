package com.medianexus.orchestrator.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("javdb_automation_ledger")
public class JavdbAutomationLedger {

    @TableId(type = IdType.INPUT)
    private String id;
    private String code;
    private String selectedInfohash;
    private String selectedMagnet;
    private String adultTaskId;
    private String runId;
    private LocalDateTime submittedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getSelectedInfohash() { return selectedInfohash; }
    public void setSelectedInfohash(String selectedInfohash) { this.selectedInfohash = selectedInfohash; }
    public String getSelectedMagnet() { return selectedMagnet; }
    public void setSelectedMagnet(String selectedMagnet) { this.selectedMagnet = selectedMagnet; }
    public String getAdultTaskId() { return adultTaskId; }
    public void setAdultTaskId(String adultTaskId) { this.adultTaskId = adultTaskId; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(LocalDateTime submittedAt) { this.submittedAt = submittedAt; }
}
