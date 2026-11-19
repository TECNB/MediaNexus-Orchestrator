package com.medianexus.orchestrator.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 只读解析的部署边界；登录凭据仅通过加密热设置保存，不绑定环境明文。 */
@Validated
@ConfigurationProperties(prefix = "medianexus.guangya")
public class GuangyaProperties {
    @NotBlank private String apiBaseUrl;
    @NotBlank private String authBaseUrl;
    @Min(1024) @Max(16777216) private int maxResponseBytes;
    @Min(1) @Max(64) private int maxDepth;
    @Min(1) @Max(100000) private int maxNodes;
    @Min(1) @Max(10000) private int cacheMaxEntries;
    @NotNull @DurationMin(seconds = 1) private Duration cacheTtl;
    public String getApiBaseUrl() { return apiBaseUrl; }
    public void setApiBaseUrl(String value) { apiBaseUrl = value; }
    public String getAuthBaseUrl() { return authBaseUrl; }
    public void setAuthBaseUrl(String value) { authBaseUrl = value; }
    public int getMaxResponseBytes() { return maxResponseBytes; }
    public void setMaxResponseBytes(int value) { maxResponseBytes = value; }
    public int getMaxDepth() { return maxDepth; }
    public void setMaxDepth(int value) { maxDepth = value; }
    public int getMaxNodes() { return maxNodes; }
    public void setMaxNodes(int value) { maxNodes = value; }
    public int getCacheMaxEntries() { return cacheMaxEntries; }
    public void setCacheMaxEntries(int value) { cacheMaxEntries = value; }
    public Duration getCacheTtl() { return cacheTtl; }
    public void setCacheTtl(Duration value) { cacheTtl = value; }
}
