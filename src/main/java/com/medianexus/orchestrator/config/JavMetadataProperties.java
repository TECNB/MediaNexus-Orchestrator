package com.medianexus.orchestrator.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "medianexus.jav-metadata")
public class JavMetadataProperties {
    private boolean enabled;
    @NotBlank private String imageBaseUrl;
    @NotBlank private String legacyIngestRoot;
    @NotBlank private String legacyStrmRoot;
    @NotNull @DurationMin(seconds = 1) private Duration requestTimeout;
    @NotNull @DurationMin(seconds = 1) private Duration pollInterval;
    @NotNull @DurationMin(minutes = 1) private Duration completionTimeout;
    @Min(1) private int maxAttempts;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getImageBaseUrl() { return imageBaseUrl; }
    public void setImageBaseUrl(String imageBaseUrl) { this.imageBaseUrl = imageBaseUrl; }
    public String getLegacyIngestRoot() { return legacyIngestRoot; }
    public void setLegacyIngestRoot(String legacyIngestRoot) { this.legacyIngestRoot = legacyIngestRoot; }
    public String getLegacyStrmRoot() { return legacyStrmRoot; }
    public void setLegacyStrmRoot(String legacyStrmRoot) { this.legacyStrmRoot = legacyStrmRoot; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
    public Duration getPollInterval() { return pollInterval; }
    public void setPollInterval(Duration pollInterval) { this.pollInterval = pollInterval; }
    public Duration getCompletionTimeout() { return completionTimeout; }
    public void setCompletionTimeout(Duration completionTimeout) { this.completionTimeout = completionTimeout; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
}
