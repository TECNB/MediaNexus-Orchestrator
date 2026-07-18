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
import com.medianexus.orchestrator.integration.prowlarr.ProwlarrRelease;
import com.medianexus.orchestrator.model.MovieMagnetIngestTask;
import com.medianexus.orchestrator.model.AnimeMagnetIngestTask;
import com.medianexus.orchestrator.model.SeriesMagnetIngestTask;
import com.medianexus.orchestrator.service.ReleaseTitleTagParser.ReleaseTitleTags;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ProwlarrReleaseIngestService {

    private static final Logger log = LoggerFactory.getLogger(ProwlarrReleaseIngestService.class);
    private static final List<String> RESOLUTION_TAGS = List.of("2160p", "1080p", "720p");
    private static final List<String> DYNAMIC_RANGE_TAGS =
            List.of("dolby_vision", "hdr10_plus", "hdr10", "hdr", "hlg", "sdr");
    private static final List<String> HDR_DYNAMIC_TAGS = List.of("hdr10_plus", "hdr10", "hdr", "hlg");
    private static final String SERIES_PRODUCT_TYPE = "SERIES";
    private static final String ANIME_PRODUCT_TYPE = "ANIME";
    private static final List<String> SEARCH_STOP_WORDS = List.of(
            "a", "an", "and", "de", "des", "du", "en", "et", "la", "le", "les", "of", "the", "to"
    );
    private static final Pattern SEARCH_TEXT_SEPARATOR_PATTERN = Pattern.compile("[^0-9a-z\\p{IsHan}]+");
    private static final int MIN_SIGNIFICANT_TOKEN_LENGTH = 2;

    private final AuthService authService;
    private final ProwlarrClient prowlarrClient;
    private final ProwlarrReleaseSearchCache releaseSearchCache;
    private final ReleaseTitleTagParser tagParser;
    private final MagnetIngestService magnetIngestService;
    private final AnimeMagnetIngestTaskService animeMagnetIngestTaskService;

    public ProwlarrReleaseIngestService(
            AuthService authService,
            ProwlarrClient prowlarrClient,
            ProwlarrReleaseSearchCache releaseSearchCache,
            ReleaseTitleTagParser tagParser,
            MagnetIngestService magnetIngestService,
            AnimeMagnetIngestTaskService animeMagnetIngestTaskService
    ) {
        this.authService = authService;
        this.prowlarrClient = prowlarrClient;
        this.releaseSearchCache = releaseSearchCache;
        this.tagParser = tagParser;
        this.magnetIngestService = magnetIngestService;
        this.animeMagnetIngestTaskService = animeMagnetIngestTaskService;
    }

    public ProwlarrReleaseSearchResponse searchReleases(
            String term,
            String mediaType,
            Integer seasonNumber
    ) {
        authService.requireCurrentUser();
        String normalizedMediaType = requiredText(mediaType, "媒体类型不能为空").toLowerCase(Locale.ROOT);
        String query;
        if ("movie".equals(normalizedMediaType)) {
            query = movieQuery(term);
        } else if ("series".equals(normalizedMediaType)) {
            query = seriesQuery(term, validateSeasonNumber(seasonNumber));
        } else {
            throw badRequest("不支持的媒体类型");
        }

        List<ProwlarrReleaseItemResponse> items = search(query).stream()
                .filter(release -> !isMagnetReference(release.downloadRef()))
                .map(release -> {
                    ReleaseTitleTags tags = tagParser.parse(release.title());
                    return toReleaseItem(release, tags, null);
                })
                .toList();
        return new ProwlarrReleaseSearchResponse(query, items);
    }

    public ProwlarrReleaseRecommendationResponse recommendMovieRelease(MovieReleaseRecommendationRequest request) {
        authService.requireCurrentUser();
        MovieReleaseIdentity movie = movieReleaseIdentity(request);
        String quality = normalizeQuality(request.quality());
        return movieRecommendationResponse(
                movie,
                quality,
                searchQueriesInParallel(movieReleaseSearchPlan(movie))
        );
    }

    public ProwlarrReleaseRecommendationResponse refreshMovieReleaseRecommendation(
            MovieReleaseRecommendationRequest request
    ) {
        authService.requireCurrentUser();
        MovieReleaseIdentity movie = movieReleaseIdentity(request);
        String quality = normalizeQuality(request.quality());
        return movieRecommendationResponse(
                movie,
                quality,
                refreshQueriesInParallel(movieReleaseSearchPlan(movie))
        );
    }

    private ProwlarrReleaseRecommendationResponse movieRecommendationResponse(
            MovieReleaseIdentity movie,
            String quality,
            List<MovieReleaseSearchResult> searchResults
    ) {
        ReleaseRecommendation recommendation = recommendMovieReleases(movie, quality, searchResults);
        RecommendedRelease recommendedRelease = recommendation.releases().get(0);
        return new ProwlarrReleaseRecommendationResponse(
                recommendedRelease.query().query(),
                recommendation.requestedQuality(),
                recommendation.selectedQuality(),
                toReleaseItem(recommendedRelease.release(), recommendedRelease.tags(), recommendedRelease.query()),
                recommendation.releases().stream()
                        .map(candidate -> toReleaseItem(candidate.release(), candidate.tags(), candidate.query()))
                        .toList()
        );
    }

    public ProwlarrReleaseSearchResponse searchMovieReleases(MovieReleaseSearchRequest request) {
        authService.requireCurrentUser();
        MovieReleaseIdentity movie = movieReleaseIdentity(request);
        String quality = normalizeOptionalQuality(request.quality());
        return movieReleaseSearchResponse(
                movie,
                quality,
                searchQueriesInParallel(movieReleaseSearchPlan(movie))
        );
    }

    public ProwlarrReleaseSearchResponse refreshMovieReleases(MovieReleaseSearchRequest request) {
        authService.requireCurrentUser();
        MovieReleaseIdentity movie = movieReleaseIdentity(request);
        String quality = normalizeOptionalQuality(request.quality());
        return movieReleaseSearchResponse(
                movie,
                quality,
                refreshQueriesInParallel(movieReleaseSearchPlan(movie))
        );
    }

    private ProwlarrReleaseSearchResponse movieReleaseSearchResponse(
            MovieReleaseIdentity movie,
            String quality,
            List<MovieReleaseSearchResult> searchResults
    ) {
        Map<String, ProwlarrReleaseItemResponse> releasesByReference = new LinkedHashMap<>();
        List<RecommendedRelease> candidates = movieReleaseSearchCandidates(searchResults);

        for (RecommendedRelease candidate : sortMovieReleaseSearchCandidates(movie, quality, candidates)) {
            releasesByReference.putIfAbsent(
                    releaseReference(candidate.release()),
                    toReleaseItem(candidate.release(), candidate.tags(), candidate.query())
            );
        }

        return new ProwlarrReleaseSearchResponse("电影发布搜索计划", List.copyOf(releasesByReference.values()));
    }

    public ProwlarrReleaseRecommendationResponse recommendSeriesRelease(SeriesReleaseRecommendationRequest request) {
        authService.requireCurrentUser();
        SeriesReleaseIdentity series = seriesReleaseIdentity(request);
        String quality = normalizeQuality(request.quality());
        return seriesRecommendationResponse(
                series,
                quality,
                searchQueriesInParallel(seriesReleaseSearchPlan(series))
        );
    }

    public ProwlarrReleaseRecommendationResponse refreshSeriesReleaseRecommendation(
            SeriesReleaseRecommendationRequest request
    ) {
        authService.requireCurrentUser();
        SeriesReleaseIdentity series = seriesReleaseIdentity(request);
        String quality = normalizeQuality(request.quality());
        return seriesRecommendationResponse(
                series,
                quality,
                refreshQueriesInParallel(seriesReleaseSearchPlan(series))
        );
    }

    private ProwlarrReleaseRecommendationResponse seriesRecommendationResponse(
            SeriesReleaseIdentity series,
            String quality,
            List<MovieReleaseSearchResult> searchResults
    ) {
        ReleaseRecommendation recommendation = recommendSeriesReleases(series, quality, searchResults);
        RecommendedRelease recommendedRelease = recommendation.releases().get(0);
        return new ProwlarrReleaseRecommendationResponse(
                recommendedRelease.query().query(),
                recommendation.requestedQuality(),
                recommendation.selectedQuality(),
                toReleaseItem(recommendedRelease.release(), recommendedRelease.tags(), recommendedRelease.query()),
                recommendation.releases().stream()
                        .map(candidate -> toReleaseItem(candidate.release(), candidate.tags(), candidate.query()))
                        .toList()
        );
    }

    public ProwlarrReleaseSearchResponse searchSeriesReleases(SeriesReleaseSearchRequest request) {
        authService.requireCurrentUser();
        SeriesReleaseIdentity series = seriesReleaseIdentity(request);
        String quality = normalizeOptionalQuality(request.quality());
        return seriesReleaseSearchResponse(
                series,
                quality,
                searchQueriesInParallel(seriesReleaseSearchPlan(series))
        );
    }

    public ProwlarrReleaseSearchResponse refreshSeriesReleases(SeriesReleaseSearchRequest request) {
        authService.requireCurrentUser();
        SeriesReleaseIdentity series = seriesReleaseIdentity(request);
        String quality = normalizeOptionalQuality(request.quality());
        return seriesReleaseSearchResponse(
                series,
                quality,
                refreshQueriesInParallel(seriesReleaseSearchPlan(series))
        );
    }

    private ProwlarrReleaseSearchResponse seriesReleaseSearchResponse(
            SeriesReleaseIdentity series,
            String quality,
            List<MovieReleaseSearchResult> searchResults
    ) {
        List<SearchTitleTerm> titleTerms = titleTerms(series);
        Map<String, ProwlarrReleaseItemResponse> releasesByReference = new LinkedHashMap<>();
        List<RecommendedRelease> candidates = movieReleaseSearchCandidates(searchResults).stream()
                .filter(candidate -> matchesSeriesSearchCandidate(series, titleTerms, candidate))
                .toList();

        for (RecommendedRelease candidate : sortSeriesReleaseSearchCandidates(series, quality, candidates)) {
            releasesByReference.putIfAbsent(
                    releaseReference(candidate.release()),
                    toReleaseItem(candidate.release(), candidate.tags(), candidate.query())
            );
        }

        return new ProwlarrReleaseSearchResponse("剧集发布搜索计划", List.copyOf(releasesByReference.values()));
    }

    public MovieMagnetIngestTaskResponse ingestMovie(MovieOpenListIngestRequest request) {
        authService.requireCurrentUser();
        String title = requiredText(request == null ? null : request.title(), "电影标题不能为空");
        String quality = normalizeQuality(request == null ? null : request.quality());
        String query = movieQuery(request == null ? null : request.term());
        SelectedRelease selectedRelease = selectRelease(query, quality);
        String magnet = resolveMagnet(selectedRelease.release());
        return magnetIngestService.createMovieTask(
                new MovieMagnetIngestRequest(
                        magnet,
                        title,
                        trimToNull(request.originalTitle()),
                        request.year()
                ),
                metadata(selectedRelease.release(), selectedRelease.tags())
        );
    }

    public SeriesMagnetIngestTaskResponse ingestSeries(SeriesOpenListIngestRequest request) {
        authService.requireCurrentUser();
        String title = requiredText(request == null ? null : request.title(), "剧集标题不能为空");
        String quality = normalizeQuality(request == null ? null : request.quality());
        int seasonNumber = validateSeasonNumber(request == null ? null : request.seasonNumber());
        String query = seriesQuery(request == null ? null : request.term(), seasonNumber);
        SelectedRelease selectedRelease = selectRelease(query, quality);
        String magnet = resolveMagnet(selectedRelease.release());
        SeriesMagnetIngestRequest ingestRequest = new SeriesMagnetIngestRequest(
                magnet,
                title,
                trimToNull(request.originalTitle()),
                seasonNumber
        );
        ReleaseIngestMetadata releaseMetadata = metadata(selectedRelease.release(), selectedRelease.tags());
        if (requiresAnimeSeriesTask(request.taskProductType())) {
            return magnetIngestService.createAnimeSeasonSeriesTask(ingestRequest, releaseMetadata);
        }
        return magnetIngestService.createSeriesTask(ingestRequest, releaseMetadata);
    }

    public MovieMagnetIngestTaskResponse ingestSelectedMovie(MovieReleaseOpenListIngestRequest request) {
        authService.requireCurrentUser();
        String title = requiredText(request == null ? null : request.title(), "电影标题不能为空");
        String releaseTitle = requiredText(request == null ? null : request.releaseTitle(), "发布标题不能为空");
        int year = validateYear(request == null ? null : request.year());
        String magnet = resolveMagnet(request.indexerId(), request.downloadRef(), releaseTitle);
        return magnetIngestService.createMovieTask(
                new MovieMagnetIngestRequest(magnet, title, trimToNull(request.originalTitle()), year),
                requestMetadata(
                        releaseTitle,
                        request.indexer(),
                        request.size(),
                        request.indexerId(),
                        request.resolutionTags(),
                        request.dynamicRangeTags()
                )
        );
    }

    public SeriesMagnetIngestTaskResponse ingestSelectedSeries(SeriesReleaseOpenListIngestRequest request) {
        authService.requireCurrentUser();
        String title = requiredText(request == null ? null : request.title(), "剧集标题不能为空");
        String releaseTitle = requiredText(request == null ? null : request.releaseTitle(), "发布标题不能为空");
        int seasonNumber = validateSeasonNumber(request == null ? null : request.seasonNumber());
        String magnet = resolveMagnet(request.indexerId(), request.downloadRef(), releaseTitle);
        SeriesMagnetIngestRequest ingestRequest =
                new SeriesMagnetIngestRequest(magnet, title, trimToNull(request.originalTitle()), seasonNumber);
        ReleaseIngestMetadata releaseMetadata = requestMetadata(
                releaseTitle,
                request.indexer(),
                request.size(),
                request.indexerId(),
                request.resolutionTags(),
                request.dynamicRangeTags()
        );
        if (requiresAnimeSeriesTask(request.taskProductType())) {
            return magnetIngestService.createAnimeSeasonSeriesTask(ingestRequest, releaseMetadata);
        }
        return magnetIngestService.createSeriesTask(ingestRequest, releaseMetadata);
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
                        request.resolutionTags(),
                        request.dynamicRangeTags()
                ),
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
                        request.resolutionTags(),
                        request.dynamicRangeTags()
                ),
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
                        request.resolutionTags(),
                        request.dynamicRangeTags()
                ),
                retryReference
        );
    }

    /**
     * Movie release search is anchored to the selected catalog movie instead of
     * the user's outer search term. Display and original titles are searched
     * across all enabled indexers; every release still has to pass title
     * relevance, quality and seeder checks, and stable-reference de-duplication
     * keeps one explainable match source for the confirmation UI and manual
     * release list.
     */
    private ReleaseRecommendation recommendMovieReleases(
            MovieReleaseIdentity movie,
            String quality,
            List<MovieReleaseSearchResult> searchResults
    ) {
        List<SearchTitleTerm> titleTerms = titleTerms(movie);
        Map<String, RecommendedRelease> releasesByReference = new LinkedHashMap<>();
        for (MovieReleaseSearchResult searchResult : searchResults) {
            for (RecommendedRelease candidate : movieReleaseSearchCandidates(List.of(searchResult)).stream()
                    .filter(release -> hasActivePeers(release.release()))
                    .filter(release -> matchesAnyTitle(release.release().title(), titleTerms))
                    .toList()) {
                releasesByReference.putIfAbsent(releaseReference(candidate.release()), candidate);
            }
        }

        List<RecommendedRelease> candidates = List.copyOf(releasesByReference.values());
        List<RecommendedRelease> recommendedReleases = selectRecommendedMovieReleases(
                movie,
                candidates,
                quality
        );
        if (!recommendedReleases.isEmpty()) {
            return new ReleaseRecommendation(quality, quality, recommendedReleases);
        }

        String fallbackQualityTag = fallbackQuality(quality);
        if (fallbackQualityTag != null) {
            List<RecommendedRelease> fallbackRecommendations = selectRecommendedMovieReleases(
                    movie,
                    candidates,
                    fallbackQualityTag
            );
            if (!fallbackRecommendations.isEmpty()) {
                return new ReleaseRecommendation(quality, fallbackQualityTag, fallbackRecommendations);
            }
        }
        throw badRequest("未找到匹配分辨率且有做种的电影发布资源");
    }

    private ReleaseRecommendation recommendSeriesReleases(
            SeriesReleaseIdentity series,
            String quality,
            List<MovieReleaseSearchResult> searchResults
    ) {
        List<SearchTitleTerm> titleTerms = titleTerms(series);
        Map<String, RecommendedRelease> releasesByReference = new LinkedHashMap<>();
        for (MovieReleaseSearchResult searchResult : searchResults) {
            for (RecommendedRelease candidate : movieReleaseSearchCandidates(List.of(searchResult)).stream()
                    .filter(release -> hasActivePeers(release.release()))
                    .filter(release -> matchesSeriesSearchCandidate(series, titleTerms, release))
                    .toList()) {
                releasesByReference.putIfAbsent(releaseReference(candidate.release()), candidate);
            }
        }

        List<RecommendedRelease> candidates = List.copyOf(releasesByReference.values());
        List<RecommendedRelease> recommendedReleases = selectRecommendedSeriesReleases(
                series,
                candidates,
                quality
        );
        if (!recommendedReleases.isEmpty()) {
            return new ReleaseRecommendation(quality, quality, recommendedReleases);
        }

        String fallbackQualityTag = fallbackQuality(quality);
        if (fallbackQualityTag != null) {
            List<RecommendedRelease> fallbackRecommendations = selectRecommendedSeriesReleases(
                    series,
                    candidates,
                    fallbackQualityTag
            );
            if (!fallbackRecommendations.isEmpty()) {
                return new ReleaseRecommendation(quality, fallbackQualityTag, fallbackRecommendations);
            }
        }
        throw badRequest("未找到匹配分辨率且有做种的剧集发布资源");
    }

    private List<RecommendedRelease> movieReleaseSearchCandidates(List<MovieReleaseSearchResult> searchResults) {
        List<RecommendedRelease> candidates = new ArrayList<>();
        for (MovieReleaseSearchResult searchResult : searchResults) {
            for (ProwlarrRelease release : searchResult.releases()) {
                if (!isMagnetReference(release.downloadRef())) {
                    candidates.add(new RecommendedRelease(
                            searchResult.query(),
                            release,
                            tagParser.parse(release.title())
                    ));
                }
            }
        }
        return candidates;
    }

    private List<RecommendedRelease> sortMovieReleaseSearchCandidates(
            MovieReleaseIdentity movie,
            String quality,
            List<RecommendedRelease> candidates
    ) {
        return candidates.stream()
                .sorted(Comparator
                        .comparingInt((RecommendedRelease candidate) -> movieReleaseQualityPriority(candidate, quality))
                        .thenComparingInt(candidate -> activePeersPriority(candidate.release()))
                        .thenComparingInt(candidate -> movieReleaseTitleBucket(movie, candidate))
                        .thenComparingInt(candidate -> movieReleaseSearchSourcePriority(candidate.query()))
                        .thenComparingInt(candidate -> seederHealthPriority(candidate.release()))
                        .thenComparing((RecommendedRelease candidate) -> sizeValue(candidate.release()), Comparator.reverseOrder())
                        .thenComparing((RecommendedRelease candidate) -> seedersValue(candidate.release()), Comparator.reverseOrder())
                        .thenComparingInt(candidate -> dynamicPriority(candidate.tags()))
                        .thenComparing((RecommendedRelease candidate) -> grabsValue(candidate.release()), Comparator.reverseOrder())
                        .thenComparing((RecommendedRelease candidate) -> leechersValue(candidate.release()), Comparator.reverseOrder()))
                .toList();
    }

    private List<RecommendedRelease> sortSeriesReleaseSearchCandidates(
            SeriesReleaseIdentity series,
            String quality,
            List<RecommendedRelease> candidates
    ) {
        return candidates.stream()
                .sorted(Comparator
                        .comparingInt((RecommendedRelease candidate) -> movieReleaseQualityPriority(candidate, quality))
                        .thenComparingInt(candidate -> activePeersPriority(candidate.release()))
                        .thenComparingInt(candidate -> seriesReleaseTitleBucket(series, candidate))
                        .thenComparingInt(candidate -> seriesReleaseSearchSourcePriority(candidate.query()))
                        .thenComparingInt(candidate -> seederHealthPriority(candidate.release()))
                        .thenComparing((RecommendedRelease candidate) -> sizeValue(candidate.release()), Comparator.reverseOrder())
                        .thenComparing((RecommendedRelease candidate) -> seedersValue(candidate.release()), Comparator.reverseOrder())
                        .thenComparingInt(candidate -> dynamicPriority(candidate.tags()))
                        .thenComparing((RecommendedRelease candidate) -> grabsValue(candidate.release()), Comparator.reverseOrder())
                        .thenComparing((RecommendedRelease candidate) -> leechersValue(candidate.release()), Comparator.reverseOrder()))
                .toList();
    }

    private int movieReleaseTitleBucket(MovieReleaseIdentity movie, RecommendedRelease candidate) {
        SearchTitleTerm displayTitle = titleTerm(movie.title());
        if (displayTitle != null && matchesTitle(candidate.release().title(), displayTitle)) {
            return 0;
        }
        if (!normalizeSearchText(movie.title()).equals(normalizeSearchText(movie.originalTitle()))) {
            SearchTitleTerm originalTitle = titleTerm(movie.originalTitle());
            if (originalTitle != null && matchesTitle(candidate.release().title(), originalTitle)) {
                return 1;
            }
        }
        return 2;
    }

    private int seriesReleaseTitleBucket(SeriesReleaseIdentity series, RecommendedRelease candidate) {
        SearchTitleTerm displayTitle = titleTerm(series.title());
        if (displayTitle != null && matchesTitle(candidate.release().title(), displayTitle)) {
            return 0;
        }
        if (!normalizeSearchText(series.title()).equals(normalizeSearchText(series.originalTitle()))) {
            SearchTitleTerm originalTitle = titleTerm(series.originalTitle());
            if (originalTitle != null && matchesTitle(candidate.release().title(), originalTitle)) {
                return 1;
            }
        }
        return 2;
    }

    private int movieReleaseQualityPriority(RecommendedRelease candidate, String quality) {
        if (!StringUtils.hasText(quality)) {
            return 0;
        }
        return candidate.tags().resolutionTags().contains(quality) ? 0 : 1;
    }

    private int movieReleaseSearchSourcePriority(MovieReleaseSearchQuery query) {
        if ("展示标题".equals(query.source())) {
            return 0;
        }
        if ("原始标题".equals(query.source())) {
            return 1;
        }
        return 2;
    }

    private int seriesReleaseSearchSourcePriority(MovieReleaseSearchQuery query) {
        if ("展示标题".equals(query.source())) {
            return 0;
        }
        if ("原始标题".equals(query.source())) {
            return 1;
        }
        return 2;
    }

    private List<RecommendedRelease> selectRecommendedMovieReleases(
            MovieReleaseIdentity movie,
            List<RecommendedRelease> candidates,
            String quality
    ) {
        List<RecommendedRelease> qualityMatchedCandidates = candidates.stream()
                .filter(candidate -> candidate.tags().resolutionTags().contains(quality))
                .toList();
        List<RecommendedRelease> recommendations = new ArrayList<>();
        LinkedHashSet<String> selectedReferences = new LinkedHashSet<>();
        addBucketRecommendations(
                recommendations,
                selectedReferences,
                titleMatchedCandidates(qualityMatchedCandidates, movie.title())
        );
        if (!normalizeSearchText(movie.title()).equals(normalizeSearchText(movie.originalTitle()))) {
            addBucketRecommendations(
                    recommendations,
                    selectedReferences,
                    titleMatchedCandidates(qualityMatchedCandidates, movie.originalTitle())
            );
        }
        return recommendations;
    }

    private List<RecommendedRelease> selectRecommendedSeriesReleases(
            SeriesReleaseIdentity series,
            List<RecommendedRelease> candidates,
            String quality
    ) {
        List<RecommendedRelease> qualityMatchedCandidates = candidates.stream()
                .filter(candidate -> candidate.tags().resolutionTags().contains(quality))
                .toList();
        List<RecommendedRelease> recommendations = new ArrayList<>();
        LinkedHashSet<String> selectedReferences = new LinkedHashSet<>();
        addBucketRecommendations(
                recommendations,
                selectedReferences,
                titleMatchedCandidates(qualityMatchedCandidates, series.title())
        );
        if (!normalizeSearchText(series.title()).equals(normalizeSearchText(series.originalTitle()))) {
            addBucketRecommendations(
                    recommendations,
                    selectedReferences,
                    titleMatchedCandidates(qualityMatchedCandidates, series.originalTitle())
            );
        }
        return recommendations;
    }

    private List<RecommendedRelease> titleMatchedCandidates(
            List<RecommendedRelease> candidates,
            String title
    ) {
        SearchTitleTerm term = titleTerm(title);
        if (term == null) {
            return List.of();
        }
        return candidates.stream()
                .filter(candidate -> matchesTitle(candidate.release().title(), term))
                .toList();
    }

    private void addBucketRecommendations(
            List<RecommendedRelease> recommendations,
            LinkedHashSet<String> selectedReferences,
            List<RecommendedRelease> candidates
    ) {
        addBestRecommendation(recommendations, selectedReferences, candidates, movieReleaseSizeComparator());
        addBestRecommendation(recommendations, selectedReferences, candidates, movieReleaseSeedersComparator());
    }

    private void addBestRecommendation(
            List<RecommendedRelease> recommendations,
            LinkedHashSet<String> selectedReferences,
            List<RecommendedRelease> candidates,
            Comparator<RecommendedRelease> comparator
    ) {
        candidates.stream()
                .sorted(comparator)
                .filter(candidate -> !selectedReferences.contains(releaseReference(candidate.release())))
                .findFirst()
                .ifPresent(candidate -> {
                    selectedReferences.add(releaseReference(candidate.release()));
                    recommendations.add(candidate);
                });
    }

    private Comparator<RecommendedRelease> movieReleaseSizeComparator() {
        return Comparator
                .comparingInt((RecommendedRelease candidate) -> seederHealthPriority(candidate.release()))
                .thenComparing((RecommendedRelease candidate) -> sizeValue(candidate.release()), Comparator.reverseOrder())
                .thenComparing((RecommendedRelease candidate) -> seedersValue(candidate.release()), Comparator.reverseOrder())
                .thenComparingInt(candidate -> dynamicPriority(candidate.tags()))
                .thenComparing((RecommendedRelease candidate) -> grabsValue(candidate.release()), Comparator.reverseOrder())
                .thenComparing((RecommendedRelease candidate) -> leechersValue(candidate.release()), Comparator.reverseOrder());
    }

    private Comparator<RecommendedRelease> movieReleaseSeedersComparator() {
        return Comparator
                .comparing((RecommendedRelease candidate) -> seedersValue(candidate.release()), Comparator.reverseOrder())
                .thenComparing((RecommendedRelease candidate) -> sizeValue(candidate.release()), Comparator.reverseOrder())
                .thenComparingInt(candidate -> dynamicPriority(candidate.tags()))
                .thenComparing((RecommendedRelease candidate) -> grabsValue(candidate.release()), Comparator.reverseOrder())
                .thenComparing((RecommendedRelease candidate) -> leechersValue(candidate.release()), Comparator.reverseOrder());
    }

    private List<MovieReleaseSearchQuery> titleSearchQueries(MovieReleaseIdentity movie) {
        LinkedHashSet<String> seenTitles = new LinkedHashSet<>();
        List<MovieReleaseSearchQuery> queries = new ArrayList<>();
        addTitleSearchQuery(queries, seenTitles, "原始标题", movie.originalTitle(), movie.year());
        addTitleSearchQuery(queries, seenTitles, "展示标题", movie.title(), movie.year());
        return queries;
    }

    private void addTitleSearchQuery(
            List<MovieReleaseSearchQuery> queries,
            LinkedHashSet<String> seenTitles,
            String source,
            String title,
            int year
    ) {
        String normalizedTitle = normalizeSearchText(title);
        if (!StringUtils.hasText(normalizedTitle) || !seenTitles.add(normalizedTitle)) {
            return;
        }
        queries.add(new MovieReleaseSearchQuery(source, title.trim() + " " + year));
    }

    private List<MovieReleaseSearchQuery> movieReleaseSearchPlan(MovieReleaseIdentity movie) {
        return titleSearchQueries(movie);
    }

    private List<MovieReleaseSearchQuery> seriesTitleSearchQueries(SeriesReleaseIdentity series) {
        LinkedHashSet<String> seenTitles = new LinkedHashSet<>();
        List<MovieReleaseSearchQuery> queries = new ArrayList<>();
        addSeriesTitleSearchQueries(queries, seenTitles, "展示标题", series.title(), series.seasonNumber());
        addSeriesTitleSearchQueries(
                queries,
                seenTitles,
                "原始标题",
                series.originalTitle(),
                series.seasonNumber()
        );
        return queries;
    }

    private void addSeriesTitleSearchQueries(
            List<MovieReleaseSearchQuery> queries,
            LinkedHashSet<String> seenTitles,
            String source,
            String title,
            int seasonNumber
    ) {
        String normalizedTitle = normalizeSearchText(title);
        if (!StringUtils.hasText(normalizedTitle) || !seenTitles.add(normalizedTitle)) {
            return;
        }
        String trimmedTitle = title.trim();
        queries.add(new MovieReleaseSearchQuery(
                source,
                trimmedTitle + " " + seasonQuerySuffix(seasonNumber)
        ));
        queries.add(new MovieReleaseSearchQuery(source, trimmedTitle));
    }

    private List<MovieReleaseSearchQuery> seriesReleaseSearchPlan(SeriesReleaseIdentity series) {
        return seriesTitleSearchQueries(series);
    }

    private List<SearchTitleTerm> titleTerms(MovieReleaseIdentity movie) {
        LinkedHashSet<SearchTitleTerm> terms = new LinkedHashSet<>();
        SearchTitleTerm titleTerm = titleTerm(movie.title());
        if (titleTerm != null) {
            terms.add(titleTerm);
        }
        SearchTitleTerm originalTitleTerm = titleTerm(movie.originalTitle());
        if (originalTitleTerm != null) {
            terms.add(originalTitleTerm);
        }
        return List.copyOf(terms);
    }

    private List<SearchTitleTerm> titleTerms(SeriesReleaseIdentity series) {
        LinkedHashSet<SearchTitleTerm> terms = new LinkedHashSet<>();
        SearchTitleTerm titleTerm = titleTerm(series.title());
        if (titleTerm != null) {
            terms.add(titleTerm);
        }
        SearchTitleTerm originalTitleTerm = titleTerm(series.originalTitle());
        if (originalTitleTerm != null) {
            terms.add(originalTitleTerm);
        }
        return List.copyOf(terms);
    }

    private SearchTitleTerm titleTerm(String title) {
        String normalizedTitle = normalizeSearchText(title);
        if (StringUtils.hasText(normalizedTitle)) {
            return new SearchTitleTerm(normalizedTitle, significantSearchTokens(title));
        }
        return null;
    }

    private SelectedRelease selectRelease(String query, String quality) {
        for (ProwlarrRelease release : search(query)) {
            ReleaseTitleTags tags = tagParser.parse(release.title());
            if (tags.resolutionTags().contains(quality)) {
                return new SelectedRelease(release, tags);
            }
        }
        throw badRequest("未找到匹配分辨率的发布资源");
    }

    private List<ProwlarrRelease> search(String query) {
        try {
            return releaseSearchCache.search(query);
        } catch (ProwlarrClientException exception) {
            if (exception.getReason() == ProwlarrClientException.Reason.CONFIGURATION) {
                throw serviceUnavailable("Prowlarr 服务尚未配置");
            }
            log.warn("Prowlarr search failed query={} reason={}", logValue(query), exception.getMessage(), exception);
            throw internalError("Prowlarr 搜索失败，请稍后重试");
        } catch (IllegalArgumentException exception) {
            log.warn("Prowlarr search configuration has invalid URI query={}", logValue(query), exception);
            throw serviceUnavailable("Prowlarr 服务尚未配置");
        }
    }

    private List<MovieReleaseSearchResult> searchQueriesInParallel(List<MovieReleaseSearchQuery> queries) {
        List<CompletableFuture<MovieReleaseSearchResult>> futures = queries.stream()
                .map(query -> CompletableFuture.supplyAsync(() -> executeReleaseSearch(query)))
                .toList();
        return futures.stream()
                .map(this::joinSearchResult)
                .toList();
    }

    private List<MovieReleaseSearchResult> refreshQueriesInParallel(List<MovieReleaseSearchQuery> queries) {
        try {
            Map<String, List<ProwlarrRelease>> refreshedReleases = releaseSearchCache.refreshQueries(
                    queries.stream().map(MovieReleaseSearchQuery::query).toList()
            );
            return queries.stream()
                    .map(query -> new MovieReleaseSearchResult(query, refreshedReleases.get(query.query())))
                    .toList();
        } catch (ProwlarrClientException exception) {
            if (exception.getReason() == ProwlarrClientException.Reason.CONFIGURATION) {
                throw serviceUnavailable("Prowlarr 服务尚未配置");
            }
            log.warn("Prowlarr release refresh failed reason={}", exception.getMessage(), exception);
            throw internalError("Prowlarr 刷新失败，请稍后重试");
        } catch (IllegalArgumentException exception) {
            log.warn("Prowlarr release refresh configuration has invalid URI", exception);
            throw serviceUnavailable("Prowlarr 服务尚未配置");
        }
    }

    private MovieReleaseSearchResult executeReleaseSearch(MovieReleaseSearchQuery query) {
        long startedAt = System.nanoTime();
        try {
            List<ProwlarrRelease> releases = search(query.query());
            log.info(
                    "Prowlarr release search completed source={} query={} elapsedMs={} resultCount={}",
                    query.source(),
                    logValue(query.query()),
                    elapsedMillis(startedAt),
                    releases.size()
            );
            return new MovieReleaseSearchResult(query, releases);
        } catch (RuntimeException exception) {
            log.warn(
                    "Prowlarr release search failed source={} query={} elapsedMs={}",
                    query.source(),
                    logValue(query.query()),
                    elapsedMillis(startedAt)
            );
            throw exception;
        }
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private MovieReleaseSearchResult joinSearchResult(CompletableFuture<MovieReleaseSearchResult> future) {
        try {
            return future.join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof BusinessException businessException) {
                throw businessException;
            }
            throw exception;
        }
    }

    private String resolveMagnet(ProwlarrRelease release) {
        return resolveMagnet(release.indexerId(), release.downloadRef(), release.title());
    }

    private String resolveMagnet(Integer indexerId, String downloadRef, String releaseTitle) {
        try {
            return prowlarrClient.resolveMagnet(indexerId, downloadRef, releaseTitle);
        } catch (ProwlarrClientException exception) {
            if (exception.getReason() == ProwlarrClientException.Reason.CONFIGURATION) {
                throw serviceUnavailable("Prowlarr 服务尚未配置");
            }
            log.warn(
                    "Prowlarr release magnet resolve failed title={} indexerId={} reason={}",
                    logValue(releaseTitle),
                    indexerId,
                    exception.getMessage()
            );
            throw badRequest("发布资源无法解析为 magnet 链接");
        } catch (IllegalArgumentException exception) {
            log.warn("Prowlarr release reference has invalid URI title={}", logValue(releaseTitle), exception);
            throw badRequest("发布资源无法解析为 magnet 链接");
        }
    }

    private boolean isMagnetReference(String downloadRef) {
        return downloadRef != null && downloadRef.trim().toLowerCase(Locale.ROOT).startsWith("magnet:");
    }

    private ReleaseIngestMetadata metadata(ProwlarrRelease release, ReleaseTitleTags tags) {
        return new ReleaseIngestMetadata(
                "PROWLARR_RELEASE",
                release.title(),
                release.indexer(),
                release.size(),
                release.indexerId(),
                release.guid(),
                tags.resolutionTags(),
                tags.dynamicRangeTags()
        );
    }

    private ReleaseIngestMetadata requestMetadata(
            String releaseTitle,
            String indexer,
            Long size,
            Integer indexerId,
            List<String> resolutionTags,
            List<String> dynamicRangeTags
    ) {
        return new ReleaseIngestMetadata(
                "PROWLARR_RELEASE",
                releaseTitle,
                trimToNull(indexer),
                size,
                indexerId,
                null,
                knownTags(resolutionTags, RESOLUTION_TAGS),
                knownTags(dynamicRangeTags, DYNAMIC_RANGE_TAGS)
        );
    }

    private ProwlarrReleaseItemResponse toReleaseItem(
            ProwlarrRelease release,
            ReleaseTitleTags tags,
            MovieReleaseSearchQuery match
    ) {
        return new ProwlarrReleaseItemResponse(
                release.title(),
                release.size(),
                release.seeders(),
                release.leechers(),
                release.grabs(),
                release.indexer(),
                release.publishDate(),
                release.indexerId(),
                release.downloadRef(),
                tags.resolutionTags(),
                tags.dynamicRangeTags(),
                tags.seasonTags(),
                match == null ? null : match.source(),
                match == null ? null : match.query()
        );
    }

    private MovieReleaseIdentity movieReleaseIdentity(MovieReleaseRecommendationRequest request) {
        return new MovieReleaseIdentity(
                request.tmdbId(),
                trimToNull(request.imdbId()),
                request.title().trim(),
                trimToNull(request.originalTitle()),
                request.year()
        );
    }

    private MovieReleaseIdentity movieReleaseIdentity(MovieReleaseSearchRequest request) {
        return new MovieReleaseIdentity(
                request.tmdbId(),
                trimToNull(request.imdbId()),
                request.title().trim(),
                trimToNull(request.originalTitle()),
                request.year()
        );
    }

    private SeriesReleaseIdentity seriesReleaseIdentity(SeriesReleaseRecommendationRequest request) {
        return new SeriesReleaseIdentity(
                request.tvdbId(),
                request.tmdbId(),
                trimToNull(request.imdbId()),
                request.title().trim(),
                trimToNull(request.originalTitle()),
                validateSeasonNumber(request.seasonNumber())
        );
    }

    private SeriesReleaseIdentity seriesReleaseIdentity(SeriesReleaseSearchRequest request) {
        return new SeriesReleaseIdentity(
                request.tvdbId(),
                request.tmdbId(),
                trimToNull(request.imdbId()),
                request.title().trim(),
                trimToNull(request.originalTitle()),
                validateSeasonNumber(request.seasonNumber())
        );
    }

    private String releaseReference(ProwlarrRelease release) {
        if (release.indexerId() != null && StringUtils.hasText(release.downloadRef())) {
            return "download:" + release.indexerId() + ":" + release.downloadRef().trim();
        }
        if (StringUtils.hasText(release.guid())) {
            return "guid:" + release.guid().trim();
        }
        return "title:" + normalizeSearchText(release.title());
    }

    private boolean hasActivePeers(ProwlarrRelease release) {
        return release.seeders() != null && release.seeders() > 0;
    }

    private int activePeersPriority(ProwlarrRelease release) {
        return hasActivePeers(release) ? 0 : 1;
    }

    private boolean matchesAnyTitle(String releaseTitle, List<SearchTitleTerm> titleTerms) {
        String normalizedTitle = normalizeSearchText(releaseTitle);
        List<String> releaseTokens = significantSearchTokens(releaseTitle);
        return titleTerms.stream().anyMatch(term -> matchesTitle(normalizedTitle, releaseTokens, term));
    }

    private boolean matchesSeriesSearchCandidate(
            SeriesReleaseIdentity series,
            List<SearchTitleTerm> titleTerms,
            RecommendedRelease candidate
    ) {
        if (!candidate.tags().seasonTags().contains(seasonQuerySuffix(series.seasonNumber()))) {
            return false;
        }
        SearchTitleTerm sourceTitle = switch (candidate.query().source()) {
            case "展示标题" -> titleTerm(series.title());
            case "原始标题" -> titleTerm(series.originalTitle());
            default -> null;
        };
        if (sourceTitle != null) {
            return matchesTitle(candidate.release().title(), sourceTitle);
        }
        return matchesAnyTitle(candidate.release().title(), titleTerms);
    }

    private boolean matchesTitle(String releaseTitle, SearchTitleTerm term) {
        return matchesTitle(normalizeSearchText(releaseTitle), significantSearchTokens(releaseTitle), term);
    }

    private boolean matchesTitle(String normalizedTitle, List<String> releaseTokens, SearchTitleTerm term) {
        return normalizedTitle.contains(term.normalizedTitle())
                || (!term.significantTokens().isEmpty() && releaseTokens.containsAll(term.significantTokens()));
    }

    private String normalizeSearchText(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String asciiComparable = Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return SEARCH_TEXT_SEPARATOR_PATTERN.matcher(asciiComparable).replaceAll("");
    }

    private List<String> significantSearchTokens(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        String asciiComparable = Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String token : SEARCH_TEXT_SEPARATOR_PATTERN.split(asciiComparable)) {
            if (token.length() >= MIN_SIGNIFICANT_TOKEN_LENGTH && !SEARCH_STOP_WORDS.contains(token)) {
                tokens.add(token);
            }
        }
        return List.copyOf(tokens);
    }

    private int dynamicPriority(ReleaseTitleTags tags) {
        if (tags.dynamicRangeTags().contains("dolby_vision")) {
            return 2;
        }
        for (String tag : tags.dynamicRangeTags()) {
            if (HDR_DYNAMIC_TAGS.contains(tag)) {
                return 1;
            }
        }
        return 0;
    }

    private int seederHealthPriority(ProwlarrRelease release) {
        return seedersValue(release) >= 3 ? 0 : 1;
    }

    private long sizeValue(ProwlarrRelease release) {
        return release.size() == null ? 0L : release.size();
    }

    private int seedersValue(ProwlarrRelease release) {
        return release.seeders() == null ? 0 : release.seeders();
    }

    private int grabsValue(ProwlarrRelease release) {
        return release.grabs() == null ? 0 : release.grabs();
    }

    private int leechersValue(ProwlarrRelease release) {
        return release.leechers() == null ? 0 : release.leechers();
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

    private String movieQuery(String term) {
        return requiredText(term, "搜索关键词不能为空");
    }

    private String seriesQuery(String term, int seasonNumber) {
        return requiredText(term, "搜索关键词不能为空") + " " + seasonQuerySuffix(seasonNumber);
    }

    private String seasonQuerySuffix(int seasonNumber) {
        return "S" + String.format("%02d", seasonNumber);
    }

    private String normalizeQuality(String quality) {
        String normalized = requiredText(quality, "请选择分辨率").toLowerCase(Locale.ROOT);
        if (!RESOLUTION_TAGS.contains(normalized)) {
            throw badRequest("不支持的分辨率标签");
        }
        return normalized;
    }

    private String normalizeOptionalQuality(String quality) {
        if (!StringUtils.hasText(quality)) {
            return null;
        }
        return normalizeQuality(quality);
    }

    private String fallbackQuality(String requestedQuality) {
        return "2160p".equals(requestedQuality) ? "1080p" : null;
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

    private BusinessException internalError(String message) {
        return new BusinessException(ErrorCode.INTERNAL_ERROR, message, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private record MovieReleaseIdentity(
            Integer tmdbId,
            String imdbId,
            String title,
            String originalTitle,
            int year
    ) {
    }

    private record SeriesReleaseIdentity(
            Integer tvdbId,
            Integer tmdbId,
            String imdbId,
            String title,
            String originalTitle,
            int seasonNumber
    ) {
    }

    private record MovieReleaseSearchQuery(String source, String query) {
    }

    private record MovieReleaseSearchResult(MovieReleaseSearchQuery query, List<ProwlarrRelease> releases) {
    }

    private record SearchTitleTerm(String normalizedTitle, List<String> significantTokens) {
    }

    private record TaggedRelease(ProwlarrRelease release, ReleaseTitleTags tags) {
    }

    private record RecommendedRelease(
            MovieReleaseSearchQuery query,
            ProwlarrRelease release,
            ReleaseTitleTags tags
    ) {
    }

    private record ReleaseRecommendation(
            String requestedQuality,
            String selectedQuality,
            List<RecommendedRelease> releases
    ) {
    }

    private record SelectedRelease(ProwlarrRelease release, ReleaseTitleTags tags) {
    }
}
