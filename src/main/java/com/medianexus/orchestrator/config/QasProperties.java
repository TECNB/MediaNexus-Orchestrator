package com.medianexus.orchestrator.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "medianexus.qas")
public class QasProperties {

    private String baseUrl;

    private String apiToken;

    /** Optional Quark account cookie used by the direct share-tree preview client. */
    private String quarkCookie;

    private String smartstrmWebhook;

    @NotNull
    @DurationMin(seconds = 1)
    private Duration timeout;

    @NotNull
    @DurationMin(seconds = 1)
    private Duration shareTreeCacheTtl;

    private String movieRootPath;

    private String tvRootPath;

    private String varietyRootPath;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiToken() {
        return apiToken;
    }

    public void setApiToken(String apiToken) {
        this.apiToken = apiToken;
    }

    public String getQuarkCookie() {
        return quarkCookie;
    }

    public void setQuarkCookie(String quarkCookie) {
        this.quarkCookie = quarkCookie;
    }

    public String getSmartstrmWebhook() {
        return smartstrmWebhook;
    }

    public void setSmartstrmWebhook(String smartstrmWebhook) {
        this.smartstrmWebhook = smartstrmWebhook;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public Duration getShareTreeCacheTtl() {
        return shareTreeCacheTtl;
    }

    public void setShareTreeCacheTtl(Duration shareTreeCacheTtl) {
        this.shareTreeCacheTtl = shareTreeCacheTtl;
    }

    public String getMovieRootPath() {
        return movieRootPath;
    }

    public void setMovieRootPath(String movieRootPath) {
        this.movieRootPath = movieRootPath;
    }

    public String getTvRootPath() {
        return tvRootPath;
    }

    public void setTvRootPath(String tvRootPath) {
        this.tvRootPath = tvRootPath;
    }

    public String getVarietyRootPath() {
        return varietyRootPath;
    }

    public void setVarietyRootPath(String varietyRootPath) {
        this.varietyRootPath = varietyRootPath;
    }
}
