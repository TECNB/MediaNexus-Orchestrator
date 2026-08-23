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

    @NotNull
    @DurationMin(seconds = 1)
    private Duration timeout;

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

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
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
