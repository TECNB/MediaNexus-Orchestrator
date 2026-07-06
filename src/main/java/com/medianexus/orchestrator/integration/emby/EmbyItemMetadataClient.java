package com.medianexus.orchestrator.integration.emby;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.EmbyProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class EmbyItemMetadataClient implements EmbyItemMetadataLookup {

    private static final Logger log = LoggerFactory.getLogger(EmbyItemMetadataClient.class);

    private final EmbyProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ConcurrentMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public EmbyItemMetadataClient(EmbyProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout())
                .build();
    }

    @Override
    public Optional<EmbyItemMetadata> findItemMetadata(String userId, String itemId) {
        String cleanedUserId = cleanConfigValue(userId);
        String cleanedItemId = cleanConfigValue(itemId);
        if (!StringUtils.hasText(cleanedUserId) || !StringUtils.hasText(cleanedItemId) || !isConfigured()) {
            return Optional.empty();
        }

        CacheEntry cached = cache.get(cleanedItemId);
        Instant now = Instant.now();
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return Optional.of(cached.metadata());
        }

        Optional<EmbyItemMetadata> loaded = loadItemMetadata(cleanedUserId, cleanedItemId);
        loaded.ifPresent(metadata -> cache.put(cleanedItemId, new CacheEntry(metadata, now.plus(cacheTtl()))));
        return loaded;
    }

    private Optional<EmbyItemMetadata> loadItemMetadata(String userId, String itemId) {
        HttpRequest request = HttpRequest.newBuilder(buildItemUri(userId, itemId))
                .timeout(timeout())
                .header("Accept", "application/json")
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Emby item metadata lookup returned non-success status itemId={} status={}",
                        itemId,
                        response.statusCode()
                );
                return Optional.empty();
            }
            return Optional.of(parseItemMetadata(objectMapper.readTree(response.body())));
        } catch (IOException exception) {
            log.warn("Emby item metadata lookup failed itemId={}", itemId, exception);
            return Optional.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("Emby item metadata lookup interrupted itemId={}", itemId, exception);
            return Optional.empty();
        }
    }

    private EmbyItemMetadata parseItemMetadata(JsonNode root) {
        return new EmbyItemMetadata(
                textAt(root, "Id"),
                textAt(root, "Type"),
                textAt(root, "Name"),
                textAt(root, "SeriesId", "SeriesID"),
                textAt(root, "SeriesName", "ShowName"),
                optionalSeasonNumber(textAt(root, "ParentIndexNumber", "SeasonNumber")),
                optionalEpisodeNumber(textAt(root, "IndexNumber", "EpisodeNumber")),
                textAt(root, "RunTimeTicks", "RuntimeTicks")
        );
    }

    private URI buildItemUri(String userId, String itemId) {
        return URI.create(baseUrl()
                + "/Users/" + encode(userId)
                + "/Items/" + encode(itemId)
                + "?api_key=" + encode(apiKey()));
    }

    private boolean isConfigured() {
        return StringUtils.hasText(cleanConfigValue(properties.getBaseUrl()))
                && StringUtils.hasText(cleanConfigValue(properties.getApiKey()));
    }

    private String baseUrl() {
        return cleanConfigValue(properties.getBaseUrl()).replaceAll("/+$", "");
    }

    private String apiKey() {
        return cleanConfigValue(properties.getApiKey());
    }

    private Duration timeout() {
        Duration timeout = properties.getTimeout();
        return timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(5) : timeout;
    }

    private Duration cacheTtl() {
        Duration ttl = properties.getMetadataCacheTtl();
        return ttl == null || ttl.isNegative() || ttl.isZero() ? Duration.ofHours(12) : ttl;
    }

    private String textAt(JsonNode root, String... paths) {
        for (String path : paths) {
            JsonNode node = root.get(path);
            if (node != null && !node.isNull() && !node.isContainerNode()) {
                return node.asText();
            }
        }
        return null;
    }

    private Integer optionalSeasonNumber(String value) {
        Integer number = optionalInteger(value);
        return number != null && number >= 0 ? number : null;
    }

    private Integer optionalEpisodeNumber(String value) {
        Integer number = optionalInteger(value);
        return number != null && number > 0 ? number : null;
    }

    private Integer optionalInteger(String value) {
        String cleaned = cleanConfigValue(value);
        if (!StringUtils.hasText(cleaned)) {
            return null;
        }
        try {
            return Integer.parseInt(cleaned);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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

    private record CacheEntry(
            EmbyItemMetadata metadata,
            Instant expiresAt
    ) {
    }
}
