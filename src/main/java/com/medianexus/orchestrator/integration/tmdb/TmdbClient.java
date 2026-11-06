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
    private volatile Transport transport;

    public TmdbClient(TmdbProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public JsonNode searchTv(String query, String language) {
        return searchTv(query, language, properties.snapshot());
    }

    /** 目录操作传入已捕获的配置，后续请求不重新读取设置。 */
    public JsonNode searchTv(String query, String language, TmdbProperties snapshot) {
        JsonNode payload = get(snapshot,
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
        return searchMovies(query, language, properties.snapshot());
    }

    /** 目录操作传入已捕获的配置，后续请求不重新读取设置。 */
    public JsonNode searchMovies(String query, String language, TmdbProperties snapshot) {
        JsonNode payload = get(snapshot,
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

    public JsonNode getMovieDetails(int movieId, String language) {
        return get("/movie/" + movieId, "language=" + encode(language), "movie details");
    }

    /** 发行类型用于区分院线与已发生的数字、实体或电视发行。 */
    public JsonNode getMovieReleaseDates(int movieId) {
        return get("/movie/" + movieId + "/release_dates", "", "movie release dates");
    }

    public JsonNode getTvDetails(int seriesId, String language) {
        return getTvDetails(seriesId, language, properties.snapshot());
    }

    public JsonNode getTvDetails(int seriesId, String language, TmdbProperties snapshot) {
        return get(snapshot, "/tv/" + seriesId, "language=" + encode(language), "tv details");
    }

    public JsonNode getTvSeasonDetails(int seriesId, int seasonNumber, String language) {
        return getTvSeasonDetails(seriesId, seasonNumber, language, properties.snapshot());
    }

    public JsonNode getTvSeasonDetails(int seriesId, int seasonNumber, String language, TmdbProperties snapshot) {
        return get(snapshot,
                "/tv/" + seriesId + "/season/" + seasonNumber,
                "language=" + encode(language),
                "tv season details"
        );
    }

    /** 不设置语言筛选，保留其他语种及无语言图片作为封面候选。 */
    public JsonNode getMovieImages(int movieId) {
        return getMovieImages(movieId, properties.snapshot());
    }

    public JsonNode getMovieImages(int movieId, TmdbProperties snapshot) {
        return get(snapshot, "/movie/" + movieId + "/images", "", "movie images");
    }

    public JsonNode getTvImages(int seriesId) {
        return getTvImages(seriesId, properties.snapshot());
    }

    public JsonNode getTvImages(int seriesId, TmdbProperties snapshot) {
        return get(snapshot, "/tv/" + seriesId + "/images", "", "tv images");
    }

    private JsonNode get(String path, String query, String operation) {
        return get(properties.snapshot(), path, query, operation);
    }

    private JsonNode get(TmdbProperties snapshot, String path, String query, String operation) {
        validateConfiguration(snapshot);
        HttpRequest request = HttpRequest.newBuilder(buildUri(snapshot, path, query))
                .timeout(snapshot.getTimeout())
                .header("Authorization", "Bearer " + apiToken(snapshot))
                .header("Accept", "application/json")
                .GET()
                .build();

        try {
            HttpResponse<String> response = transport(snapshot.getTimeout()).send(request, HttpResponse.BodyHandlers.ofString());
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

    private URI buildUri(TmdbProperties snapshot, String path, String query) {
        String suffix = StringUtils.hasText(query) ? "?" + query : "";
        return URI.create(baseUrl(snapshot) + path + suffix);
    }

    private String baseUrl(TmdbProperties snapshot) {
        String baseUrl = cleanConfigValue(snapshot.getBaseUrl()).replaceAll("/+$", "");
        if (!StringUtils.hasText(baseUrl)) {
            throw new TmdbClientException(Reason.CONFIGURATION, "TMDB base URL is not configured");
        }
        return baseUrl;
    }

    private String apiToken(TmdbProperties snapshot) {
        String token = cleanConfigValue(snapshot.getApiToken());
        if (token.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            token = token.substring("Bearer ".length()).trim();
        }
        if (!StringUtils.hasText(token)) {
            throw new TmdbClientException(Reason.CONFIGURATION, "TMDB API token is not configured");
        }
        return token;
    }

    private void validateConfiguration(TmdbProperties snapshot) {
        baseUrl(snapshot);
        apiToken(snapshot);
    }

    private String encode(String value) {
        return URLEncoder.encode(cleanConfigValue(value), StandardCharsets.UTF_8);
    }

    /** 只替换后续调用的连接；已发出的请求继续持有旧客户端。 */
    private HttpClient transport(Duration timeout) {
        Transport current = transport;
        if (current == null || !current.timeout().equals(timeout)) {
            current = new Transport(timeout, HttpClient.newBuilder().connectTimeout(timeout).build());
            transport = current;
        }
        return current.client();
    }

    private record Transport(Duration timeout, HttpClient client) {}

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
