package com.medianexus.orchestrator.service.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.medianexus.orchestrator.integration.sonarr.SonarrClient;
import com.medianexus.orchestrator.integration.sonarr.SonarrClientException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class SonarrMediaCatalogSearch implements MediaCatalogSearch {

    private static final Pattern SAFE_ID_PATTERN = Pattern.compile("[^a-zA-Z0-9]+");
    private static final Pattern SEARCH_TEXT_SEPARATOR_PATTERN = Pattern.compile("[^0-9a-z\\p{IsHan}]+");

    private final SonarrClient sonarrClient;

    public SonarrMediaCatalogSearch(SonarrClient sonarrClient) {
        this.sonarrClient = sonarrClient;
    }

    @Override
    public List<SeriesCatalogItem> searchSeries(String term) {
        try {
            List<SeriesCatalogItem> items = new ArrayList<>();
            for (JsonNode series : sonarrClient.searchSeries(term)) {
                items.add(toSeriesCatalogItem(series, term));
            }
            return items;
        } catch (SonarrClientException exception) {
            throw catalogException(exception);
        }
    }

    @Override
    public SeriesCatalogSeasons getSeriesSeasons(SeriesCatalogIdentity identity) {
        Integer tvdbId = identity.tvdbId();
        if (tvdbId == null || tvdbId <= 0) {
            throw new MediaCatalogSearchException(
                    MediaCatalogSearchException.Reason.UNSUPPORTED_IDENTITY,
                    "Sonarr catalog seasons require a TVDB id"
            );
        }

        try {
            JsonNode series = sonarrClient.getSeriesByTvdbId(tvdbId);
            if (series == null) {
                return null;
            }
            return new SeriesCatalogSeasons(
                    tvdbId,
                    identity.tmdbId(),
                    textOrFallback(series.get("title"), "Unknown Title"),
                    extractSeasonNumbers(series.path("seasons"))
            );
        } catch (SonarrClientException exception) {
            throw catalogException(exception);
        }
    }

    private SeriesCatalogItem toSeriesCatalogItem(JsonNode series, String searchTerm) {
        String canonicalTitle = textOrFallback(series.get("title"), "Unknown Title");
        String title = preferredSeriesDisplayTitle(canonicalTitle, series.path("alternateTitles"), searchTerm);
        String originalTitle = textOrNull(series.get("originalTitle"));
        if (!sameSearchText(title, canonicalTitle) && !StringUtils.hasText(originalTitle)) {
            originalTitle = canonicalTitle;
        }
        Integer year = integerOrNull(series.get("year"));
        return new SeriesCatalogItem(
                buildSeriesId(series, title, year),
                title,
                originalTitle,
                year,
                textOrFallback(series.get("overview"), ""),
                extractPoster(series.path("images")),
                integerOrNull(series.get("tvdbId")),
                textOrNull(series.get("imdbId")),
                integerOrNull(series.get("tmdbId")),
                normalizeStatus(textOrNull(series.get("status"))),
                textOrNull(series.get("network")),
                textOrNull(series.get("seriesType"))
        );
    }

    private String preferredSeriesDisplayTitle(String canonicalTitle, JsonNode alternateTitles, String searchTerm) {
        if (hasChineseText(canonicalTitle) || !hasChineseText(searchTerm)) {
            return canonicalTitle;
        }

        List<String> chineseTitles = extractAlternateTitles(alternateTitles).stream()
                .filter(this::hasChineseText)
                .toList();
        if (chineseTitles.isEmpty()) {
            return canonicalTitle;
        }

        String normalizedSearchTerm = normalizeSearchText(searchTerm);
        for (String title : chineseTitles) {
            if (normalizeSearchText(title).equals(normalizedSearchTerm)) {
                return title;
            }
        }
        for (String title : chineseTitles) {
            String normalizedTitle = normalizeSearchText(title);
            if (normalizedTitle.contains(normalizedSearchTerm) || normalizedSearchTerm.contains(normalizedTitle)) {
                return title;
            }
        }
        return chineseTitles.get(0);
    }

    private String buildSeriesId(JsonNode series, String title, Integer year) {
        Integer tvdbId = integerOrNull(series.get("tvdbId"));
        if (tvdbId != null) {
            return "tvdb:" + tvdbId;
        }
        Integer tmdbId = integerOrNull(series.get("tmdbId"));
        if (tmdbId != null) {
            return "tmdb:" + tmdbId;
        }
        String imdbId = textOrNull(series.get("imdbId"));
        if (StringUtils.hasText(imdbId)) {
            return "imdb:" + imdbId;
        }
        return fallbackId(title, year);
    }

    private String fallbackId(String title, Integer year) {
        String safeTitle = SAFE_ID_PATTERN.matcher(title.toLowerCase(Locale.ROOT)).replaceAll("-")
                .replaceAll("^-+|-+$", "");
        if (!StringUtils.hasText(safeTitle)) {
            safeTitle = "unknown";
        }
        return safeTitle + "-" + (year == null ? "unknown" : year);
    }

    private String extractPoster(JsonNode images) {
        if (images == null || !images.isArray()) {
            return null;
        }
        for (JsonNode image : images) {
            String coverType = textOrNull(image.get("coverType"));
            if ("poster".equalsIgnoreCase(coverType)) {
                String remoteUrl = textOrNull(image.get("remoteUrl"));
                return StringUtils.hasText(remoteUrl) ? remoteUrl : textOrNull(image.get("url"));
            }
        }
        return null;
    }

    private List<Integer> extractSeasonNumbers(JsonNode seasons) {
        if (seasons == null || !seasons.isArray()) {
            return List.of();
        }
        Set<Integer> seasonNumbers = new TreeSet<>();
        for (JsonNode season : seasons) {
            Integer seasonNumber = integerOrNull(season.get("seasonNumber"));
            if (seasonNumber != null && seasonNumber > 0) {
                seasonNumbers.add(seasonNumber);
            }
        }
        return new ArrayList<>(seasonNumbers);
    }

    private List<String> extractAlternateTitles(JsonNode alternateTitles) {
        if (alternateTitles == null || !alternateTitles.isArray()) {
            return List.of();
        }
        Set<String> titles = new LinkedHashSet<>();
        for (JsonNode alternateTitle : alternateTitles) {
            String title = textOrNull(alternateTitle.get("title"));
            if (StringUtils.hasText(title)) {
                titles.add(title.trim());
            }
        }
        return List.copyOf(titles);
    }

    private boolean hasChineseText(String value) {
        return StringUtils.hasText(value) && value.chars().anyMatch(character ->
                Character.UnicodeScript.of(character) == Character.UnicodeScript.HAN
        );
    }

    private boolean sameSearchText(String left, String right) {
        return normalizeSearchText(left).equals(normalizeSearchText(right));
    }

    private String normalizeSearchText(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return SEARCH_TEXT_SEPARATOR_PATTERN.matcher(value.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    private String normalizeStatus(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "unknown";
    }

    private String textOrFallback(JsonNode node, String fallback) {
        String value = textOrNull(node);
        return StringUtils.hasText(value) ? value : fallback;
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

    private MediaCatalogSearchException catalogException(SonarrClientException exception) {
        MediaCatalogSearchException.Reason reason = exception.getReason() == SonarrClientException.Reason.CONFIGURATION
                ? MediaCatalogSearchException.Reason.CONFIGURATION
                : MediaCatalogSearchException.Reason.UPSTREAM;
        return new MediaCatalogSearchException(reason, exception.getMessage(), exception);
    }
}
