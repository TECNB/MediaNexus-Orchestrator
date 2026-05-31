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

    public JsonNode searchMikan(String keyword) {
        return post("mikan", "text=" + encode(keyword), "{}");
    }

    public JsonNode searchBgm(String keyword) {
        return post("searchBgm", "name=" + encode(keyword), "{}");
    }

    public JsonNode getMikanGroups(String sourceUrl) {
        return post("mikanGroup", "url=" + encode(sourceUrl), "{}");
    }

    public JsonNode rssToAni(JsonNode payload) {
        return post("rssToAni", null, writeJson(payload));
    }

    public JsonNode previewAni(JsonNode subscription) {
        return post("previewAni", null, writeJson(subscription));
    }

    public JsonNode listAni() {
        return post("listAni", null, "{}");
    }

    public JsonNode addAni(JsonNode subscription) {
        return post("addAni", null, writeJson(subscription));
    }

    private JsonNode unwrapResult(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode codeNode = root.get("code");
            if (codeNode != null && codeNode.isNumber()) {
                int code = codeNode.asInt();
                if (code != 0 && code != 200) {
                    throw new AniRssClientException("ani-rss returned failure code " + code);
                }
            }
            if (root.has("data")) {
                return root.get("data");
            }
            return root;
        } catch (IOException exception) {
            throw new AniRssClientException("ani-rss response parse failed", exception);
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
            return unwrapResult(response.body());
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
        if (properties.getTimeout() == null || properties.getTimeout().isNegative() || properties.getTimeout().isZero()) {
            return Duration.ofSeconds(10);
        }
        return properties.getTimeout();
    }
}
