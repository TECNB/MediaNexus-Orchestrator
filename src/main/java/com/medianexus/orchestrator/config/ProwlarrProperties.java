package com.medianexus.orchestrator.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "medianexus.prowlarr")
public class ProwlarrProperties {

    private String baseUrl;

    private String apiKey;

    @NotNull
    @DurationMin(seconds = 1)
    private Duration timeout;

    @NotNull
    @DurationMin(seconds = 1)
    private Duration searchCacheTtl = Duration.ofMinutes(30);

    @Min(1)
    private long searchCacheMaxWeightBytes = 32L * 1024 * 1024;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
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

    public Duration getSearchCacheTtl() {
        return searchCacheTtl;
    }

    public void setSearchCacheTtl(Duration searchCacheTtl) {
        this.searchCacheTtl = searchCacheTtl;
    }

    public long getSearchCacheMaxWeightBytes() {
        return searchCacheMaxWeightBytes;
    }

    public void setSearchCacheMaxWeightBytes(long searchCacheMaxWeightBytes) {
        this.searchCacheMaxWeightBytes = searchCacheMaxWeightBytes;
    }
}
