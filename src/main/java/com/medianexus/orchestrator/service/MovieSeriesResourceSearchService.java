package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.dto.resources.response.MovieSearchItem;
import com.medianexus.orchestrator.dto.resources.response.MovieSearchResponse;
import com.medianexus.orchestrator.dto.resources.response.SeriesSearchItem;
import com.medianexus.orchestrator.dto.resources.response.SeriesSearchResponse;
import com.medianexus.orchestrator.dto.resources.response.SeriesSeasonsResponse;
import com.medianexus.orchestrator.service.catalog.MediaCatalogSearch;
import com.medianexus.orchestrator.service.catalog.MediaCatalogSearchException;
import com.medianexus.orchestrator.service.catalog.MovieCatalogItem;
import com.medianexus.orchestrator.service.catalog.SeriesCatalogItem;
import com.medianexus.orchestrator.service.catalog.SeriesCatalogSeasons;
import com.medianexus.orchestrator.service.catalog.TmdbMovieCatalogSearch;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MovieSeriesResourceSearchService {

    private static final Logger log = LoggerFactory.getLogger(MovieSeriesResourceSearchService.class);
    private final TmdbMovieCatalogSearch tmdbMovieCatalogSearch;
    private final MediaCatalogSearch mediaCatalogSearch;
    private final AuthService authService;

    public MovieSeriesResourceSearchService(
            TmdbMovieCatalogSearch tmdbMovieCatalogSearch,
            MediaCatalogSearch mediaCatalogSearch,
            AuthService authService
    ) {
        this.tmdbMovieCatalogSearch = tmdbMovieCatalogSearch;
        this.mediaCatalogSearch = mediaCatalogSearch;
        this.authService = authService;
    }

    public MovieSearchResponse searchMovies(String term) {
        authService.requireCurrentUser();
        String normalizedTerm = normalizeSearchTerm(term);
        try {
            List<MovieSearchItem> items = new ArrayList<>();
            for (MovieCatalogItem movie : tmdbMovieCatalogSearch.searchMovies(normalizedTerm)) {
                items.add(toMovieSearchItem(movie));
            }
            return new MovieSearchResponse(items);
        } catch (MediaCatalogSearchException exception) {
            if (exception.getReason() == MediaCatalogSearchException.Reason.CONFIGURATION) {
                throw serviceUnavailable("电影目录服务尚未配置");
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

    public SeriesSeasonsResponse getSeriesSeasons(Integer tmdbId) {
        authService.requireCurrentUser();
        if (tmdbId == null || tmdbId <= 0) {
            throw badRequest("tmdb_id must be greater than 0");
        }

        try {
            SeriesCatalogSeasons seasons = mediaCatalogSearch.getSeriesSeasons(tmdbId);
            if (seasons == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "series not found", HttpStatus.NOT_FOUND);
            }
            return new SeriesSeasonsResponse(
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
                    "Series seasons lookup failed tmdbId={} reason={}",
                    tmdbId,
                    exception.getMessage(),
                    exception
            );
            throw internalError("剧集季数加载失败，请稍后重试");
        }
    }

    private MovieSearchItem toMovieSearchItem(MovieCatalogItem movie) {
        return new MovieSearchItem(
                movie.id(),
                movie.title(),
                movie.originalTitle(),
                movie.year(),
                movie.overview(),
                movie.poster(),
                movie.tmdbId(),
                movie.imdbId(),
                movie.alternateTitles(),
                movie.status()
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

    private String normalizeSearchTerm(String term) {
        if (!StringUtils.hasText(term)) {
            throw badRequest("搜索关键词不能为空");
        }
        return term.trim();
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
