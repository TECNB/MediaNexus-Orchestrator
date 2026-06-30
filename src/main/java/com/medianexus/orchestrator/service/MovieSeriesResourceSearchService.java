package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.dto.resources.response.MovieSearchItem;
import com.medianexus.orchestrator.dto.resources.response.MovieSearchResponse;
import com.medianexus.orchestrator.dto.resources.response.SeriesSearchItem;
import com.medianexus.orchestrator.dto.resources.response.SeriesSearchResponse;
import com.medianexus.orchestrator.dto.resources.response.SeriesSeasonsResponse;
import com.medianexus.orchestrator.integration.radarr.RadarrClient;
import com.medianexus.orchestrator.integration.radarr.RadarrClientException;
import com.medianexus.orchestrator.service.catalog.MediaCatalogSearch;
import com.medianexus.orchestrator.service.catalog.MediaCatalogSearchException;
import com.medianexus.orchestrator.service.catalog.SeriesCatalogIdentity;
import com.medianexus.orchestrator.service.catalog.SeriesCatalogItem;
import com.medianexus.orchestrator.service.catalog.SeriesCatalogSeasons;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MovieSeriesResourceSearchService {

    private static final Logger log = LoggerFactory.getLogger(MovieSeriesResourceSearchService.class);
    private static final Pattern SAFE_ID_PATTERN = Pattern.compile("[^a-zA-Z0-9]+");

    private final RadarrClient radarrClient;
    private final MediaCatalogSearch mediaCatalogSearch;
    private final AuthService authService;

    public MovieSeriesResourceSearchService(
            RadarrClient radarrClient,
            MediaCatalogSearch mediaCatalogSearch,
            AuthService authService
    ) {
        this.radarrClient = radarrClient;
        this.mediaCatalogSearch = mediaCatalogSearch;
        this.authService = authService;
    }

    public MovieSearchResponse searchMovies(String term) {
        authService.requireCurrentUser();
        String normalizedTerm = normalizeSearchTerm(term);
        try {
            List<MovieSearchItem> items = new ArrayList<>();
            for (JsonNode movie : radarrClient.searchMovies(normalizedTerm)) {
                items.add(toMovieSearchItem(movie));
            }
            return new MovieSearchResponse(items);
        } catch (RadarrClientException exception) {
            if (exception.getReason() == RadarrClientException.Reason.CONFIGURATION) {
                throw serviceUnavailable("Radarr 服务尚未配置");
            }
            log.warn("Movie search failed term={} reason={}", logValue(normalizedTerm), exception.getMessage(), exception);
            throw internalError("电影搜索失败，请稍后重试");
        }
    }

    public SeriesSearchResponse searchSeries(String term) {
        authService.requireCurrentUser();
        String normalizedTerm = normalizeSearchTerm(term);
        try {
            List<SeriesSearchItem> items = new ArrayList<>();
            for (SeriesCatalogItem series : mediaCatalogSearch.searchSeries(normalizedTerm)) {
                items.add(toSeriesSearchItem(series));
            }
            return new SeriesSearchResponse(items);
        } catch (MediaCatalogSearchException exception) {
            if (exception.getReason() == MediaCatalogSearchException.Reason.CONFIGURATION) {
                throw serviceUnavailable("剧集目录服务尚未配置");
            }
            log.warn("Series search failed term={} reason={}", logValue(normalizedTerm), exception.getMessage(), exception);
            throw internalError("剧集搜索失败，请稍后重试");
        }
    }

    public SeriesSeasonsResponse getSeriesSeasons(Integer tvdbId, Integer tmdbId) {
        authService.requireCurrentUser();
        if (tvdbId == null && tmdbId == null) {
            throw badRequest("tmdb_id or tvdb_id is required");
        }
        if (tvdbId != null && tvdbId <= 0) {
            throw badRequest("tvdb_id must be greater than 0");
        }
        if (tmdbId != null && tmdbId <= 0) {
            throw badRequest("tmdb_id must be greater than 0");
        }

        try {
            SeriesCatalogSeasons seasons = mediaCatalogSearch.getSeriesSeasons(
                    new SeriesCatalogIdentity(tvdbId, tmdbId, null)
            );
            if (seasons == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "series not found", HttpStatus.NOT_FOUND);
            }
            return new SeriesSeasonsResponse(
                    seasons.tvdbId(),
                    seasons.tmdbId(),
                    seasons.title(),
                    seasons.seasonCount(),
                    seasons.seasonNumbers()
            );
        } catch (BusinessException exception) {
            throw exception;
        } catch (MediaCatalogSearchException exception) {
            if (exception.getReason() == MediaCatalogSearchException.Reason.CONFIGURATION) {
                throw serviceUnavailable("剧集目录服务尚未配置");
            }
            log.warn(
                    "Series seasons lookup failed tmdbId={} tvdbId={} reason={}",
                    tmdbId,
                    tvdbId,
                    exception.getMessage(),
                    exception
            );
            throw internalError("剧集季数加载失败，请稍后重试");
        }
    }

    private MovieSearchItem toMovieSearchItem(JsonNode movie) {
        String title = textOrFallback(movie.get("title"), "Unknown Title");
        Integer year = integerOrNull(movie.get("year"));
        return new MovieSearchItem(
                buildMovieId(movie, title, year),
                title,
                textOrNull(movie.get("originalTitle")),
                year,
                textOrFallback(movie.get("overview"), ""),
                extractPoster(movie.path("images")),
                integerOrNull(movie.get("tmdbId")),
                textOrNull(movie.get("imdbId")),
                extractAlternateTitles(movie.path("alternateTitles")),
                mapMovieStatus(movie)
        );
    }

    private SeriesSearchItem toSeriesSearchItem(SeriesCatalogItem series) {
        return new SeriesSearchItem(
                series.id(),
                series.title(),
                series.originalTitle(),
                series.year(),
                series.overview(),
                series.poster(),
                series.tvdbId(),
                series.imdbId(),
                series.tmdbId(),
                series.status(),
                series.network(),
                series.seriesType()
        );
    }

    private String buildMovieId(JsonNode movie, String title, Integer year) {
        Integer tmdbId = integerOrNull(movie.get("tmdbId"));
        if (tmdbId != null) {
            return "tmdb:" + tmdbId;
        }
        String imdbId = textOrNull(movie.get("imdbId"));
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

    private String mapMovieStatus(JsonNode movie) {
        for (String candidate : new String[]{
                textOrNull(movie.get("status")),
                textOrNull(movie.get("minimumAvailability"))
        }) {
            String normalized = normalizeMovieStatus(candidate);
            if (normalized != null) {
                return normalized;
            }
        }
        if (movie.path("isAvailable").asBoolean(false)
                || StringUtils.hasText(textOrNull(movie.get("inCinemas")))
                || StringUtils.hasText(textOrNull(movie.get("digitalRelease")))
                || StringUtils.hasText(textOrNull(movie.get("physicalRelease")))) {
            return "released";
        }
        return "unknown";
    }

    private String normalizeMovieStatus(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (List.of("released", "available", "incinemas", "in_cinemas", "in cinemas").contains(normalized)) {
            return "released";
        }
        if (List.of("announced", "predb", "tba").contains(normalized)) {
            return "announced";
        }
        return null;
    }

    private String normalizeSearchTerm(String term) {
        if (!StringUtils.hasText(term)) {
            throw badRequest("搜索关键词不能为空");
        }
        return term.trim();
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

    private String logValue(String value) {
        String cleaned = value == null ? "" : value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return cleaned.length() <= 80 ? cleaned : cleaned.substring(0, 80);
    }

    private BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }

    private BusinessException serviceUnavailable(String message) {
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, message, HttpStatus.SERVICE_UNAVAILABLE);
    }

    private BusinessException internalError(String message) {
        return new BusinessException(ErrorCode.INTERNAL_ERROR, message, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
