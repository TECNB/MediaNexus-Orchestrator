package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.dto.magnet.request.MovieMagnetIngestRequest;
import com.medianexus.orchestrator.dto.magnet.request.SeriesMagnetIngestRequest;
import com.medianexus.orchestrator.dto.magnet.response.MovieMagnetIngestTaskResponse;
import com.medianexus.orchestrator.dto.magnet.response.AnimeMagnetIngestTaskResponse;
import com.medianexus.orchestrator.dto.magnet.response.SeriesMagnetIngestTaskResponse;
import com.medianexus.orchestrator.dto.resources.request.MovieOpenListIngestRequest;
import com.medianexus.orchestrator.dto.resources.request.MovieReleaseOpenListIngestRequest;
import com.medianexus.orchestrator.dto.resources.request.MovieReleaseRecommendationRequest;
import com.medianexus.orchestrator.dto.resources.request.MovieReleaseSearchRequest;
import com.medianexus.orchestrator.dto.resources.request.SeriesOpenListIngestRequest;
import com.medianexus.orchestrator.dto.resources.request.SeriesReleaseOpenListIngestRequest;
import com.medianexus.orchestrator.dto.resources.request.SeriesReleaseRecommendationRequest;
import com.medianexus.orchestrator.dto.resources.request.SeriesReleaseSearchRequest;
import com.medianexus.orchestrator.dto.resources.response.ProwlarrReleaseItemResponse;
import com.medianexus.orchestrator.dto.resources.response.ProwlarrReleaseRecommendationResponse;
import com.medianexus.orchestrator.dto.resources.response.ProwlarrReleaseSearchResponse;
import com.medianexus.orchestrator.dto.taskcenter.request.OpenListReleaseRetryRequest;
import com.medianexus.orchestrator.integration.prowlarr.ProwlarrClient;
import com.medianexus.orchestrator.integration.prowlarr.ProwlarrClientException;
import com.medianexus.orchestrator.model.MovieMagnetIngestTask;
import com.medianexus.orchestrator.model.User;
import com.medianexus.orchestrator.model.AnimeMagnetIngestTask;
import com.medianexus.orchestrator.model.SeriesMagnetIngestTask;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 保留既有发布接口；新搜索读取全局引擎，提交按候选自身的下载引用解析。 */
@Service
public class ProwlarrReleaseIngestService {

    private static final Logger log = LoggerFactory.getLogger(ProwlarrReleaseIngestService.class);
    private static final List<String> RESOLUTION_TAGS = List.of("2160p", "1080p", "720p");
    private static final List<String> DYNAMIC_RANGE_TAGS =
            List.of("dolby_vision", "hdr10_plus", "hdr10", "hdr", "hlg", "sdr");
    private static final String SERIES_PRODUCT_TYPE = "SERIES";
    private static final String ANIME_PRODUCT_TYPE = "ANIME";

    private final AuthService authService;
    private final MagnetIngestService magnetIngestService;
    private final AnimeMagnetIngestTaskService animeMagnetIngestTaskService;
    private final ReleaseCandidateComparison comparisons;
    private final ReleaseFileTables fileTables;

    public ProwlarrReleaseIngestService(AuthService authService,
            MagnetIngestService magnetIngestService, AnimeMagnetIngestTaskService animeMagnetIngestTaskService,
            ReleaseCandidateComparison comparisons, ReleaseFileTables fileTables) {
        this.authService = authService;
        this.magnetIngestService = magnetIngestService;
        this.animeMagnetIngestTaskService = animeMagnetIngestTaskService;
        this.comparisons = comparisons;
        this.fileTables = fileTables;
    }

    public ProwlarrReleaseSearchResponse searchReleases(
            String term,
            String mediaType,
            Integer seasonNumber
    ) {
        authService.requireCurrentUser();
        if ("movie".equalsIgnoreCase(mediaType)) {
            return comparisons.searchMovie(new MovieReleaseSearchRequest(null, null,
                    requiredText(term, "搜索词不能为空"), null, null, null));
        }
        if ("series".equalsIgnoreCase(mediaType)) {
            return comparisons.searchSeries(new SeriesReleaseSearchRequest(null, null, null,
                    requiredText(term, "搜索词不能为空"), null, validateSeasonNumber(seasonNumber), null));
        }
        throw badRequest("不支持的媒体类型");
    }

    public ProwlarrReleaseRecommendationResponse recommendMovieRelease(MovieReleaseRecommendationRequest request) {
        authService.requireCurrentUser();
        return comparisons.previewMovie(request);
    }

    ProwlarrReleaseRecommendationResponse recommendMovieReleaseForAutomation(MovieReleaseRecommendationRequest request) {
        return comparisons.recommendMovie(request);
    }

    ProwlarrReleaseRecommendationResponse recommendMovieReleaseForAutomation(
            MovieReleaseRecommendationRequest request, java.util.function.Consumer<ReleaseSearchProgress> progress) {
        return comparisons.recommendMovie(request, progress);
    }

    ProwlarrReleaseRecommendationResponse recommendMovieReleaseForAutomation(MovieReleaseRecommendationRequest request,
            String bangumiTitle, java.util.function.Consumer<ReleaseSearchProgress> progress) {
        return comparisons.recommendMovie(request, bangumiTitle, progress);
    }

    ProwlarrReleaseRecommendationResponse recommendWeComMovie(
            MovieReleaseRecommendationRequest request, java.util.function.Consumer<ReleaseSearchProgress> progress) {
        return comparisons.recommendPreferredMovie(request, progress);
    }

    ProwlarrReleaseRecommendationResponse recommendWeComSeries(
            SeriesReleaseRecommendationRequest request, java.util.function.Consumer<ReleaseSearchProgress> progress) {
        return comparisons.recommendPreferredSeries(request, progress);
    }

    public ProwlarrReleaseRecommendationResponse refreshMovieReleaseRecommendation(
            MovieReleaseRecommendationRequest request
    ) {
        authService.requireCurrentUser();
        return comparisons.refreshRecommendMovie(request);
    }

    public ProwlarrReleaseSearchResponse searchMovieReleases(MovieReleaseSearchRequest request) {
        authService.requireCurrentUser();
        return comparisons.searchMovie(request);
    }

    public ProwlarrReleaseSearchResponse refreshMovieReleases(MovieReleaseSearchRequest request) {
        authService.requireCurrentUser();
        return comparisons.refreshMovie(request);
    }

    public ProwlarrReleaseRecommendationResponse recommendSeriesRelease(SeriesReleaseRecommendationRequest request) {
        authService.requireCurrentUser();
        return comparisons.previewSeries(request);
    }

    ProwlarrReleaseRecommendationResponse recommendSeriesReleaseForAutomation(SeriesReleaseRecommendationRequest request) {
        return comparisons.recommendSeries(request);
    }

    ProwlarrReleaseRecommendationResponse recommendSeriesReleaseForAutomation(
            SeriesReleaseRecommendationRequest request, java.util.function.Consumer<ReleaseSearchProgress> progress) {
        return comparisons.recommendSeries(request, progress);
    }

    ProwlarrReleaseRecommendationResponse recommendSeriesReleaseForAutomation(SeriesReleaseRecommendationRequest request,
            String bangumiTitle, java.util.function.Consumer<ReleaseSearchProgress> progress) {
        return comparisons.recommendSeries(request, bangumiTitle, progress);
    }

    public ProwlarrReleaseRecommendationResponse refreshSeriesReleaseRecommendation(
            SeriesReleaseRecommendationRequest request
    ) {
        authService.requireCurrentUser();
        return comparisons.refreshRecommendSeries(request);
    }

    public ProwlarrReleaseSearchResponse searchSeriesReleases(SeriesReleaseSearchRequest request) {
        authService.requireCurrentUser();
        return comparisons.searchSeries(request);
    }

    public ProwlarrReleaseSearchResponse refreshSeriesReleases(SeriesReleaseSearchRequest request) {
        authService.requireCurrentUser();
        return comparisons.refreshSeries(request);
    }

    public MovieMagnetIngestTaskResponse ingestMovie(MovieOpenListIngestRequest request) {
        authService.requireCurrentUser();
        var selected = comparisons.recommendMovie(new MovieReleaseRecommendationRequest(request.tmdbId(), null,
                request.title(), request.originalTitle(), request.year(), request.quality())).item();
        return ingestSelectedMovie(new MovieReleaseOpenListIngestRequest(request.title(), request.originalTitle(),
                request.year(), selected.title(), selected.indexer(), selected.size(), selected.indexerId(),
                selected.downloadRef(), selected.resolutionTags(), selected.dynamicRangeTags(), request.tmdbId()));
    }

    public SeriesMagnetIngestTaskResponse ingestSeries(SeriesOpenListIngestRequest request) {
        authService.requireCurrentUser();
        var selected = comparisons.recommendSeries(new SeriesReleaseRecommendationRequest(null, request.tmdbId(), null,
                request.title(), request.originalTitle(), request.seasonNumber(), request.quality())).item();
        return ingestSelectedSeries(new SeriesReleaseOpenListIngestRequest(request.title(), request.originalTitle(),
                request.seasonNumber(), request.taskProductType(), selected.title(), selected.indexer(), selected.size(),
                selected.indexerId(), selected.downloadRef(), selected.resolutionTags(), selected.dynamicRangeTags(), request.tmdbId()));
    }

    public MovieMagnetIngestTaskResponse ingestSelectedMovie(MovieReleaseOpenListIngestRequest request) {
        authService.requireCurrentUser();
        String title = requiredText(request == null ? null : request.title(), "电影标题不能为空");
        String releaseTitle = requiredText(request == null ? null : request.releaseTitle(), "发布标题不能为空");
        int year = validateYear(request == null ? null : request.year());
        String magnet = resolveMagnet(request.indexerId(), request.downloadRef(), releaseTitle);
        return magnetIngestService.createMovieTask(
                new MovieMagnetIngestRequest(
                        magnet,
                        title,
                        trimToNull(request.originalTitle()),
                        year,
                        request.tmdbId()
                ),
                requestMetadata(
                        releaseTitle,
                        request.indexer(),
                        request.size(),
                        request.indexerId(),
                        request.downloadRef(),
                        request.resolutionTags(),
                        request.dynamicRangeTags()
                ).withUnverifiedRisk(request.acceptUnverifiedRisk())
        );
    }

    public SeriesMagnetIngestTaskResponse ingestSelectedSeries(SeriesReleaseOpenListIngestRequest request) {
        authService.requireCurrentUser();
        String title = requiredText(request == null ? null : request.title(), "剧集标题不能为空");
        String releaseTitle = requiredText(request == null ? null : request.releaseTitle(), "发布标题不能为空");
        int seasonNumber = validateSeasonNumber(request == null ? null : request.seasonNumber());
        String magnet = resolveMagnet(request.indexerId(), request.downloadRef(), releaseTitle);
        SeriesMagnetIngestRequest ingestRequest =
                new SeriesMagnetIngestRequest(
                        magnet,
                        title,
                        trimToNull(request.originalTitle()),
                        seasonNumber,
                        request.tmdbId()
                );
        ReleaseIngestMetadata releaseMetadata = requestMetadata(
                releaseTitle,
                request.indexer(),
                request.size(),
                request.indexerId(),
                request.downloadRef(),
                request.resolutionTags(),
                request.dynamicRangeTags()
        ).withUnverifiedRisk(request.acceptUnverifiedRisk());
        if (requiresAnimeSeriesTask(request.taskProductType())) {
            return magnetIngestService.createAnimeSeasonSeriesTask(ingestRequest, releaseMetadata);
        }
        return magnetIngestService.createSeriesTask(ingestRequest, releaseMetadata);
    }

    /** 仅供已校验归属的后台编排调用；沿用发布解析、任务类别与扣额规则。 */
    MovieMagnetIngestTaskResponse ingestSelectedMovieForOwner(MovieReleaseOpenListIngestRequest request, User owner) {
        String title = requiredText(request.title(), "电影标题不能为空");
        String releaseTitle = requiredText(request.releaseTitle(), "发布标题不能为空");
        String magnet = resolveMagnet(request.indexerId(), request.downloadRef(), releaseTitle);
        return magnetIngestService.createMovieTaskForOwner(
                new MovieMagnetIngestRequest(magnet, title, trimToNull(request.originalTitle()),
                        validateYear(request.year()), request.tmdbId()),
                requestMetadata(releaseTitle, request.indexer(), request.size(), request.indexerId(),
                        request.downloadRef(),
                        request.resolutionTags(), request.dynamicRangeTags()), owner);
    }

    SeriesMagnetIngestTaskResponse ingestSelectedAnimeForOwner(SeriesReleaseOpenListIngestRequest request, User owner) {
        String title = requiredText(request.title(), "动漫标题不能为空");
        String releaseTitle = requiredText(request.releaseTitle(), "发布标题不能为空");
        String magnet = resolveMagnet(request.indexerId(), request.downloadRef(), releaseTitle);
        return magnetIngestService.createAnimeSeasonTaskForOwner(
                new SeriesMagnetIngestRequest(magnet, title, trimToNull(request.originalTitle()),
                        validateSeasonNumber(request.seasonNumber()), request.tmdbId()),
                requestMetadata(releaseTitle, request.indexer(), request.size(), request.indexerId(),
                        request.downloadRef(),
                        request.resolutionTags(), request.dynamicRangeTags()), owner);
    }

    SeriesMagnetIngestTaskResponse ingestSelectedSeriesForOwner(SeriesReleaseOpenListIngestRequest request, User owner) {
        String title = requiredText(request.title(), "剧集标题不能为空");
        String releaseTitle = requiredText(request.releaseTitle(), "发布标题不能为空");
        String magnet = resolveMagnet(request.indexerId(), request.downloadRef(), releaseTitle);
        return magnetIngestService.createSeriesTaskForOwner(
                new SeriesMagnetIngestRequest(magnet, title, trimToNull(request.originalTitle()),
                        validateSeasonNumber(request.seasonNumber()), request.tmdbId()),
                requestMetadata(releaseTitle, request.indexer(), request.size(), request.indexerId(),
                        request.downloadRef(), request.resolutionTags(), request.dynamicRangeTags()), owner);
    }

    /** 仅供已校验归属的人工确认操作；保留显式许可，不向自动 ForOwner 入口传播。 */
    MovieMagnetIngestTaskResponse ingestConfirmedMovieForOwner(MovieReleaseOpenListIngestRequest request, User owner) {
        String title = requiredText(request.title(), "电影标题不能为空");
        String releaseTitle = requiredText(request.releaseTitle(), "发布标题不能为空");
        String magnet = resolveMagnet(request.indexerId(), request.downloadRef(), releaseTitle);
        return magnetIngestService.createConfirmedMovieTaskForOwner(
                new MovieMagnetIngestRequest(magnet, title, trimToNull(request.originalTitle()),
                        validateYear(request.year()), request.tmdbId()),
                requestMetadata(releaseTitle, request.indexer(), request.size(), request.indexerId(),
                        request.downloadRef(), request.resolutionTags(), request.dynamicRangeTags())
                        .withUnverifiedRisk(request.acceptUnverifiedRisk()), owner);
    }

    SeriesMagnetIngestTaskResponse ingestConfirmedAnimeForOwner(SeriesReleaseOpenListIngestRequest request, User owner) {
        String title = requiredText(request.title(), "动漫标题不能为空");
        String releaseTitle = requiredText(request.releaseTitle(), "发布标题不能为空");
        String magnet = resolveMagnet(request.indexerId(), request.downloadRef(), releaseTitle);
        return magnetIngestService.createConfirmedAnimeSeasonTaskForOwner(
                new SeriesMagnetIngestRequest(magnet, title, trimToNull(request.originalTitle()),
                        validateSeasonNumber(request.seasonNumber()), request.tmdbId()),
                requestMetadata(releaseTitle, request.indexer(), request.size(), request.indexerId(),
                        request.downloadRef(), request.resolutionTags(), request.dynamicRangeTags())
                        .withUnverifiedRisk(request.acceptUnverifiedRisk()), owner);
    }

    SeriesMagnetIngestTaskResponse ingestConfirmedSeriesForOwner(SeriesReleaseOpenListIngestRequest request, User owner) {
        String title = requiredText(request.title(), "剧集标题不能为空");
        String releaseTitle = requiredText(request.releaseTitle(), "发布标题不能为空");
        String magnet = resolveMagnet(request.indexerId(), request.downloadRef(), releaseTitle);
        return magnetIngestService.createConfirmedSeriesTaskForOwner(
                new SeriesMagnetIngestRequest(magnet, title, trimToNull(request.originalTitle()),
                        validateSeasonNumber(request.seasonNumber()), request.tmdbId()),
                requestMetadata(releaseTitle, request.indexer(), request.size(), request.indexerId(),
                        request.downloadRef(), request.resolutionTags(), request.dynamicRangeTags())
                        .withUnverifiedRisk(request.acceptUnverifiedRisk()), owner);
    }


    public MovieMagnetIngestTaskResponse ingestSelectedMovieRetry(
            MovieMagnetIngestTask originalTask,
            OpenListReleaseRetryRequest request,
            TaskRetryReference retryReference
    ) {
        authService.requireCurrentUser();
        String releaseTitle = requiredText(request == null ? null : request.releaseTitle(), "发布标题不能为空");
        String magnet = resolveMagnet(request.indexerId(), request.downloadRef(), releaseTitle);
        return magnetIngestService.createMovieRetryTask(
                originalTask,
                magnet,
                requestMetadata(
                        releaseTitle,
                        request.indexer(),
                        request.size(),
                        request.indexerId(),
                        request.downloadRef(),
                        request.resolutionTags(),
                        request.dynamicRangeTags()
                ).withUnverifiedRisk(request.acceptUnverifiedRisk()),
                retryReference
        );
    }

    public SeriesMagnetIngestTaskResponse ingestSelectedSeriesRetry(
            SeriesMagnetIngestTask originalTask,
            OpenListReleaseRetryRequest request,
            TaskRetryReference retryReference
    ) {
        authService.requireCurrentUser();
        String releaseTitle = requiredText(request == null ? null : request.releaseTitle(), "发布标题不能为空");
        String magnet = resolveMagnet(request.indexerId(), request.downloadRef(), releaseTitle);
        return magnetIngestService.createSeriesRetryTask(
                originalTask,
                magnet,
                requestMetadata(
                        releaseTitle,
                        request.indexer(),
                        request.size(),
                        request.indexerId(),
                        request.downloadRef(),
                        request.resolutionTags(),
                        request.dynamicRangeTags()
                ).withUnverifiedRisk(request.acceptUnverifiedRisk()),
                retryReference
        );
    }

    public AnimeMagnetIngestTaskResponse ingestSelectedAnimeRetry(
            AnimeMagnetIngestTask originalTask,
            OpenListReleaseRetryRequest request,
            TaskRetryReference retryReference
    ) {
        authService.requireCurrentUser();
        String releaseTitle = requiredText(request == null ? null : request.releaseTitle(), "发布标题不能为空");
        String magnet = resolveMagnet(request.indexerId(), request.downloadRef(), releaseTitle);
        return animeMagnetIngestTaskService.createRetryTask(
                originalTask,
                magnet,
                requestMetadata(
                        releaseTitle,
                        request.indexer(),
                        request.size(),
                        request.indexerId(),
                        request.downloadRef(),
                        request.resolutionTags(),
                        request.dynamicRangeTags()
                ),
                retryReference
        );
    }

    /** 每个事件对应真实发现查询的开始/结束。 */
    public record ReleaseSearchProgress(String query, String source, String status, long elapsedMs, Integer resultCount) {}

    private String resolveMagnet(Integer indexerId, String downloadRef, String releaseTitle) {
        return fileTables.resolveSubmissionMagnet(indexerId, downloadRef, releaseTitle);
    }

    private boolean isMagnetReference(String downloadRef) {
        return downloadRef != null && downloadRef.trim().toLowerCase(Locale.ROOT).startsWith("magnet:");
    }

    private ReleaseIngestMetadata requestMetadata(
            String releaseTitle,
            String indexer,
            Long size,
            Integer indexerId,
            String downloadRef,
            List<String> resolutionTags,
            List<String> dynamicRangeTags
    ) {
        return new ReleaseIngestMetadata(
                isMagnetReference(downloadRef) && indexerId == null ? "PANSOU" : "PROWLARR_RELEASE",
                releaseTitle,
                trimToNull(indexer),
                size,
                indexerId,
                downloadRef,
                knownTags(resolutionTags, RESOLUTION_TAGS),
                knownTags(dynamicRangeTags, DYNAMIC_RANGE_TAGS)
        );
    }

    private List<String> knownTags(List<String> tags, List<String> allowed) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String tag : tags) {
            String value = trimToNull(tag);
            if (value != null && allowed.contains(value)) {
                normalized.add(value);
            }
        }
        return List.copyOf(normalized);
    }

    private int validateYear(Integer year) {
        if (year == null || year < 1888) {
            throw badRequest("电影年份无效");
        }
        return year;
    }

    private int validateSeasonNumber(Integer seasonNumber) {
        if (seasonNumber == null || seasonNumber < 1) {
            throw badRequest("季编号必须大于 0");
        }
        return seasonNumber;
    }

    private boolean requiresAnimeSeriesTask(String taskProductType) {
        if (!StringUtils.hasText(taskProductType) || SERIES_PRODUCT_TYPE.equals(taskProductType)) {
            return false;
        }
        if (ANIME_PRODUCT_TYPE.equals(taskProductType)) {
            return true;
        }
        throw badRequest("不支持的任务产品类别");
    }

    private String requiredText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw badRequest(message);
        }
        return value.trim();
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
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

    /** 没有合适推荐与上游查询失败不同；原手动 API 保持原 400 响应。 */
    static class NoMatchingReleaseException extends BusinessException {
        private final String reasonCode;
        private final List<ProwlarrReleaseItemResponse> candidates;

        NoMatchingReleaseException(String message) {
            this("NO_RELEASE", message);
        }

        NoMatchingReleaseException(String reasonCode, String message) {
            this(reasonCode, message, List.of());
        }

        NoMatchingReleaseException(String reasonCode, String message, List<ProwlarrReleaseItemResponse> candidates) {
            super(ErrorCode.BAD_REQUEST, message);
            this.reasonCode = reasonCode;
            this.candidates = List.copyOf(candidates);
        }

        String reasonCode() {
            return reasonCode;
        }
        List<ProwlarrReleaseItemResponse> candidates() { return candidates; }
    }

}
