package com.medianexus.orchestrator.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenList 上游服务和整季导入路径配置。
 */
@ConfigurationProperties(prefix = "medianexus.openlist")
public class OpenListProperties {

    /**
     * OpenList 服务根地址，客户端会自动拼接 /api。
     */
    private String baseUrl;

    /**
     * OpenList Authorization 头，通常为 token 或 Bearer token。
     */
    private String authorization;

    /**
     * OpenList 离线下载工具名称，默认使用 PikPak。
     */
    private String offlineTool = "PikPak";

    /**
     * OpenList 离线下载任务的删除策略。
     */
    private String deletePolicy = "delete_on_upload_succeed";

    /**
     * 动漫季度保存路径模板，支持 title、themoviedbName、season、seasonFormat 占位符。
     */
    private String animePathTemplate = "/pikpak/Media/Anime/${themoviedbName}/Season ${season}";

    /**
     * 整理后文件名模板，支持 title、season、seasonFormat、episode、episodeFormat 占位符。
     */
    private String animeRenameTemplate = "${title} S${seasonFormat}E${episodeFormat}";

    /**
     * 逗号分隔的排除正则，用于跳过特别篇、总集篇或合集区间等非正片文件。
     */
    private String animeExcludePatterns = "特别篇,\\d-\\d,总集";

    /**
     * 单次 OpenList HTTP 请求超时时间，非正值会在客户端回退到 30 秒。
     */
    private Duration timeout = Duration.ofSeconds(120);

    /**
     * 轮询 OpenList 离线下载任务的间隔，非正值会回退到 30 秒。
     */
    private Duration pollInterval = Duration.ofSeconds(30);

    /**
     * 离线下载任务最长等待时间，非正值会回退到 360 分钟。
     */
    private Duration offlineTimeout = Duration.ofMinutes(360);

    /**
     * OpenList 离线任务失败后的最大重试次数。
     */
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
