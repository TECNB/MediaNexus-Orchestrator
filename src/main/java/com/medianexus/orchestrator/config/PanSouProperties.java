package com.medianexus.orchestrator.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "medianexus.pansou")
public class PanSouProperties {

    private String baseUrl;
    private String username;
    private String password;

    @NotNull
    @DurationMin(seconds = 1)
    private Duration timeout = Duration.ofSeconds(15);

    @Min(1)
    @Max(30)
    private int linkCheckLimit = 10;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getLinkCheckLimit() {
        return linkCheckLimit;
    }

    public void setLinkCheckLimit(int linkCheckLimit) {
        this.linkCheckLimit = linkCheckLimit;
    }
}
