package com.medianexus.orchestrator.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Locale;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "medianexus.radarr")
public class RadarrProperties {

    private String scheme = "http";
    private String host;
    private Integer port;
    private String apiKey;

    @NotNull
    @DurationMin(seconds = 1)
    private Duration timeout;

    public String getScheme() {
        return scheme;
    }

    public void setScheme(String scheme) {
        String normalized = scheme == null ? "" : scheme.trim().toLowerCase(Locale.ROOT);
        this.scheme = normalized.isEmpty() ? "http" : normalized;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}
