package com.medianexus.orchestrator.integration.anirss;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.AniRssProperties;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.SocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Ani-RSS REST API 客户端。
 *
 * Ani-RSS 会在控制器路由前统一添加 {@code /api} 前缀；调用方只传业务 endpoint，
 * 本客户端负责拼接最终路径、附加 {@code x-api-key}，并把上游响应解包成 data 节点。
 */
@Component
public class AniRssClient {

    private static final ProxySelector DIRECT_PROXY_SELECTOR = new ProxySelector() {
        @Override
        public List<Proxy> select(URI uri) {
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            // Direct connections do not maintain proxy failure state.
        }
    };

    private final AniRssProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public AniRssClient(AniRssProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout())
                .proxy(DIRECT_PROXY_SELECTOR)
                .build();
    }

    /**
     * 调用 Ani-RSS Mikan 搜索，返回上游 data 节点。
     */
    public JsonNode searchMikan(String keyword) {
        return post("mikan", "text=" + encode(keyword), "{}");
    }

    /**
     * 调用 Ani-RSS Bangumi 搜索，供整季 magnet 导入选择条目使用。
     */
    public JsonNode searchBgm(String keyword) {
        return post("searchBgm", "name=" + encode(keyword), "{}");
    }

    /**
     * 按 Bangumi subject id 获取 Ani-RSS 订阅草稿，包含 Ani-RSS 解析出的 TMDB 标题。
     */
    public JsonNode getAniBySubjectId(String bgmId) {
        return post("getAniBySubjectId", "id=" + encode(bgmId), "{}");
    }

    /**
     * 按 Mikan 番剧页面地址获取字幕组候选。
     */
    public JsonNode getMikanGroups(String sourceUrl) {
        return post("mikanGroup", "url=" + encode(sourceUrl), "{}");
    }

    /**
     * 将 Mikan RSS、Bangumi 地址和字幕组转换为 Ani-RSS 订阅草稿。
     */
    public JsonNode rssToAni(JsonNode payload) {
        return post("rssToAni", null, writeJson(payload));
    }

    /**
     * 预览 Ani-RSS 订阅草稿会产生的下载条目和缺集信息。
     */
    public JsonNode previewAni(JsonNode subscription) {
        return post("previewAni", null, writeJson(subscription));
    }

    /**
     * 返回 Ani-RSS 当前订阅列表，用于本地重复订阅检查。
     */
    public JsonNode listAni() {
        return post("listAni", null, "{}");
    }

    /**
     * 提交 Ani-RSS 订阅草稿。
     */
    public JsonNode addAni(JsonNode subscription) {
        return post("addAni", null, writeJson(subscription));
    }

    private JsonNode unwrapResult(String endpoint, String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode codeNode = root.get("code");
            if (codeNode != null && codeNode.isNumber()) {
                int code = codeNode.asInt();
                // Ani-RSS 新旧接口混用 0 和 200 表示成功，其他 code 都作为上游失败处理。
                if (code != 0 && code != 200) {
                    throw new AniRssClientException("ani-rss returned failure code "
                            + code + " for endpoint " + endpoint);
                }
            }
            if (root.has("data")) {
                return root.get("data");
            }
            return root;
        } catch (IOException exception) {
            throw new AniRssClientException("ani-rss response parse failed for endpoint " + endpoint, exception);
        }
    }

    private JsonNode post(String endpoint, String query, String body) {
        validateConfiguration();

        HttpRequest request = HttpRequest.newBuilder(buildApiUri(endpoint, query))
                .timeout(timeout())
                .header("Content-Type", "application/json")
                .header("x-api-key", properties.getApiKey().trim())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AniRssClientException("ani-rss returned non-success status "
                        + response.statusCode() + " for endpoint " + endpoint);
            }
            return unwrapResult(endpoint, response.body());
        } catch (IOException exception) {
            throw new AniRssClientException("ani-rss request failed for endpoint " + endpoint, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AniRssClientException("ani-rss request interrupted for endpoint " + endpoint, exception);
        }
    }

    private String writeJson(JsonNode payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (IOException exception) {
            throw new AniRssClientException("ani-rss request payload serialization failed", exception);
        }
    }

    private URI buildApiUri(String endpoint, String query) {
        String baseUrl = properties.getBaseUrl().trim();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        // 允许配置值已经包含 /api，避免部署环境切换时生成 /api/api/...。
        String path = baseUrl.endsWith("/api") ? "/" + endpoint : "/api/" + endpoint;
        String suffix = StringUtils.hasText(query) ? "?" + query : "";
        return URI.create(baseUrl + path + suffix);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private void validateConfiguration() {
        if (!StringUtils.hasText(properties.getBaseUrl()) || !StringUtils.hasText(properties.getApiKey())) {
            throw new AniRssClientException("ani-rss configuration is incomplete");
        }
    }

    private Duration timeout() {
        return properties.getTimeout();
    }
}
