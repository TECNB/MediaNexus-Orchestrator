package com.medianexus.orchestrator.integration.telegram;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medianexus.orchestrator.config.TelegramWorkerProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class TelegramWorkerClient {

    private final TelegramWorkerProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public TelegramWorkerClient(TelegramWorkerProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder()
                .connectTimeout(properties.getTimeout())
                .build());
    }

    TelegramWorkerClient(
            TelegramWorkerProperties properties,
            ObjectMapper objectMapper,
            HttpClient httpClient
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public JsonNode health() {
        validateConfiguration();
        HttpRequest request = HttpRequest.newBuilder(uri("/health"))
                .timeout(properties.getTimeout())
                .GET()
                .build();
        return send(request);
    }

    public JsonNode resolveSource(String source) {
        ObjectNode body = objectMapper.createObjectNode();
        putSource(body, source);
        return post("/resolve-source", body);
    }

    public JsonNode forwardUnread(ObjectNode body) {
        return post("/forward-unread", body);
    }

    public JsonNode backfill(ObjectNode body) {
        return post("/backfill", body);
    }

    private JsonNode post(String path, JsonNode body) {
        validateConfiguration();
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .timeout(properties.getTimeout())
                .header("Authorization", "Bearer " + properties.getApiToken().trim())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(body), StandardCharsets.UTF_8))
                .build();
        return send(request);
    }

    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode body = readJson(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                JsonNode error = body.path("error");
                String message = error.path("message").asText(body.path("message").asText(
                        "Telegram Worker 返回 HTTP " + response.statusCode()
                ));
                Integer retryAfter = error.path("retryAfterSeconds").isNumber()
                        ? error.path("retryAfterSeconds").asInt()
                        : null;
                throw new TelegramWorkerClientException(
                        message,
                        error.path("retryable").asBoolean(false),
                        retryAfter
                );
            }
            return body;
        } catch (HttpTimeoutException exception) {
            throw new TelegramWorkerClientException("Telegram Worker 请求超时", exception, true);
        } catch (IOException exception) {
            throw new TelegramWorkerClientException("无法连接 Telegram Worker", exception, true);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new TelegramWorkerClientException("Telegram Worker 请求被中断", exception, true);
        }
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(StringUtils.hasText(value) ? value : "{}");
        } catch (JsonProcessingException exception) {
            throw new TelegramWorkerClientException("Telegram Worker 返回了无效 JSON", exception, false);
        }
    }

    private String writeJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new TelegramWorkerClientException("Telegram Worker 请求序列化失败", exception, false);
        }
    }

    private URI uri(String path) {
        String baseUrl = properties.getBaseUrl().trim().replaceAll("/+$", "");
        return URI.create(baseUrl + path);
    }

    private void validateConfiguration() {
        if (!StringUtils.hasText(properties.getBaseUrl()) || !StringUtils.hasText(properties.getApiToken())) {
            throw new TelegramWorkerClientException("Telegram Worker 配置不完整", false, null);
        }
    }

    public static void putSource(ObjectNode body, String source) {
        String normalized = source == null ? "" : source.trim();
        if (normalized.matches("-?\\d+")) {
            body.put("source", Long.parseLong(normalized));
        } else {
            body.put("source", normalized);
        }
    }
}
