package com.medianexus.orchestrator.integration.radarr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.RadarrProperties;
import com.medianexus.orchestrator.integration.radarr.RadarrClientException.Reason;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class RadarrClient {

    private final RadarrProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public RadarrClient(RadarrProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout())
                .build();
    }

    public JsonNode searchMovies(String term) {
        JsonNode payload = get(
                "/api/v3/movie/lookup",
                "term=" + encode(term),
                "movie lookup"
        );
        if (!payload.isArray()) {
            throw new RadarrClientException(Reason.INVALID_RESPONSE, "Radarr movie lookup response is not an array");
        }
        return payload;
    }

    private JsonNode get(String path, String query, String operation) {
        validateConfiguration();
        HttpRequest request = HttpRequest.newBuilder(buildUri(path, query))
                .timeout(timeout())
                .header("X-Api-Key", cleanConfigValue(properties.getApiKey()))
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RadarrClientException(
                        Reason.UPSTREAM,
                        "Radarr returned non-success status for " + operation
                );
            }
            return objectMapper.readTree(response.body());
        } catch (IOException exception) {
            throw new RadarrClientException(Reason.UPSTREAM, "Radarr request failed for " + operation, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RadarrClientException(Reason.UPSTREAM, "Radarr request interrupted for " + operation, exception);
        }
    }

    private URI buildUri(String path, String query) {
        String baseUrl = baseUrl();
        String suffix = StringUtils.hasText(query) ? "?" + query : "";
        return URI.create(baseUrl + path + suffix);
    }

    private String baseUrl() {
        String scheme = cleanConfigValue(properties.getScheme()).toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new RadarrClientException(Reason.CONFIGURATION, "Radarr scheme must be http or https");
        }

        String host = cleanConfigValue(properties.getHost()).replaceAll("/+$", "");
        if (!StringUtils.hasText(host) || !StringUtils.hasText(cleanConfigValue(properties.getApiKey()))) {
            throw new RadarrClientException(Reason.CONFIGURATION, "Radarr configuration is incomplete");
        }
        String baseUrl = host.startsWith("http://") || host.startsWith("https://")
                ? host
                : scheme + "://" + host;
        Integer port = properties.getPort();
        if (port != null && port > 0 && !baseUrl.matches("^https?://[^/:]+:\\d+.*$")) {
            baseUrl = baseUrl + ":" + port;
        }
        return baseUrl;
    }

    private void validateConfiguration() {
        baseUrl();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private Duration timeout() {
        return properties.getTimeout();
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
