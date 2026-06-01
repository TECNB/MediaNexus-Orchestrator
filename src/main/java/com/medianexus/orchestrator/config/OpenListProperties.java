package com.medianexus.orchestrator.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "medianexus.openlist")
public class OpenListProperties {

    private String baseUrl;

    private String authorization;

    private String offlineTool = "PikPak";

    private String deletePolicy = "delete_on_upload_succeed";

    private String animePathTemplate = "/pikpak/Media/Anime/${themoviedbName}/Season ${season}";

    private String animeRenameTemplate = "${title} S${seasonFormat}E${episodeFormat}";

    private String animeExcludePatterns = "特别篇,\\d-\\d,总集";

    private Duration timeout = Duration.ofSeconds(120);

    private Duration pollInterval = Duration.ofSeconds(30);

    private Duration offlineTimeout = Duration.ofMinutes(360);

    private int retryLimit = 5;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getAuthorization() {
        return authorization;
    }

    public void setAuthorization(String authorization) {
        this.authorization = authorization;
    }

    public String getOfflineTool() {
        return offlineTool;
    }

    public void setOfflineTool(String offlineTool) {
        this.offlineTool = offlineTool;
    }

    public String getDeletePolicy() {
        return deletePolicy;
    }

    public void setDeletePolicy(String deletePolicy) {
        this.deletePolicy = deletePolicy;
    }

    public String getAnimePathTemplate() {
        return animePathTemplate;
    }

    public void setAnimePathTemplate(String animePathTemplate) {
        this.animePathTemplate = animePathTemplate;
    }

    public String getAnimeRenameTemplate() {
        return animeRenameTemplate;
    }

    public void setAnimeRenameTemplate(String animeRenameTemplate) {
        this.animeRenameTemplate = animeRenameTemplate;
    }

    public String getAnimeExcludePatterns() {
        return animeExcludePatterns;
    }

    public void setAnimeExcludePatterns(String animeExcludePatterns) {
        this.animeExcludePatterns = animeExcludePatterns;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public Duration getOfflineTimeout() {
        return offlineTimeout;
    }

    public void setOfflineTimeout(Duration offlineTimeout) {
        this.offlineTimeout = offlineTimeout;
    }

    public int getRetryLimit() {
        return retryLimit;
    }

    public void setRetryLimit(int retryLimit) {
        this.retryLimit = retryLimit;
    }
}
