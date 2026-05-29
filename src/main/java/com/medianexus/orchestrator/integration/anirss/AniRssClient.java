package com.medianexus.orchestrator.integration.anirss;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.AniRssProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AniRssClient {

    private final AniRssProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public AniRssClient(AniRssProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout())
                .build();
    }

    public JsonNode searchMikan(String keyword) {
        validateConfiguration();

        HttpRequest request = HttpRequest.newBuilder(buildMikanUri(keyword))
                .timeout(timeout())
                .header("Content-Type", "application/json")
                .header("x-api-key", properties.getApiKey().trim())
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AniRssClientException("ani-rss returned non-success status");
            }
            return unwrapResult(response.body());
        } catch (IOException exception) {
            throw new AniRssClientException("ani-rss request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AniRssClientException("ani-rss request interrupted", exception);
        }
    }

    private JsonNode unwrapResult(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (root.has("data")) {
                JsonNode codeNode = root.get("code");
                if (codeNode != null && codeNode.isNumber()) {
                    int code = codeNode.asInt();
                    if (code != 0 && code != 200) {
                        throw new AniRssClientException("ani-rss returned failure code");
                    }
                }
                return root.get("data");
            }
            return root;
        } catch (IOException exception) {
            throw new AniRssClientException("ani-rss response parse failed", exception);
        }
    }

    private URI buildMikanUri(String keyword) {
        String baseUrl = properties.getBaseUrl().trim();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String encodedKeyword = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
        return URI.create(baseUrl + "/mikan?text=" + encodedKeyword);
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
