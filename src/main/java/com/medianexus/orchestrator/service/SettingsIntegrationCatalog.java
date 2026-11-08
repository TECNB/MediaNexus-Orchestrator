package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.config.*;
import java.util.List;
import java.util.Set;

/** 字段归属的唯一白名单；账号路径与已有 AS 请求仅作为只读接管来源。 */
public final class SettingsIntegrationCatalog {
    private SettingsIntegrationCatalog() {}

    public record Software(String id, String label, Class<?> type, List<String> writable,
                           List<String> readOnly, Set<String> secrets) {
        public List<String> keys() {
            return java.util.stream.Stream.concat(writable.stream(), readOnly.stream()).toList();
        }
    }

    public static final List<Software> SOFTWARE = List.of(
            new Software("openlist", "OpenList", OpenListProperties.class,
                    List.of("baseUrl", "authorization", "offlineTool", "ed2kOfflineTool", "deletePolicy",
                            "timeout", "pollInterval", "offlineTimeout", "adultOfflineTimeout", "retryLimit",
                            "animeRenameTemplate", "animeExcludePatterns", "davUsername", "davPassword"),
                    List.of("movieRootPath", "tvRootPath", "adultRootPath", "animePathTemplate"),
                    Set.of("authorization", "davPassword")),
            new Software("clouddrive2", "CloudDrive2", CloudDrive2Properties.class,
                    List.of("host", "port", "apiToken", "organizationEnabled", "animeOrganizationEnabled",
                            "movieOrganizationEnabled", "seriesOrganizationEnabled", "adultOrganizationEnabled",
                            "operationTimeout", "visibilityTimeout", "visibilityPollInterval"),
                    List.of("ingestPathPrefix", "cloudDrivePathPrefix", "mediaSourcePathPrefix", "strmPathPrefix"), Set.of("apiToken")),
            new Software("autosymlink", "AutoSymlink", AutoSymlinkProperties.class,
                    List.of("disabled", "baseUrl", "cookie", "timeout", "maxAttempts", "retryInterval", "newAccountScanParametersJson"),
                    List.of("movie.uuid", "tv.uuid", "anime.uuid", "adult.uuid", "movie.requestBodyJson",
                            "tv.requestBodyJson", "anime.requestBodyJson", "adult.requestBodyJson"),
                    Set.of("cookie", "movie.requestBodyJson", "tv.requestBodyJson", "anime.requestBodyJson", "adult.requestBodyJson")),
            new Software("emby", "Emby", EmbyProperties.class,
                    List.of("baseUrl", "apiKey", "webhookSecret", "timeout", "registrationTemplateUsername",
                            "metadataCacheTtl", "adultOtherLibraryName", "adultOtherAutomationEnabled",
                            "adultOtherRefreshConcurrency", "adultOtherCollectionReadConcurrency",
                            "adultOtherNewEventDebounce", "adultOtherDeleteEventDebounce",
                            "adultOtherPrimaryPollInterval", "adultOtherPrimaryQuietPeriod", "adultOtherRefreshTimeout"),
                    List.of("registrationPasswordSecret"), Set.of("apiKey", "webhookSecret", "registrationPasswordSecret")),
            new Software("prowlarr", "Prowlarr", ProwlarrProperties.class,
                    List.of("disabled", "baseUrl", "apiKey", "timeout", "searchCacheTtl", "searchCacheMaxWeightBytes"), List.of(), Set.of("apiKey")),
            new Software("pansou", "PanSou", PanSouProperties.class,
                    List.of("disabled", "baseUrl", "username", "password", "timeout", "linkCheckLimit"), List.of(), Set.of("password")),
            new Software("qas", "夸克 / QAS", QasProperties.class,
                    List.of("baseUrl", "apiToken", "quarkCookie", "quarkApiBaseUrl", "timeout", "shareTreeCacheTtl",
                            "movieRootPath", "tvRootPath", "varietyRootPath"), List.of(), Set.of("apiToken", "quarkCookie")),
            new Software("smartstrm", "SmartSTRM", QasProperties.class,
                    List.of("smartstrmWebhook"), List.of(), Set.of("smartstrmWebhook")),
            new Software("wecom", "企业微信", WeComProperties.class,
                    List.of("enabled", "apiBaseUrl", "corpId", "agentId", "appSecret", "userId", "frontendBaseUrl", "timeout"),
                    List.of(), Set.of("appSecret")),
            new Software("anirss", "Ani-RSS", AniRssProperties.class, List.of(),
                    List.of("baseUrl", "apiKey", "timeout"), Set.of("apiKey")),
            new Software("telegram-worker", "Telegram Worker", TelegramWorkerProperties.class, List.of(),
                    List.of("baseUrl", "apiToken", "timeout"), Set.of("apiToken"))
    );

    public static String label(String key) {
        return switch (key) {
            case "baseUrl" -> "服务地址";
            case "imageBaseUrl" -> "图片基础地址";
            case "apiBaseUrl" -> "接口地址";
            case "apiKey" -> "API 密钥";
            case "apiToken" -> "API Token";
            case "authorization" -> "管理认证 Token";
            case "cookie" -> "登录 Cookie";
            case "timeout" -> "请求超时";
            case "enabled" -> "启用集成";
            case "disabled" -> "停用集成（保留已有凭据）";
            case "username" -> "用户名";
            case "password" -> "密码";
            case "davUsername" -> "OpenList DAV 用户名";
            case "davPassword" -> "OpenList DAV 密码（独立于管理 Token）";
            case "defaultLanguage" -> "默认语言";
            case "fallbackLanguage" -> "回退语言";
            case "dailyContentCreateLimit" -> "每日创建额度";
            case "offlineTool" -> "普通链接离线工具";
            case "ed2kOfflineTool" -> "ed2k 离线工具";
            case "deletePolicy" -> "离线任务删除策略";
            case "pollInterval" -> "离线状态轮询间隔";
            case "offlineTimeout" -> "离线等待超时";
            case "adultOfflineTimeout" -> "Adult 离线等待超时";
            case "retryLimit" -> "离线失败重试次数";
            case "animeRenameTemplate" -> "动漫文件命名规则";
            case "animeExcludePatterns" -> "动漫文件排除规则";
            case "movieRootPath" -> "电影根目录";
            case "tvRootPath" -> "剧集根目录";
            case "adultRootPath" -> "Adult 根目录";
            case "varietyRootPath" -> "综艺根目录";
            case "animePathTemplate" -> "原账号动漫保存模板";
            case "host" -> "gRPC 主机";
            case "port" -> "gRPC 端口";
            case "organizationEnabled" -> "启用 CD2 媒体整理";
            case "animeOrganizationEnabled" -> "动漫整季使用 CD2 整理";
            case "movieOrganizationEnabled" -> "电影使用 CD2 整理";
            case "seriesOrganizationEnabled" -> "剧集使用 CD2 整理";
            case "adultOrganizationEnabled" -> "Adult 使用 CD2 整理";
            case "operationTimeout" -> "gRPC 操作超时";
            case "visibilityTimeout" -> "文件可见性等待上限";
            case "visibilityPollInterval" -> "文件可见性检查间隔";
            case "ingestPathPrefix" -> "已有 OpenList 映射根";
            case "cloudDrivePathPrefix" -> "已有 CD2 云内映射根";
            case "mediaSourcePathPrefix" -> "已有媒体源挂载根";
            case "strmPathPrefix" -> "已有 STRM 输出根";
            case "maxAttempts" -> "同步调用最大尝试次数";
            case "retryInterval" -> "同步调用重试间隔";
            case "newAccountScanParametersJson" -> "未来账号通用扫描参数 JSON（不含路径或认证）";
            case "movie.uuid" -> "原账号电影 AS 任务";
            case "tv.uuid" -> "原账号剧集 AS 任务";
            case "anime.uuid" -> "原账号动漫 AS 任务";
            case "adult.uuid" -> "原账号 Adult AS 任务";
            case "movie.requestBodyJson" -> "原账号电影同步请求状态";
            case "tv.requestBodyJson" -> "原账号剧集同步请求状态";
            case "anime.requestBodyJson" -> "原账号动漫同步请求状态";
            case "adult.requestBodyJson" -> "原账号 Adult 同步请求状态";
            case "webhookSecret" -> "Webhook 认证密钥";
            case "registrationPasswordSecret" -> "托管账号派生密钥";
            case "registrationTemplateUsername" -> "注册权限模板用户名";
            case "metadataCacheTtl" -> "元数据缓存有效期";
            case "adultOtherLibraryName" -> "Adult-Other 媒体库名称";
            case "adultOtherAutomationEnabled" -> "启用 Adult-Other 自动维护";
            case "adultOtherRefreshConcurrency" -> "Adult-Other 刷新并发";
            case "adultOtherCollectionReadConcurrency" -> "Adult-Other 合集读取并发";
            case "adultOtherNewEventDebounce" -> "Adult-Other 新增事件合并等待";
            case "adultOtherDeleteEventDebounce" -> "Adult-Other 删除事件合并等待";
            case "adultOtherPrimaryPollInterval" -> "Adult-Other 媒体轮询间隔";
            case "adultOtherPrimaryQuietPeriod" -> "Adult-Other 媒体稳定等待";
            case "adultOtherRefreshTimeout" -> "Adult-Other 刷新等待上限";
            case "searchCacheTtl" -> "发布搜索缓存有效期";
            case "searchCacheMaxWeightBytes" -> "发布搜索缓存容量（字节）";
            case "linkCheckLimit" -> "链接检查上限";
            case "quarkCookie" -> "夸克 Cookie";
            case "quarkApiBaseUrl" -> "夸克接口地址";
            case "shareTreeCacheTtl" -> "分享目录缓存有效期";
            case "smartstrmWebhook" -> "整理回调地址（敏感）";
            case "corpId" -> "企业 ID";
            case "agentId" -> "应用 ID";
            case "appSecret" -> "应用 Secret";
            case "userId" -> "通知接收成员";
            case "frontendBaseUrl" -> "消息跳转站点地址";
            case "allowedExtensions" -> "允许的字幕扩展名";
            case "maxUploadSize" -> "上传文件大小上限（字节）";
            case "maxEntryCount" -> "压缩包字幕条目上限";
            case "maxTextSubtitleSize" -> "文本字幕大小上限（字节）";
            case "maxSupSize" -> "SUP 字幕大小上限（字节）";
            case "maxTotalExtractedSize" -> "解压总大小上限（字节）";
            case "maxCompressionRatio" -> "解压压缩比上限";
            case "engine" -> "默认磁力搜索引擎";
            default -> key;
        };
    }
}
