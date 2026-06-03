package com.medianexus.orchestrator.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ani-RSS 上游服务配置。
 */
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
     * 单次 Ani-RSS HTTP 请求超时时间，非正值会在客户端回退到 10 秒。
     */
    private Duration timeout = Duration.ofSeconds(10);

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
