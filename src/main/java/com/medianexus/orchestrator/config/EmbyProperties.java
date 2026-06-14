package com.medianexus.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Emby webhook and ranking integration configuration.
 */
@ConfigurationProperties(prefix = "medianexus.emby")
public class EmbyProperties {

    /**
     * Secret expected in the Emby webhook query string.
     */
    private String webhookSecret;

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public void setWebhookSecret(String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }
}
