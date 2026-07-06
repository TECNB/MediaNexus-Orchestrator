package com.medianexus.orchestrator.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Emby webhook and ranking integration configuration.
 */
@ConfigurationProperties(prefix = "medianexus.emby")
public class EmbyProperties {

    /**
     * Emby Server base URL used for lightweight item metadata lookups and Collection sync.
     */
    private String baseUrl;

    /**
     * Emby API key used for item metadata lookups.
     */
    private String apiKey;

    /**
     * Secret expected in the Emby webhook query string.
     */
    private String webhookSecret;

    /**
     * Timeout for lightweight Emby API calls.
     */
    private Duration timeout = Duration.ofSeconds(5);

    /**
     * In-memory TTL for item metadata loaded from Emby.
     */
    private Duration metadataCacheTtl = Duration.ofHours(12);

    private String adultOtherLibraryName = "Adult - Other";

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

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public void setWebhookSecret(String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public Duration getMetadataCacheTtl() {
        return metadataCacheTtl;
    }

    public void setMetadataCacheTtl(Duration metadataCacheTtl) {
        this.metadataCacheTtl = metadataCacheTtl;
    }

    public String getAdultOtherLibraryName() {
        return adultOtherLibraryName;
    }

    public void setAdultOtherLibraryName(String adultOtherLibraryName) {
        this.adultOtherLibraryName = adultOtherLibraryName;
    }
}
