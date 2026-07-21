package com.medianexus.orchestrator.integration.tmdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.TmdbProperties;
import com.medianexus.orchestrator.integration.tmdb.TmdbClientException.Reason;
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
public class TmdbClient {

    private final TmdbProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public TmdbClient(TmdbProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout())
                .build();
    }

    public JsonNode searchTv(String query, String language) {
        JsonNode payload = get(
                "/search/tv",
                "query=" + encode(query)
                        + "&language=" + encode(language)
                        + "&include_adult=false",
                "tv search"
        );
        JsonNode results = payload.path("results");
        if (!results.isArray()) {
            throw new TmdbClientException(Reason.INVALID_RESPONSE, "TMDB tv search response results is not an array");
        }
        return results;
    }

    public JsonNode searchMovies(String query, String language) {
        JsonNode payload = get(
                "/search/movie",
                "query=" + encode(query)
                        + "&language=" + encode(language)
                        + "&include_adult=false",
                "movie search"
        );
        JsonNode results = payload.path("results");
        if (!results.isArray()) {
            throw new TmdbClientException(Reason.INVALID_RESPONSE, "TMDB movie search response results is not an array");
        }
        return results;
    }

    public JsonNode getTvDetails(int seriesId, String language) {
        return get("/tv/" + seriesId, "language=" + encode(language), "tv details");
    }

    private JsonNode get(String path, String query, String operation) {
        validateConfiguration();
        HttpRequest request = HttpRequest.newBuilder(buildUri(path, query))
                .timeout(timeout())
                .header("Authorization", authorizationHeader())
                .header("Accept", "application/json")
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new TmdbClientException(Reason.UPSTREAM, "TMDB returned non-success status for " + operation);
            }
            return objectMapper.readTree(response.body());
        } catch (IOException exception) {
            throw new TmdbClientException(Reason.UPSTREAM, "TMDB request failed for " + operation, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new TmdbClientException(Reason.UPSTREAM, "TMDB request interrupted for " + operation, exception);
        }
    }

    private URI buildUri(String path, String query) {
        String suffix = StringUtils.hasText(query) ? "?" + query : "";
        return URI.create(baseUrl() + path + suffix);
    }

    private String baseUrl() {
        String baseUrl = cleanConfigValue(properties.getBaseUrl()).replaceAll("/+$", "");
        if (!StringUtils.hasText(baseUrl)) {
            throw new TmdbClientException(Reason.CONFIGURATION, "TMDB base URL is not configured");
        }
        return baseUrl;
    }

    private String authorizationHeader() {
        return "Bearer " + apiToken();
    }

    private String apiToken() {
        String token = cleanConfigValue(properties.getApiToken());
        if (token.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            token = token.substring("Bearer ".length()).trim();
        }
        if (!StringUtils.hasText(token)) {
            throw new TmdbClientException(Reason.CONFIGURATION, "TMDB API token is not configured");
        }
        return token;
    }

    private void validateConfiguration() {
        baseUrl();
        apiToken();
    }

    private String encode(String value) {
        return URLEncoder.encode(cleanConfigValue(value), StandardCharsets.UTF_8);
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
