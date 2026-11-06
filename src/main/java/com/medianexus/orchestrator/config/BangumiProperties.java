package com.medianexus.orchestrator.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "medianexus.bangumi")
public class BangumiProperties {
    @NotBlank
    private String baseUrl;
    @NotBlank
    private String userAgent;
    @NotNull
    @DurationMin(seconds = 1)
    private Duration timeout;
    @NotBlank
    private String mappingReleaseBaseUrl;
    @NotNull
    @DurationMin(seconds = 1)
    private Duration mappingCacheTtl;
    @Min(1)
    private int catalogConcurrency;

    public String getMappingReleaseBaseUrl() {
        return mappingReleaseBaseUrl;
    }

    public void setMappingReleaseBaseUrl(String value) {
        mappingReleaseBaseUrl = value;
    }

    public Duration getMappingCacheTtl() {
        return mappingCacheTtl;
    }

    public void setMappingCacheTtl(Duration value) {
        mappingCacheTtl = value;
    }

    public int getCatalogConcurrency() {
        return catalogConcurrency;
    }

    public void setCatalogConcurrency(int value) {
        catalogConcurrency = value;
    }


    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}
