package com.medianexus.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PikPak 个人访问令牌。令牌为空则该账号继续走 OpenList。
 */
@ConfigurationProperties(prefix = "medianexus.pikpak-pat")
public class PikPakPatProperties {

    private String apiBase = "https://api-drive.mypikpak.com";

    private String mediaBPrefix = "";

    private String mediaBToken = "";

    private String adultPrefix = "";

    private String adultToken = "";

    public String getApiBase() {
        return apiBase;
    }

    public void setApiBase(String apiBase) {
        this.apiBase = apiBase;
    }

    public String getMediaBPrefix() {
        return mediaBPrefix;
    }

    public void setMediaBPrefix(String mediaBPrefix) {
        this.mediaBPrefix = mediaBPrefix;
    }

    public String getMediaBToken() {
        return mediaBToken;
    }

    public void setMediaBToken(String mediaBToken) {
        this.mediaBToken = mediaBToken;
    }

    public String getAdultPrefix() {
        return adultPrefix;
    }

    public void setAdultPrefix(String adultPrefix) {
        this.adultPrefix = adultPrefix;
    }

    public String getAdultToken() {
        return adultToken;
    }

    public void setAdultToken(String adultToken) {
        this.adultToken = adultToken;
    }
}
