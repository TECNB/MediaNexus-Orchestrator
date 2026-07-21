package com.medianexus.orchestrator.service.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.medianexus.orchestrator.config.TmdbProperties;
import com.medianexus.orchestrator.integration.tmdb.TmdbClient;
import com.medianexus.orchestrator.integration.tmdb.TmdbClientException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Maps TMDB TV search and detail responses into the internal
 * series catalog contract consumed by resource search and release planning.
 */
@Component
public class TmdbSeriesCatalogSearch implements MediaCatalogSearch {

    private final TmdbClient tmdbClient;
    private final TmdbProperties properties;

    public TmdbSeriesCatalogSearch(TmdbClient tmdbClient, TmdbProperties properties) {
        this.tmdbClient = tmdbClient;
        this.properties = properties;
    }

    @Override
    public List<SeriesCatalogItem> searchSeries(String term) {
        try {
            List<SeriesCatalogItem> items = new ArrayList<>();
            for (JsonNode searchItem : tmdbClient.searchTv(term, defaultLanguage())) {
                SeriesCatalogItem item = toSeriesCatalogItem(searchItem);
                if (item != null) {
                    items.add(item);
                }
            }
            return items;
        } catch (TmdbClientException exception) {
            throw catalogException(exception);
        }
    }

    @Override
    public SeriesCatalogSeasons getSeriesSeasons(Integer tmdbId) {
        if (tmdbId == null || tmdbId <= 0) {
            throw new MediaCatalogSearchException(
                    MediaCatalogSearchException.Reason.UNSUPPORTED_IDENTITY,
                    "TMDB catalog seasons require a TMDB id"
            );
        }

        try {
            JsonNode detail = tmdbClient.getTvDetails(tmdbId, defaultLanguage());
            return new SeriesCatalogSeasons(
                    tmdbId,
                    firstNonBlank(
                            textOrNull(detail.get("name")),
                            textOrNull(detail.get("original_name")),
                            "Unknown Title"
                    ),
                    extractSeasonNumbers(detail.path("seasons"))
            );
        } catch (TmdbClientException exception) {
            throw catalogException(exception);
        }
    }

    private SeriesCatalogItem toSeriesCatalogItem(JsonNode searchItem) {
        Integer tmdbId = integerOrNull(searchItem.get("id"));
        if (tmdbId == null || tmdbId <= 0) {
            return null;
        }

        JsonNode defaultDetail = tmdbClient.getTvDetails(tmdbId, defaultLanguage());
        JsonNode fallbackDetail = needsFallbackOverview(searchItem, defaultDetail)
                ? tmdbClient.getTvDetails(tmdbId, fallbackLanguage())
                : null;
        String localizedTitle = firstText(
                defaultDetail.get("name"),
                searchItem.get("name")
        );
        String originalTitle = firstText(
                defaultDetail.get("original_name"),
                searchItem.get("original_name")
        );
        String title = firstNonBlank(localizedTitle, originalTitle, "Unknown Title");
        Integer year = parseYear(firstText(
                defaultDetail.get("first_air_date"),
                searchItem.get("first_air_date")
        ));
        String overview = firstNonBlank(
                firstText(defaultDetail.get("overview"), searchItem.get("overview")),
                fallbackDetail == null ? null : textOrNull(fallbackDetail.get("overview")),
                ""
        );

        return new SeriesCatalogItem(
                "tmdb:" + tmdbId,
                title,
                originalTitle,
                year,
                overview,
                posterUrl(firstText(defaultDetail.get("poster_path"), searchItem.get("poster_path"))),
                null,
                null,
                tmdbId,
                normalizeStatus(textOrNull(defaultDetail.get("status"))),
                firstNetworkName(defaultDetail.path("networks")),
                textOrNull(defaultDetail.get("type"))
        );
    }

    private boolean needsFallbackOverview(JsonNode searchItem, JsonNode defaultDetail) {
        return !defaultLanguage().equalsIgnoreCase(fallbackLanguage())
                && !StringUtils.hasText(firstText(defaultDetail.get("overview"), searchItem.get("overview")));
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
        String path = trimmedPath.startsWith("/") ? trimmedPath : "/" + trimmedPath;
        return baseUrl + path;
    }

    private String firstNetworkName(JsonNode networks) {
        if (networks == null || !networks.isArray()) {
            return null;
        }
        for (JsonNode network : networks) {
            String name = textOrNull(network.get("name"));
            if (StringUtils.hasText(name)) {
                return name;
            }
        }
        return null;
    }

    private List<Integer> extractSeasonNumbers(JsonNode seasons) {
        if (seasons == null || !seasons.isArray()) {
            return List.of();
        }
        TreeSet<Integer> seasonNumbers = new TreeSet<>();
        for (JsonNode season : seasons) {
            Integer seasonNumber = integerOrNull(season.get("season_number"));
            if (seasonNumber != null && seasonNumber > 0) {
                seasonNumbers.add(seasonNumber);
            }
        }
        return List.copyOf(seasonNumbers);
    }

    private Integer parseYear(String date) {
        if (!StringUtils.hasText(date) || date.length() < 4) {
            return null;
        }
        try {
            return Integer.parseInt(date.substring(0, 4));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String normalizeStatus(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "unknown";
    }

    private String firstText(JsonNode first, JsonNode second) {
        return firstNonBlank(textOrNull(first), textOrNull(second), null);
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

    private String fallbackLanguage() {
        return cleanConfigValue(properties.getFallbackLanguage());
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
