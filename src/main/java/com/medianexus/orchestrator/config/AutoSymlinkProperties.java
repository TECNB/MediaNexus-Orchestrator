package com.medianexus.orchestrator.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "medianexus.autosymlink")
public class AutoSymlinkProperties {

    private String baseUrl;

    private String cookie;

    @NotNull
    @DurationMin(seconds = 1)
    private Duration timeout;

    @Min(1)
    private int maxAttempts;

    @NotNull
    @DurationMin(seconds = 1)
    private Duration retryInterval;

    @Valid
    private SyncTask movie = new SyncTask();

    @Valid
    private SyncTask tv = new SyncTask();

    @Valid
    private SyncTask anime = new SyncTask();

    @Valid
    private SyncTask adult = new SyncTask();

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getCookie() {
        return cookie;
    }

    public void setCookie(String cookie) {
        this.cookie = cookie;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getRetryInterval() {
        return retryInterval;
    }

    public void setRetryInterval(Duration retryInterval) {
        this.retryInterval = retryInterval;
    }

    public SyncTask getMovie() {
        return movie;
    }

    public void setMovie(SyncTask movie) {
        this.movie = movie;
    }

    public SyncTask getTv() {
        return tv;
    }

    public void setTv(SyncTask tv) {
        this.tv = tv;
    }

    public SyncTask getAnime() {
        return anime;
    }

    public void setAnime(SyncTask anime) {
        this.anime = anime;
    }

    public SyncTask getAdult() {
        return adult;
    }

    public void setAdult(SyncTask adult) {
        this.adult = adult;
    }

    public static class SyncTask {

        private String uuid;

        private String requestBodyJson;

        public String getUuid() {
            return uuid;
        }

        public void setUuid(String uuid) {
            this.uuid = uuid;
        }

        public String getRequestBodyJson() {
            return requestBodyJson;
        }

        public void setRequestBodyJson(String requestBodyJson) {
            this.requestBodyJson = requestBodyJson;
        }
    }
}
