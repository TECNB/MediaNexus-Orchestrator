package com.medianexus.orchestrator.service.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.medianexus.orchestrator.config.TmdbProperties;
import com.medianexus.orchestrator.integration.tmdb.TmdbClient;
import com.medianexus.orchestrator.integration.tmdb.TmdbClientException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class TmdbMovieCatalogSearch {

    private final TmdbClient tmdbClient;
    private final TmdbProperties properties;

    public TmdbMovieCatalogSearch(TmdbClient tmdbClient, TmdbProperties properties) {
        this.tmdbClient = tmdbClient;
        this.properties = properties;
    }

    public List<MovieCatalogItem> searchMovies(String term) {
        try {
            List<MovieCatalogItem> items = new ArrayList<>();
            for (JsonNode searchItem : tmdbClient.searchMovies(term, defaultLanguage())) {
                MovieCatalogItem item = toMovieCatalogItem(searchItem);
                if (item != null) {
                    items.add(item);
                }
            }
            return items;
        } catch (TmdbClientException exception) {
            throw catalogException(exception);
        }
    }

    private MovieCatalogItem toMovieCatalogItem(JsonNode searchItem) {
        Integer tmdbId = integerOrNull(searchItem.get("id"));
        if (tmdbId == null || tmdbId <= 0) {
            return null;
        }

        String originalTitle = textOrNull(searchItem.get("original_title"));
        String title = firstNonBlank(textOrNull(searchItem.get("title")), originalTitle, "Unknown Title");
        String releaseDate = textOrNull(searchItem.get("release_date"));
        return new MovieCatalogItem(
                "tmdb:" + tmdbId,
                title,
                originalTitle,
                parseYear(releaseDate),
                firstNonBlank(textOrNull(searchItem.get("overview")), null, ""),
                posterUrl(textOrNull(searchItem.get("poster_path"))),
                tmdbId,
                null,
                List.of(),
                releaseStatus(releaseDate)
        );
    }

    private String posterUrl(String posterPath) {
        if (!StringUtils.hasText(posterPath)) {
            return null;
        }
        String trimmedPath = posterPath.trim();
        if (trimmedPath.startsWith("http://") || trimmedPath.startsWith("https://")) {
            return trimmedPath;
        }
        String baseUrl = cleanConfigValue(properties.getImageBaseUrl()).replaceAll("/+$", "");
        if (!StringUtils.hasText(baseUrl)) {
            return null;
        }
        return baseUrl + (trimmedPath.startsWith("/") ? trimmedPath : "/" + trimmedPath);
    }

    private Integer parseYear(String releaseDate) {
        if (!StringUtils.hasText(releaseDate) || releaseDate.length() < 4) {
            return null;
        }
        try {
            return Integer.parseInt(releaseDate.substring(0, 4));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String releaseStatus(String releaseDate) {
        if (!StringUtils.hasText(releaseDate)) {
            return "unknown";
        }
        try {
            return LocalDate.parse(releaseDate).isAfter(LocalDate.now()) ? "announced" : "released";
        } catch (DateTimeParseException exception) {
            return "unknown";
        }
    }

    private String firstNonBlank(String first, String second, String fallback) {
        if (StringUtils.hasText(first)) {
            return first.trim();
        }
        if (StringUtils.hasText(second)) {
            return second.trim();
        }
        return fallback;
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private Integer integerOrNull(JsonNode node) {
        if (node == null || node.isNull() || !node.isNumber()) {
            return null;
        }
        return node.asInt();
    }

    private String defaultLanguage() {
        return cleanConfigValue(properties.getDefaultLanguage());
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

    private MediaCatalogSearchException catalogException(TmdbClientException exception) {
        MediaCatalogSearchException.Reason reason = exception.getReason() == TmdbClientException.Reason.CONFIGURATION
                ? MediaCatalogSearchException.Reason.CONFIGURATION
                : MediaCatalogSearchException.Reason.UPSTREAM;
        return new MediaCatalogSearchException(reason, exception.getMessage(), exception);
    }
}
