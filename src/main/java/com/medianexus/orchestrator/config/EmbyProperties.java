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
     * Secret used to derive deterministic managed passwords for provisioned Emby users.
     */
    private String registrationPasswordSecret;

    /**
     * Existing Emby user whose UserPolicy is copied for newly provisioned users.
     */
    private String registrationTemplateUsername = "csy";

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

    private boolean adultOtherAutomationEnabled = true;

    private int adultOtherRefreshConcurrency = 8;

    private int adultOtherCollectionReadConcurrency = 8;

    private Duration adultOtherNewEventDebounce = Duration.ofSeconds(2);

    private Duration adultOtherDeleteEventDebounce = Duration.ofSeconds(5);

    private Duration adultOtherPrimaryPollInterval = Duration.ofSeconds(10);

    private Duration adultOtherPrimaryQuietPeriod = Duration.ofSeconds(30);

    private Duration adultOtherRefreshTimeout = Duration.ofMinutes(5);

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

    public String getRegistrationPasswordSecret() {
        return registrationPasswordSecret;
    }

    public void setRegistrationPasswordSecret(String registrationPasswordSecret) {
        this.registrationPasswordSecret = registrationPasswordSecret;
    }

    public String getRegistrationTemplateUsername() {
        return registrationTemplateUsername;
    }

    public void setRegistrationTemplateUsername(String registrationTemplateUsername) {
        this.registrationTemplateUsername = registrationTemplateUsername;
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

    public boolean isAdultOtherAutomationEnabled() {
        return adultOtherAutomationEnabled;
    }

    public void setAdultOtherAutomationEnabled(boolean adultOtherAutomationEnabled) {
        this.adultOtherAutomationEnabled = adultOtherAutomationEnabled;
    }

    public int getAdultOtherRefreshConcurrency() {
        return adultOtherRefreshConcurrency;
    }

    public void setAdultOtherRefreshConcurrency(int adultOtherRefreshConcurrency) {
        this.adultOtherRefreshConcurrency = adultOtherRefreshConcurrency;
    }

    public int getAdultOtherCollectionReadConcurrency() {
        return adultOtherCollectionReadConcurrency;
    }

    public void setAdultOtherCollectionReadConcurrency(int adultOtherCollectionReadConcurrency) {
        this.adultOtherCollectionReadConcurrency = adultOtherCollectionReadConcurrency;
    }

    public Duration getAdultOtherNewEventDebounce() {
        return adultOtherNewEventDebounce;
    }

    public void setAdultOtherNewEventDebounce(Duration adultOtherNewEventDebounce) {
        this.adultOtherNewEventDebounce = adultOtherNewEventDebounce;
    }

    public Duration getAdultOtherDeleteEventDebounce() {
        return adultOtherDeleteEventDebounce;
    }

    public void setAdultOtherDeleteEventDebounce(Duration adultOtherDeleteEventDebounce) {
        this.adultOtherDeleteEventDebounce = adultOtherDeleteEventDebounce;
    }

    public Duration getAdultOtherPrimaryPollInterval() {
        return adultOtherPrimaryPollInterval;
    }

    public void setAdultOtherPrimaryPollInterval(Duration adultOtherPrimaryPollInterval) {
        this.adultOtherPrimaryPollInterval = adultOtherPrimaryPollInterval;
    }

    public Duration getAdultOtherPrimaryQuietPeriod() {
        return adultOtherPrimaryQuietPeriod;
    }

    public void setAdultOtherPrimaryQuietPeriod(Duration adultOtherPrimaryQuietPeriod) {
        this.adultOtherPrimaryQuietPeriod = adultOtherPrimaryQuietPeriod;
    }

    public Duration getAdultOtherRefreshTimeout() {
        return adultOtherRefreshTimeout;
    }

    public void setAdultOtherRefreshTimeout(Duration adultOtherRefreshTimeout) {
        this.adultOtherRefreshTimeout = adultOtherRefreshTimeout;
    }
}
