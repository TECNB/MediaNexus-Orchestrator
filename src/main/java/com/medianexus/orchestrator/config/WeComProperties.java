package com.medianexus.orchestrator.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 企微通知可选配置；成员、凭据和站点地址仅在发送时校验。 */
@Validated
@ConfigurationProperties(prefix = "medianexus.wecom")
public class WeComProperties {

    private boolean enabled;
    @NotBlank
    private String apiBaseUrl;
    private String corpId;
    private Long agentId;
    private String appSecret;
    private String userId;
    private String frontendBaseUrl;
    @NotNull
    @DurationMin(seconds = 1)
    private Duration timeout;

    private boolean ingestEnabled;
    private String ingestToken;
    private String ingestAesKey;

    public boolean isIngestEnabled() { return ingestEnabled; }
    public void setIngestEnabled(boolean value) { ingestEnabled = value; }
    public String getIngestToken() { return ingestToken; }
    public void setIngestToken(String value) { ingestToken = value; }
    public String getIngestAesKey() { return ingestAesKey; }
    public void setIngestAesKey(String value) { ingestAesKey = value; }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    public String getCorpId() {
        return corpId;
    }

    public void setCorpId(String corpId) {
        this.corpId = corpId;
    }

    public Long getAgentId() {
        return agentId;
    }

    public void setAgentId(Long agentId) {
        this.agentId = agentId;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public void setAppSecret(String appSecret) {
        this.appSecret = appSecret;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getFrontendBaseUrl() {
        return frontendBaseUrl;
    }

    public void setFrontendBaseUrl(String frontendBaseUrl) {
        this.frontendBaseUrl = frontendBaseUrl;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

}
