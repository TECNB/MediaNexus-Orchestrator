package com.medianexus.orchestrator.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * OpenList 上游服务和整季导入路径配置。
 */
@Validated
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
    @NotBlank
    private String offlineTool;

    /**
     * OpenList ed2k 离线下载工具名称。OpenList 的 PikPak 工具不支持 ed2k。
     */
    @NotBlank
    private String ed2kOfflineTool;

    /**
     * OpenList 离线下载任务的删除策略。
     */
    @NotBlank
    private String deletePolicy;

    /**
     * 动漫季度保存路径模板，支持 {title}、{themoviedbName}、{season}、{seasonFormat} 占位符。
     */
    @NotBlank
    private String animePathTemplate;

    /**
     * 电影离线提交基础保存路径；为空时仅禁用电影提交能力，不影响应用启动。
     */
    private String movieRootPath;

    /**
     * 剧集离线提交基础保存路径；为空时仅禁用剧集提交能力，不影响应用启动。
     */
    private String tvRootPath;

    /**
     * Adult 离线提交基础保存路径；为空时仅禁用 Adult 提交能力，不影响应用启动。
     */
    private String adultRootPath;

    /**
     * 整理后文件名模板，支持 {title}、{season}、{seasonFormat}、{episode}、{episodeFormat} 占位符。
     */
    @NotBlank
    private String animeRenameTemplate;

    /**
     * 逗号分隔的排除正则，用于跳过特别篇、总集篇或合集区间等非正片文件。
     */
    @NotBlank
    private String animeExcludePatterns;

    /**
     * 单次 OpenList HTTP 请求超时时间。
     */
    @NotNull
    @DurationMin(seconds = 1)
    private Duration timeout;

    /**
     * 轮询 OpenList 离线下载任务的间隔。
     */
    @NotNull
    @DurationMin(seconds = 1)
    private Duration pollInterval;

    /**
     * 离线下载任务最长等待时间。
     */
    @NotNull
    @DurationMin(seconds = 1)
    private Duration offlineTimeout;

    /**
     * Adult 离线下载任务最长等待时间，死种较多时使用更短窗口。
     */
    @NotNull
    @DurationMin(seconds = 1)
    private Duration adultOfflineTimeout;

    /**
     * OpenList 离线任务失败后的最大重试次数。
     */
    @Min(0)
    private int retryLimit;

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

    public String getEd2kOfflineTool() {
        return ed2kOfflineTool;
    }

    public void setEd2kOfflineTool(String ed2kOfflineTool) {
        this.ed2kOfflineTool = ed2kOfflineTool;
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

    public String getAdultRootPath() {
        return adultRootPath;
    }

    public void setAdultRootPath(String adultRootPath) {
        this.adultRootPath = adultRootPath;
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

    public Duration getAdultOfflineTimeout() {
        return adultOfflineTimeout;
    }

    public void setAdultOfflineTimeout(Duration adultOfflineTimeout) {
        this.adultOfflineTimeout = adultOfflineTimeout;
    }

    public int getRetryLimit() {
        return retryLimit;
    }

    public void setRetryLimit(int retryLimit) {
        this.retryLimit = retryLimit;
    }
}
