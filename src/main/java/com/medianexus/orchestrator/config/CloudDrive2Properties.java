package com.medianexus.orchestrator.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * CloudDrive2 媒体库文件整理配置。
 */
@Validated
@ConfigurationProperties(prefix = "medianexus.clouddrive2")
public class CloudDrive2Properties {

    private boolean organizationEnabled;

    private boolean animeOrganizationEnabled;

    private boolean movieOrganizationEnabled;

    private boolean seriesOrganizationEnabled;

    private boolean adultOrganizationEnabled;

    private String host;

    @Min(1)
    @Max(65535)
    private int port = 19798;

    private String apiToken;

    private String ingestPathPrefix = "/pikpak";

    private String cloudDrivePathPrefix = "/WebDAV";

    @NotNull
    @DurationMin(seconds = 1)
    private Duration operationTimeout = Duration.ofMinutes(2);

    @NotNull
    @DurationMin(seconds = 1)
    private Duration visibilityTimeout = Duration.ofMinutes(2);

    @NotNull
    @DurationMin(millis = 100)
    private Duration visibilityPollInterval = Duration.ofSeconds(1);

    public boolean isOrganizationEnabled() {
        return organizationEnabled;
    }

    public void setOrganizationEnabled(boolean organizationEnabled) {
        this.organizationEnabled = organizationEnabled;
    }

    public boolean isAnimeOrganizationEnabled() {
        return animeOrganizationEnabled;
    }

    public void setAnimeOrganizationEnabled(boolean animeOrganizationEnabled) {
        this.animeOrganizationEnabled = animeOrganizationEnabled;
    }

    public boolean isMovieOrganizationEnabled() {
        return movieOrganizationEnabled;
    }

    public void setMovieOrganizationEnabled(boolean movieOrganizationEnabled) {
        this.movieOrganizationEnabled = movieOrganizationEnabled;
    }

    public boolean isSeriesOrganizationEnabled() {
        return seriesOrganizationEnabled;
    }

    public void setSeriesOrganizationEnabled(boolean seriesOrganizationEnabled) {
        this.seriesOrganizationEnabled = seriesOrganizationEnabled;
    }

    public boolean isAdultOrganizationEnabled() {
        return adultOrganizationEnabled;
    }

    public void setAdultOrganizationEnabled(boolean adultOrganizationEnabled) {
        this.adultOrganizationEnabled = adultOrganizationEnabled;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getApiToken() {
        return apiToken;
    }

    public void setApiToken(String apiToken) {
        this.apiToken = apiToken;
    }

    public String getIngestPathPrefix() {
        return ingestPathPrefix;
    }

    public void setIngestPathPrefix(String ingestPathPrefix) {
        this.ingestPathPrefix = ingestPathPrefix;
    }

    public String getCloudDrivePathPrefix() {
        return cloudDrivePathPrefix;
    }

    public void setCloudDrivePathPrefix(String cloudDrivePathPrefix) {
        this.cloudDrivePathPrefix = cloudDrivePathPrefix;
    }

    public Duration getOperationTimeout() {
        return operationTimeout;
    }

    public void setOperationTimeout(Duration operationTimeout) {
        this.operationTimeout = operationTimeout;
    }

    public Duration getVisibilityTimeout() {
        return visibilityTimeout;
    }

    public void setVisibilityTimeout(Duration visibilityTimeout) {
        this.visibilityTimeout = visibilityTimeout;
    }

    public Duration getVisibilityPollInterval() {
        return visibilityPollInterval;
    }

    public void setVisibilityPollInterval(Duration visibilityPollInterval) {
        this.visibilityPollInterval = visibilityPollInterval;
    }
}
