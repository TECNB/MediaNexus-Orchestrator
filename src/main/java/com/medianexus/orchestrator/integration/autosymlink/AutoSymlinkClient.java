package com.medianexus.orchestrator.integration.autosymlink;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.AutoSymlinkProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * HTTP client for AutoSymlink's manual sync-task endpoint.
 * The caller supplies the configured task UUID and full JSON request body required by that endpoint.
 * Any HTTP 2xx response means the task was accepted; the response body has no stable contract.
 */
@Component
public class AutoSymlinkClient {

    private static final int MAX_LOG_BODY_LENGTH = 500;

    private final AutoSymlinkProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public AutoSymlinkClient(AutoSymlinkProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout())
                .build();
    }

    /**
     * Submits a sync task and accepts any HTTP 2xx response as successful submission.
     *
     * @throws AutoSymlinkClientException when configuration or input is invalid, transport fails, or AS returns non-2xx
     */
    public void submitSyncTask(String uuid, JsonNode requestBody) {
        validateConfiguration();
        if (!StringUtils.hasText(uuid)) {
            throw new AutoSymlinkClientException("AutoSymlink task uuid is missing");
        }
        if (requestBody == null || !requestBody.isObject()) {
            throw new AutoSymlinkClientException("AutoSymlink sync task request body must be a JSON object");
        }

        String body = writeJson(requestBody);
        HttpRequest request = HttpRequest.newBuilder(syncTaskUri(uuid))
                .timeout(timeout())
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Cookie", cleanConfigValue(properties.getCookie()))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AutoSymlinkClientException(
                        "AutoSymlink returned non-success status "
                                + response.statusCode()
                                + ": "
                                + truncateForLog(response.body())
                );
            }
        } catch (HttpTimeoutException exception) {
            throw new AutoSymlinkClientException("AutoSymlink request timed out after " + timeoutHint(), exception);
        } catch (IOException exception) {
            throw new AutoSymlinkClientException("AutoSymlink request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AutoSymlinkClientException("AutoSymlink request interrupted", exception);
        }
    }

    private URI syncTaskUri(String uuid) {
        String baseUrl = cleanConfigValue(properties.getBaseUrl()).replaceAll("/+$", "");
        return URI.create(baseUrl + "/common_tools/add_sync_task/" + encode(uuid.trim()));
    }

    private String writeJson(JsonNode body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException exception) {
            throw new AutoSymlinkClientException("AutoSymlink request serialization failed", exception);
        }
    }

    private void validateConfiguration() {
        if (!StringUtils.hasText(cleanConfigValue(properties.getBaseUrl()))
                || !StringUtils.hasText(cleanConfigValue(properties.getCookie()))) {
            throw new AutoSymlinkClientException("AutoSymlink configuration is incomplete");
        }
    }

    private Duration timeout() {
        return properties.getTimeout();
    }

    private String timeoutHint() {
        Duration configuredTimeout = timeout();
        long millis = configuredTimeout.toMillis();
        return millis % 1000 == 0 ? configuredTimeout.toSeconds() + "s" : millis + "ms";
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String truncateForLog(String value) {
        if (value == null) {
            return value;
        }
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ");
        return normalized.length() <= MAX_LOG_BODY_LENGTH
                ? normalized
                : normalized.substring(0, MAX_LOG_BODY_LENGTH);
    }

    private String cleanConfigValue(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.trim();
        if (cleaned.length() >= 2
                && ((cleaned.startsWith("'") && cleaned.endsWith("'"))
                || (cleaned.startsWith("\"") && cleaned.endsWith("\"")))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        return cleaned;
    }
}
