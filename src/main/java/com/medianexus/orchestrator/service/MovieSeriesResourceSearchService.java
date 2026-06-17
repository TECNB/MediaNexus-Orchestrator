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
import com.medianexus.orchestrator.integration.sonarr.SonarrClient;
import com.medianexus.orchestrator.integration.sonarr.SonarrClientException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
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
    private final SonarrClient sonarrClient;
    private final AuthService authService;

    public MovieSeriesResourceSearchService(
            RadarrClient radarrClient,
            SonarrClient sonarrClient,
            AuthService authService
    ) {
        this.radarrClient = radarrClient;
        this.sonarrClient = sonarrClient;
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
            for (JsonNode series : sonarrClient.searchSeries(normalizedTerm)) {
                items.add(toSeriesSearchItem(series));
            }
            return new SeriesSearchResponse(items);
        } catch (SonarrClientException exception) {
            if (exception.getReason() == SonarrClientException.Reason.CONFIGURATION) {
                throw serviceUnavailable("Sonarr 服务尚未配置");
            }
            log.warn("Series search failed term={} reason={}", logValue(normalizedTerm), exception.getMessage(), exception);
            throw internalError("剧集搜索失败，请稍后重试");
        }
    }

    public SeriesSeasonsResponse getSeriesSeasons(Integer tvdbId) {
        authService.requireCurrentUser();
        if (tvdbId == null) {
            throw badRequest("tvdb_id is required");
        }
        if (tvdbId <= 0) {
            throw badRequest("tvdb_id must be greater than 0");
        }

        try {
            JsonNode series = sonarrClient.getSeriesByTvdbId(tvdbId);
            if (series == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "series not found", HttpStatus.NOT_FOUND);
            }
            List<Integer> seasonNumbers = extractSeasonNumbers(series.path("seasons"));
            return new SeriesSeasonsResponse(
                    tvdbId,
                    textOrFallback(series.get("title"), "Unknown Title"),
                    seasonNumbers.size(),
                    seasonNumbers
            );
        } catch (BusinessException exception) {
            throw exception;
        } catch (SonarrClientException exception) {
            if (exception.getReason() == SonarrClientException.Reason.CONFIGURATION) {
                throw serviceUnavailable("Sonarr 服务尚未配置");
            }
            log.warn("Series seasons lookup failed tvdbId={} reason={}", tvdbId, exception.getMessage(), exception);
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
                mapMovieStatus(movie)
        );
    }

    private SeriesSearchItem toSeriesSearchItem(JsonNode series) {
        String title = textOrFallback(series.get("title"), "Unknown Title");
        Integer year = integerOrNull(series.get("year"));
        return new SeriesSearchItem(
                buildSeriesId(series, title, year),
                title,
                textOrNull(series.get("originalTitle")),
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

    private String normalizeStatus(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "unknown";
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
