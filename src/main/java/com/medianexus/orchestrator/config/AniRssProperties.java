package com.medianexus.orchestrator.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Ani-RSS 上游服务配置。
 */
@Validated
@ConfigurationProperties(prefix = "medianexus.ani-rss")
public class AniRssProperties {

    /**
     * Ani-RSS 服务根地址，可配置为包含或不包含 /api 后缀。
     */
    private String baseUrl;

    /**
     * 传给 Ani-RSS 的 x-api-key。
     */
    private String apiKey;

    /**
     * 单次 Ani-RSS HTTP 请求超时时间。
     */
    @NotNull
    @DurationMin(seconds = 1)
    private Duration timeout;

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
}
