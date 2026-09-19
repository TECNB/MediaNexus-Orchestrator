package com.medianexus.orchestrator.model;

public class JavdbPlaylistHistoryItem {
    private String code;
    private String status;
    private String adultTaskId;
    private String selectedMagnet;
    private String candidatesJson;
    private String appearancesJson;
    private String runId;
    private String configSnapshot;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getAdultTaskId() { return adultTaskId; }
    public void setAdultTaskId(String adultTaskId) { this.adultTaskId = adultTaskId; }
    public String getSelectedMagnet() { return selectedMagnet; }
    public void setSelectedMagnet(String selectedMagnet) { this.selectedMagnet = selectedMagnet; }
    public String getCandidatesJson() { return candidatesJson; }
    public void setCandidatesJson(String candidatesJson) { this.candidatesJson = candidatesJson; }
    public String getAppearancesJson() { return appearancesJson; }
    public void setAppearancesJson(String appearancesJson) { this.appearancesJson = appearancesJson; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getConfigSnapshot() { return configSnapshot; }
    public void setConfigSnapshot(String configSnapshot) { this.configSnapshot = configSnapshot; }
}
