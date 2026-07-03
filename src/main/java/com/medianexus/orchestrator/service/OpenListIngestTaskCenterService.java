package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.dto.magnet.response.AnimeMagnetIngestTaskResponse;
import com.medianexus.orchestrator.dto.magnet.response.MovieMagnetIngestTaskResponse;
import com.medianexus.orchestrator.dto.magnet.response.SeriesMagnetIngestTaskResponse;
import com.medianexus.orchestrator.dto.taskcenter.request.OpenListManualMagnetRetryRequest;
import com.medianexus.orchestrator.dto.taskcenter.request.OpenListAdultBatchRetryRequest;
import com.medianexus.orchestrator.dto.taskcenter.request.OpenListReleaseRetryRequest;
import com.medianexus.orchestrator.dto.taskcenter.response.OpenListIngestTaskCenterAttemptChainResponse;
import com.medianexus.orchestrator.dto.taskcenter.response.OpenListIngestTaskCenterAttemptResponse;
import com.medianexus.orchestrator.dto.taskcenter.response.OpenListIngestTaskCenterDetailResponse;
import com.medianexus.orchestrator.dto.taskcenter.response.OpenListIngestTaskCenterItemResponse;
import com.medianexus.orchestrator.dto.taskcenter.response.OpenListIngestTaskCenterListResponse;
import com.medianexus.orchestrator.dto.taskcenter.response.OpenListIngestTaskCenterLogResponse;
import com.medianexus.orchestrator.dto.taskcenter.response.OpenListIngestTaskCenterProgressResponse;
import com.medianexus.orchestrator.dto.taskcenter.response.OpenListManualMagnetRetryResponse;
import com.medianexus.orchestrator.dto.taskcenter.response.OpenListReleaseRetryContextResponse;
import com.medianexus.orchestrator.mapper.AdultMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.AdultMagnetIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.AnimeMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.AnimeMagnetIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.MovieMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.MovieMagnetIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.SeriesMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.SeriesMagnetIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.UserMapper;
import com.medianexus.orchestrator.model.AdultMagnetIngestTask;
import com.medianexus.orchestrator.model.AdultMagnetIngestTaskLog;
import com.medianexus.orchestrator.model.AnimeMagnetIngestTask;
import com.medianexus.orchestrator.model.AnimeMagnetIngestTaskLog;
import com.medianexus.orchestrator.model.MovieMagnetIngestTask;
import com.medianexus.orchestrator.model.MovieMagnetIngestTaskLog;
import com.medianexus.orchestrator.model.SeriesMagnetIngestTask;
import com.medianexus.orchestrator.model.SeriesMagnetIngestTaskLog;
import com.medianexus.orchestrator.model.User;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class OpenListIngestTaskCenterService {

    private static final String ADMIN_ROLE = "ADMIN";
    private static final String MOVIE_TASK_TYPE = "MOVIE";
    private static final String SERIES_TASK_TYPE = "SERIES";
    private static final String ANIME_TASK_TYPE = "ANIME";
    private static final String ADULT_TASK_TYPE = "ADULT";
    private static final String MOVIE_PRODUCT_TYPE = "MOVIE";
    private static final String SERIES_PRODUCT_TYPE = "SERIES";
    private static final String ANIME_PRODUCT_TYPE = "ANIME";
    private static final String ADULT_PRODUCT_TYPE = "ADULT";
    private static final String MANUAL_MAGNET_SOURCE = "MANUAL_MAGNET";
    private static final String PROWLARR_RELEASE_SOURCE = "PROWLARR_RELEASE";
    private static final String ALL_FILTER = "ALL";
    private static final String IN_PROGRESS_VIEW = "IN_PROGRESS";
    private static final String NEEDS_ATTENTION_VIEW = "NEEDS_ATTENTION";
    private static final String SUCCEEDED_VIEW = "SUCCEEDED";
    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final Set<String> IN_PROGRESS_STATUSES = Set.of(
            "PENDING",
            "SUBMITTED",
            "DOWNLOADING",
            "ORGANIZING"
    );
    private static final Set<String> NEEDS_ATTENTION_STATUSES = Set.of(
            "FAILED",
            "INTERRUPTED",
            "PARTIAL_SUCCESS"
    );

    private final AuthService authService;
    private final MovieMagnetIngestTaskMapper movieTaskMapper;
    private final SeriesMagnetIngestTaskMapper seriesTaskMapper;
    private final AnimeMagnetIngestTaskMapper animeTaskMapper;
    private final AdultMagnetIngestTaskMapper adultTaskMapper;
    private final MovieMagnetIngestTaskLogMapper movieTaskLogMapper;
    private final SeriesMagnetIngestTaskLogMapper seriesTaskLogMapper;
    private final AnimeMagnetIngestTaskLogMapper animeTaskLogMapper;
    private final AdultMagnetIngestTaskLogMapper adultTaskLogMapper;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;
    private final MagnetIngestService magnetIngestService;
    private final AnimeMagnetIngestTaskService animeMagnetIngestTaskService;
    private final AdultMagnetIngestService adultMagnetIngestService;
    private final ProwlarrReleaseIngestService prowlarrReleaseIngestService;
    private final MovieSeriesResourceSearchService resourceSearchService;

    public OpenListIngestTaskCenterService(
            AuthService authService,
            MovieMagnetIngestTaskMapper movieTaskMapper,
            SeriesMagnetIngestTaskMapper seriesTaskMapper,
            AnimeMagnetIngestTaskMapper animeTaskMapper,
            AdultMagnetIngestTaskMapper adultTaskMapper,
            MovieMagnetIngestTaskLogMapper movieTaskLogMapper,
            SeriesMagnetIngestTaskLogMapper seriesTaskLogMapper,
            AnimeMagnetIngestTaskLogMapper animeTaskLogMapper,
            AdultMagnetIngestTaskLogMapper adultTaskLogMapper,
            UserMapper userMapper,
            ObjectMapper objectMapper,
            MagnetIngestService magnetIngestService,
            AnimeMagnetIngestTaskService animeMagnetIngestTaskService,
            AdultMagnetIngestService adultMagnetIngestService,
            ProwlarrReleaseIngestService prowlarrReleaseIngestService,
            MovieSeriesResourceSearchService resourceSearchService
    ) {
        this.authService = authService;
        this.movieTaskMapper = movieTaskMapper;
        this.seriesTaskMapper = seriesTaskMapper;
        this.animeTaskMapper = animeTaskMapper;
        this.adultTaskMapper = adultTaskMapper;
        this.movieTaskLogMapper = movieTaskLogMapper;
        this.seriesTaskLogMapper = seriesTaskLogMapper;
        this.animeTaskLogMapper = animeTaskLogMapper;
        this.adultTaskLogMapper = adultTaskLogMapper;
        this.userMapper = userMapper;
        this.objectMapper = objectMapper;
        this.magnetIngestService = magnetIngestService;
        this.animeMagnetIngestTaskService = animeMagnetIngestTaskService;
        this.adultMagnetIngestService = adultMagnetIngestService;
        this.prowlarrReleaseIngestService = prowlarrReleaseIngestService;
        this.resourceSearchService = Objects.requireNonNull(resourceSearchService, "resourceSearchService");
    }

    public OpenListIngestTaskCenterListResponse listOpenListIngestTasks() {
        return listOpenListIngestTasks(null, null, null, null, null, null);
    }

    public OpenListIngestTaskCenterListResponse listOpenListIngestTasks(
            String view,
            String productType,
            String sourceType,
            String keyword,
            Integer page,
            Integer pageSize
    ) {
        User user = authService.requireCurrentUser();
        List<MovieMagnetIngestTask> movieTasks = movieTaskMapper.selectList(ownedMovieTasks(user));
        List<SeriesMagnetIngestTask> seriesTasks = seriesTaskMapper.selectList(ownedSeriesTasks(user));
        List<AnimeMagnetIngestTask> animeTasks = animeTaskMapper.selectList(ownedAnimeTasks(user));
        List<AdultMagnetIngestTask> adultTasks = adultTasksVisibleTo(user);
        Map<Long, User> creatorsById = creatorsById(movieTasks, seriesTasks, animeTasks, adultTasks);
        List<TaskCenterListingCandidate> candidates = new ArrayList<>();

        candidates.addAll(movieTasks.stream()
                .map(task -> movieItem(task, creatorsById))
                .toList());
        candidates.addAll(seriesTasks.stream()
                .map(task -> seriesItem(task, creatorsById))
                .toList());
        candidates.addAll(animeTasks.stream()
                .map(task -> animeItem(task, creatorsById))
                .toList());
        candidates.addAll(adultTasks.stream()
                .map(task -> adultItem(task, creatorsById))
                .toList());
        List<TaskCenterListingCandidate> latestAttemptCandidates = collapseAttemptChains(candidates);

        String normalizedView = normalizeView(view);
        String normalizedProductType = normalizeProductType(productType);
        String normalizedSourceType = normalizeSourceType(sourceType);
        String normalizedKeyword = normalizeKeyword(keyword);
        int normalizedPageSize = normalizePageSize(pageSize);

        List<TaskCenterListingCandidate> searchableItems = latestAttemptCandidates.stream()
                .filter(item -> matchesProductType(item, normalizedProductType))
                .filter(item -> matchesSourceType(item, normalizedSourceType))
                .filter(item -> matchesKeyword(item, normalizedKeyword))
                .toList();
        int total = (int) searchableItems.stream()
                .filter(item -> matchesView(item, normalizedView))
                .count();
        int normalizedPage = normalizePage(page, total, normalizedPageSize);
        int offset = (normalizedPage - 1) * normalizedPageSize;
        List<OpenListIngestTaskCenterItemResponse> pageItems = searchableItems.stream()
                .filter(item -> matchesView(item, normalizedView))
                .sorted(taskCenterOrdering())
                .skip(offset)
                .limit(normalizedPageSize)
                .map(TaskCenterListingCandidate::response)
                .toList();

        return new OpenListIngestTaskCenterListResponse(
                pageItems,
                total,
                normalizedPage,
                normalizedPageSize,
                searchableItems.size(),
                countByView(searchableItems, IN_PROGRESS_VIEW),
                countByView(searchableItems, NEEDS_ATTENTION_VIEW),
                countByView(searchableItems, SUCCEEDED_VIEW)
        );
    }

    public OpenListIngestTaskCenterDetailResponse getOpenListIngestTaskDetail(
            String taskType,
            String taskId
    ) {
        User user = authService.requireCurrentUser();
        String normalizedTaskType = normalizeTaskType(taskType);
        return switch (normalizedTaskType) {
            case MOVIE_TASK_TYPE -> movieDetail(getAccessibleMovieTask(taskId, user), user);
            case SERIES_TASK_TYPE -> seriesDetail(getAccessibleSeriesTask(taskId, user), user);
            case ANIME_TASK_TYPE -> animeDetail(getAccessibleAnimeTask(taskId, user), user);
            case ADULT_TASK_TYPE -> adultDetail(getAccessibleAdultTask(taskId, user), user);
            default -> throw taskNotFoundException();
        };
    }

    public OpenListManualMagnetRetryResponse reuseOriginalManualMagnet(
            String taskType,
            String taskId
    ) {
        User user = authService.requireCurrentUser();
        String normalizedTaskType = normalizeTaskType(taskType);
        return switch (normalizedTaskType) {
            case MOVIE_TASK_TYPE -> retryMovieWithOriginalMagnet(getAccessibleMovieTask(taskId, user));
            case SERIES_TASK_TYPE -> retrySeriesWithOriginalMagnet(getAccessibleSeriesTask(taskId, user));
            case ANIME_TASK_TYPE -> retryAnimeWithSelectedMagnet(getAccessibleAnimeTask(taskId, user), null);
            default -> throw manualMagnetRetryNotSupportedException();
        };
    }

    public OpenListManualMagnetRetryResponse replaceManualMagnet(
            String taskType,
            String taskId,
            OpenListManualMagnetRetryRequest request
    ) {
        if (request == null || !StringUtils.hasText(request.magnet())) {
            throw badRequest("magnet 链接不能为空");
        }
        User user = authService.requireCurrentUser();
        String normalizedTaskType = normalizeTaskType(taskType);
        return switch (normalizedTaskType) {
            case MOVIE_TASK_TYPE -> retryMovieWithSelectedMagnet(getAccessibleMovieTask(taskId, user), request.magnet());
            case SERIES_TASK_TYPE -> retrySeriesWithSelectedMagnet(getAccessibleSeriesTask(taskId, user), request.magnet());
            case ANIME_TASK_TYPE -> retryAnimeWithSelectedMagnet(getAccessibleAnimeTask(taskId, user), request.magnet());
            default -> throw manualMagnetRetryNotSupportedException();
        };
    }

    public OpenListReleaseRetryContextResponse getReleaseRetryContext(
            String taskType,
            String taskId
    ) {
        User user = authService.requireCurrentUser();
        String normalizedTaskType = normalizeTaskType(taskType);
        return switch (normalizedTaskType) {
            case MOVIE_TASK_TYPE -> movieReleaseRetryContext(getAccessibleMovieTask(taskId, user));
            case SERIES_TASK_TYPE -> seriesReleaseRetryContext(getAccessibleSeriesTask(taskId, user));
            case ANIME_TASK_TYPE -> animeReleaseRetryContext(getAccessibleAnimeTask(taskId, user));
            default -> throw releaseRetryNotSupportedException();
        };
    }

    public OpenListManualMagnetRetryResponse retryWithSelectedRelease(
            String taskType,
            String taskId,
            OpenListReleaseRetryRequest request
    ) {
        User user = authService.requireCurrentUser();
        String normalizedTaskType = normalizeTaskType(taskType);
        return switch (normalizedTaskType) {
            case MOVIE_TASK_TYPE -> retryMovieWithSelectedRelease(
                    getAccessibleMovieTask(taskId, user),
                    request
            );
            case SERIES_TASK_TYPE -> retrySeriesWithSelectedRelease(
                    getAccessibleSeriesTask(taskId, user),
                    request
            );
            case ANIME_TASK_TYPE -> retryAnimeWithSelectedRelease(
                    getAccessibleAnimeTask(taskId, user),
                    request
            );
            default -> throw releaseRetryNotSupportedException();
        };
    }

    public OpenListManualMagnetRetryResponse retryAdultBatch(
            String taskId,
            OpenListAdultBatchRetryRequest request
    ) {
        if (request == null || request.downloadLinks() == null || request.downloadLinks().isEmpty()) {
            throw badRequest("下载链接列表不能为空");
        }
        User user = authService.requireCurrentUser();
        AdultMagnetIngestTask originalTask = getAccessibleAdultTask(taskId, user);
        ensureRecoverableStatus(originalTask.getStatus());
        String newTaskId = adultMagnetIngestService.createRetryTask(
                originalTask.getCategory(),
                request.downloadLinks(),
                retryReference(ADULT_TASK_TYPE, originalTask.getId(), originalTask.getAttemptGroupId())
        ).id();
        return retryResponse(ADULT_TASK_TYPE, newTaskId);
    }

    private OpenListManualMagnetRetryResponse retryMovieWithSelectedMagnet(
            MovieMagnetIngestTask originalTask,
            String replacementMagnet
    ) {
        ensureMagnetReplacementAllowed(originalTask.getStatus(), sourceType(originalTask.getSourceType()));
        MovieMagnetIngestTaskResponse response = magnetIngestService.createMovieRetryTask(
                originalTask,
                StringUtils.hasText(replacementMagnet) ? replacementMagnet : originalTask.getMagnet(),
                retryReference(MOVIE_TASK_TYPE, originalTask.getId(), originalTask.getAttemptGroupId())
        );
        return retryResponse(MOVIE_TASK_TYPE, response.id());
    }

    private OpenListManualMagnetRetryResponse retryMovieWithOriginalMagnet(MovieMagnetIngestTask originalTask) {
        ensureMagnetReplacementAllowed(originalTask.getStatus(), sourceType(originalTask.getSourceType()));
        return retryMovieWithSelectedMagnet(originalTask, originalTask.getMagnet());
    }

    private OpenListManualMagnetRetryResponse retrySeriesWithSelectedMagnet(
            SeriesMagnetIngestTask originalTask,
            String replacementMagnet
    ) {
        ensureMagnetReplacementAllowed(originalTask.getStatus(), sourceType(originalTask.getSourceType()));
        SeriesMagnetIngestTaskResponse response = magnetIngestService.createSeriesRetryTask(
                originalTask,
                StringUtils.hasText(replacementMagnet) ? replacementMagnet : originalTask.getMagnet(),
                retryReference(SERIES_TASK_TYPE, originalTask.getId(), originalTask.getAttemptGroupId())
        );
        return retryResponse(SERIES_TASK_TYPE, response.id());
    }

    private OpenListManualMagnetRetryResponse retrySeriesWithOriginalMagnet(SeriesMagnetIngestTask originalTask) {
        ensureMagnetReplacementAllowed(originalTask.getStatus(), sourceType(originalTask.getSourceType()));
        return retrySeriesWithSelectedMagnet(originalTask, originalTask.getMagnet());
    }

    private OpenListManualMagnetRetryResponse retryAnimeWithSelectedMagnet(
            AnimeMagnetIngestTask originalTask,
            String replacementMagnet
    ) {
        ensureRecoverableStatus(originalTask.getStatus());
        AnimeMagnetIngestTaskResponse response = animeMagnetIngestTaskService.createRetryTask(
                originalTask,
                StringUtils.hasText(replacementMagnet) ? replacementMagnet : originalTask.getMagnet(),
                retryReference(ANIME_TASK_TYPE, originalTask.getId(), originalTask.getAttemptGroupId())
        );
        return retryResponse(ANIME_TASK_TYPE, response.id());
    }

    private void ensureMagnetReplacementAllowed(String status, String sourceType) {
        ensureRecoverableStatus(status);
        if (!MANUAL_MAGNET_SOURCE.equals(sourceType) && !PROWLARR_RELEASE_SOURCE.equals(sourceType)) {
            throw badRequest("当前任务来源不支持手动提供 magnet");
        }
    }

    private OpenListReleaseRetryContextResponse movieReleaseRetryContext(MovieMagnetIngestTask task) {
        ensureReleaseSelectionAllowed(task.getStatus(), sourceType(task.getSourceType()));
        ReleaseRetryTitles titles = movieReleaseRetryTitles(task);
        return new OpenListReleaseRetryContextResponse(
                MOVIE_TASK_TYPE,
                task.getId(),
                MOVIE_PRODUCT_TYPE,
                titles.title(),
                titles.originalTitle(),
                task.getYear(),
                null,
                task.getQualityTag(),
                task.getReleaseTitle(),
                task.getReleaseIndexer(),
                task.getReleaseSize(),
                splitTags(task.getResolutionTags()),
                splitTags(task.getDynamicRangeTags())
        );
    }

    private OpenListReleaseRetryContextResponse seriesReleaseRetryContext(SeriesMagnetIngestTask task) {
        ensureReleaseSelectionAllowed(task.getStatus(), sourceType(task.getSourceType()));
        ReleaseRetryTitles titles = seriesReleaseRetryTitles(task);
        return new OpenListReleaseRetryContextResponse(
                SERIES_TASK_TYPE,
                task.getId(),
                seriesProductType(task.getTaskProductType()),
                titles.title(),
                titles.originalTitle(),
                null,
                task.getSeasonNumber(),
                task.getQualityTag(),
                task.getReleaseTitle(),
                task.getReleaseIndexer(),
                task.getReleaseSize(),
                splitTags(task.getResolutionTags()),
                splitTags(task.getDynamicRangeTags())
        );
    }

    private ReleaseRetryTitles movieReleaseRetryTitles(MovieMagnetIngestTask task) {
        ReleaseRetryTitles storedTitles = new ReleaseRetryTitles(
                task.getTitle(),
                trimToNull(task.getOriginalTitle())
        );
        if (!shouldEnrichReleaseRetryTitles(storedTitles)) {
            return storedTitles;
        }
        return enrichMovieReleaseRetryTitles(task, storedTitles);
    }

    private ReleaseRetryTitles seriesReleaseRetryTitles(SeriesMagnetIngestTask task) {
        ReleaseRetryTitles storedTitles = new ReleaseRetryTitles(
                task.getTitle(),
                trimToNull(task.getOriginalTitle())
        );
        if (!shouldEnrichReleaseRetryTitles(storedTitles)) {
            return storedTitles;
        }
        return enrichSeriesReleaseRetryTitles(storedTitles);
    }

    private boolean shouldEnrichReleaseRetryTitles(ReleaseRetryTitles titles) {
        String title = trimToNull(titles.title());
        String originalTitle = trimToNull(titles.originalTitle());
        return StringUtils.hasText(originalTitle) && normalizeTitle(title).equals(normalizeTitle(originalTitle));
    }

    private ReleaseRetryTitles enrichMovieReleaseRetryTitles(
            MovieMagnetIngestTask task,
            ReleaseRetryTitles fallback
    ) {
        String searchTerm = firstText(fallback.originalTitle(), fallback.title());
        if (!StringUtils.hasText(searchTerm)) {
            return fallback;
        }
        try {
            return resourceSearchService.searchMovies(searchTerm).items().stream()
                    .filter(item -> task.getYear() == null || task.getYear().equals(item.year()))
                    .filter(item -> matchesKnownTitle(item.title(), fallback)
                            || matchesKnownTitle(item.originalTitle(), fallback)
                            || item.alternateTitles() != null
                            && item.alternateTitles().stream().anyMatch(title -> matchesKnownTitle(title, fallback)))
                    .findFirst()
                    .map(item -> new ReleaseRetryTitles(
                            firstText(item.title(), fallback.title()),
                            firstText(item.originalTitle(), fallback.originalTitle())
                    ))
                    .orElse(fallback);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private ReleaseRetryTitles enrichSeriesReleaseRetryTitles(ReleaseRetryTitles fallback) {
        String searchTerm = firstText(fallback.originalTitle(), fallback.title());
        if (!StringUtils.hasText(searchTerm)) {
            return fallback;
        }
        try {
            return resourceSearchService.searchSeries(searchTerm).items().stream()
                    .filter(item -> matchesKnownTitle(item.title(), fallback)
                            || matchesKnownTitle(item.originalTitle(), fallback))
                    .findFirst()
                    .map(item -> new ReleaseRetryTitles(
                            firstText(item.title(), fallback.title()),
                            firstText(item.originalTitle(), fallback.originalTitle())
                    ))
                    .orElse(fallback);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private boolean matchesKnownTitle(String candidate, ReleaseRetryTitles knownTitles) {
        String normalizedCandidate = normalizeTitle(candidate);
        return StringUtils.hasText(normalizedCandidate)
                && (normalizedCandidate.equals(normalizeTitle(knownTitles.title()))
                || normalizedCandidate.equals(normalizeTitle(knownTitles.originalTitle())));
    }

    private String normalizeTitle(String value) {
        String normalized = trimToNull(value);
        return normalized == null ? "" : normalized.toLowerCase(Locale.ROOT);
    }

    private String firstText(String first, String second) {
        String normalizedFirst = trimToNull(first);
        if (StringUtils.hasText(normalizedFirst)) {
            return normalizedFirst;
        }
        return trimToNull(second);
    }

    private OpenListReleaseRetryContextResponse animeReleaseRetryContext(AnimeMagnetIngestTask task) {
        ensureReleaseSelectionAllowed(task.getStatus(), sourceType(task.getSourceType()));
        return new OpenListReleaseRetryContextResponse(
                ANIME_TASK_TYPE,
                task.getId(),
                ANIME_PRODUCT_TYPE,
                task.getTitle(),
                task.getName(),
                null,
                task.getSeasonNumber(),
                task.getQualityTag(),
                task.getReleaseTitle(),
                task.getReleaseIndexer(),
                task.getReleaseSize(),
                splitTags(task.getResolutionTags()),
                splitTags(task.getDynamicRangeTags())
        );
    }

    private OpenListManualMagnetRetryResponse retryMovieWithSelectedRelease(
            MovieMagnetIngestTask originalTask,
            OpenListReleaseRetryRequest request
    ) {
        ensureReleaseSelectionAllowed(originalTask.getStatus(), sourceType(originalTask.getSourceType()));
        MovieMagnetIngestTaskResponse response = prowlarrReleaseIngestService.ingestSelectedMovieRetry(
                originalTask,
                request,
                retryReference(MOVIE_TASK_TYPE, originalTask.getId(), originalTask.getAttemptGroupId())
        );
        return retryResponse(MOVIE_TASK_TYPE, response.id());
    }

    private OpenListManualMagnetRetryResponse retrySeriesWithSelectedRelease(
            SeriesMagnetIngestTask originalTask,
            OpenListReleaseRetryRequest request
    ) {
        ensureReleaseSelectionAllowed(originalTask.getStatus(), sourceType(originalTask.getSourceType()));
        SeriesMagnetIngestTaskResponse response = prowlarrReleaseIngestService.ingestSelectedSeriesRetry(
                originalTask,
                request,
                retryReference(SERIES_TASK_TYPE, originalTask.getId(), originalTask.getAttemptGroupId())
        );
        return retryResponse(SERIES_TASK_TYPE, response.id());
    }

    private OpenListManualMagnetRetryResponse retryAnimeWithSelectedRelease(
            AnimeMagnetIngestTask originalTask,
            OpenListReleaseRetryRequest request
    ) {
        ensureReleaseSelectionAllowed(originalTask.getStatus(), sourceType(originalTask.getSourceType()));
        AnimeMagnetIngestTaskResponse response = prowlarrReleaseIngestService.ingestSelectedAnimeRetry(
                originalTask,
                request,
                retryReference(ANIME_TASK_TYPE, originalTask.getId(), originalTask.getAttemptGroupId())
        );
        return retryResponse(ANIME_TASK_TYPE, response.id());
    }

    private void ensureReleaseSelectionAllowed(String status, String sourceType) {
        ensureRecoverableStatus(status);
        if (!MANUAL_MAGNET_SOURCE.equals(sourceType) && !PROWLARR_RELEASE_SOURCE.equals(sourceType)) {
            throw badRequest("当前任务来源不支持重新选择发布资源");
        }
    }

    private void ensureRecoverableStatus(String status) {
        if (!NEEDS_ATTENTION_STATUSES.contains(status)) {
            throw badRequest("当前任务状态不支持重试");
        }
    }

    private TaskRetryReference retryReference(
            String taskType,
            String taskId,
            String attemptGroupId
    ) {
        return new TaskRetryReference(
                effectiveAttemptGroupId(taskType, taskId, attemptGroupId),
                taskType,
                taskId
        );
    }

    private OpenListManualMagnetRetryResponse retryResponse(String taskType, String taskId) {
        return new OpenListManualMagnetRetryResponse(
                taskType,
                taskId,
                taskCenterDetailPath(taskType, taskId)
        );
    }

    private OpenListIngestTaskCenterDetailResponse movieDetail(MovieMagnetIngestTask task, User user) {
        List<OpenListIngestTaskCenterLogResponse> logs = movieLogs(task.getId());
        return new OpenListIngestTaskCenterDetailResponse(
                MOVIE_TASK_TYPE,
                task.getId(),
                MOVIE_PRODUCT_TYPE,
                task.getCreatedByUserId(),
                creatorUsername(task.getCreatedByUserId()),
                movieDisplayTitle(task),
                task.getStatus(),
                task.getStage(),
                sourceType(task.getSourceType()),
                task.getReleaseTitle(),
                task.getReleaseIndexer(),
                task.getReleaseSize(),
                splitTags(task.getResolutionTags()),
                task.getQualityTag(),
                splitTags(task.getDynamicRangeTags()),
                progressSummary(task.getOrganizedCount(), task.getSkippedCount()),
                organizedProgress(task.getOrganizedCount(), task.getSkippedCount()),
                task.getErrorMessage(),
                lastWarningOrErrorLog(logs),
                logs,
                isActive(task.getStatus()),
                pendingExplanation(task.getStatus()),
                null,
                attemptChain(movieSnapshot(task), user),
                task.getCreatedAt(),
                task.getUpdatedAt(),
                task.getFinishedAt()
        );
    }

    private OpenListIngestTaskCenterDetailResponse seriesDetail(SeriesMagnetIngestTask task, User user) {
        List<OpenListIngestTaskCenterLogResponse> logs = seriesLogs(task.getId());
        return new OpenListIngestTaskCenterDetailResponse(
                SERIES_TASK_TYPE,
                task.getId(),
                seriesProductType(task.getTaskProductType()),
                task.getCreatedByUserId(),
                creatorUsername(task.getCreatedByUserId()),
                seriesDisplayTitle(task),
                task.getStatus(),
                task.getStage(),
                sourceType(task.getSourceType()),
                task.getReleaseTitle(),
                task.getReleaseIndexer(),
                task.getReleaseSize(),
                splitTags(task.getResolutionTags()),
                task.getQualityTag(),
                splitTags(task.getDynamicRangeTags()),
                progressSummary(task.getOrganizedCount(), task.getSkippedCount()),
                organizedProgress(task.getOrganizedCount(), task.getSkippedCount()),
                task.getErrorMessage(),
                lastWarningOrErrorLog(logs),
                logs,
                isActive(task.getStatus()),
                pendingExplanation(task.getStatus()),
                null,
                attemptChain(seriesSnapshot(task), user),
                task.getCreatedAt(),
                task.getUpdatedAt(),
                task.getFinishedAt()
        );
    }

    private OpenListIngestTaskCenterDetailResponse animeDetail(AnimeMagnetIngestTask task, User user) {
        List<OpenListIngestTaskCenterLogResponse> logs = animeLogs(task.getId());
        return new OpenListIngestTaskCenterDetailResponse(
                ANIME_TASK_TYPE,
                task.getId(),
                ANIME_PRODUCT_TYPE,
                task.getCreatedByUserId(),
                creatorUsername(task.getCreatedByUserId()),
                animeDisplayTitle(task),
                task.getStatus(),
                task.getStage(),
                sourceType(task.getSourceType()),
                task.getReleaseTitle(),
                task.getReleaseIndexer(),
                task.getReleaseSize(),
                splitTags(task.getResolutionTags()),
                task.getQualityTag(),
                splitTags(task.getDynamicRangeTags()),
                progressSummary(task.getOrganizedCount(), task.getSkippedCount()),
                organizedProgress(task.getOrganizedCount(), task.getSkippedCount()),
                task.getErrorMessage(),
                lastWarningOrErrorLog(logs),
                logs,
                isActive(task.getStatus()),
                pendingExplanation(task.getStatus()),
                null,
                attemptChain(animeSnapshot(task), user),
                task.getCreatedAt(),
                task.getUpdatedAt(),
                task.getFinishedAt()
        );
    }

    private OpenListIngestTaskCenterDetailResponse adultDetail(AdultMagnetIngestTask task, User user) {
        List<OpenListIngestTaskCenterLogResponse> logs = adultLogs(task.getId());
        return new OpenListIngestTaskCenterDetailResponse(
                ADULT_TASK_TYPE,
                task.getId(),
                ADULT_PRODUCT_TYPE,
                task.getCreatedByUserId(),
                creatorUsername(task.getCreatedByUserId()),
                adultTitle(task),
                task.getStatus(),
                task.getStage(),
                MANUAL_MAGNET_SOURCE,
                null,
                null,
                null,
                List.of(),
                null,
                List.of(),
                adultProgressSummary(task),
                adultProgress(task),
                task.getErrorMessage(),
                lastWarningOrErrorLog(logs),
                logs,
                isActive(task.getStatus()),
                pendingExplanation(task.getStatus()),
                adultDownloadLinks(task.getDownloadLinksJson()),
                attemptChain(adultSnapshot(task), user),
                task.getCreatedAt(),
                task.getUpdatedAt(),
                task.getFinishedAt()
        );
    }

    private OpenListIngestTaskCenterAttemptChainResponse attemptChain(
            TaskAttemptSnapshot current,
            User user
    ) {
        Map<TaskAttemptKey, TaskAttemptSnapshot> uniqueSnapshots = new LinkedHashMap<>();
        collectAttemptChainSnapshots(current, user).stream()
                .sorted(attemptOrdering())
                .forEach(snapshot -> uniqueSnapshots.putIfAbsent(snapshot.key(), snapshot));

        Set<TaskAttemptKey> visibleKeys = uniqueSnapshots.keySet();
        List<OpenListIngestTaskCenterAttemptResponse> attempts = uniqueSnapshots.values().stream()
                .map(snapshot -> attemptResponse(snapshot, current.key(), visibleKeys))
                .toList();
        OpenListIngestTaskCenterAttemptResponse currentAttempt = attempts.stream()
                .filter(attempt -> current.taskType().equals(attempt.taskType()) && current.id().equals(attempt.id()))
                .findFirst()
                .orElseGet(() -> attemptResponse(current, current.key(), visibleKeys));
        OpenListIngestTaskCenterAttemptResponse retryOf = attempts.stream()
                .filter(attempt -> current.retryOfKey()
                        .map(key -> key.matches(attempt.taskType(), attempt.id()))
                        .orElse(false))
                .findFirst()
                .orElse(null);

        return new OpenListIngestTaskCenterAttemptChainResponse(
                current.attemptGroupId(),
                currentAttempt,
                retryOf,
                attempts
        );
    }

    private List<TaskAttemptSnapshot> collectAttemptChainSnapshots(TaskAttemptSnapshot current, User user) {
        Map<TaskAttemptKey, TaskAttemptSnapshot> uniqueSnapshots = new LinkedHashMap<>();
        List<TaskAttemptSnapshot> pendingSnapshots = new ArrayList<>();
        addAttemptSnapshot(uniqueSnapshots, pendingSnapshots, current, user);
        addAttemptSnapshots(
                uniqueSnapshots,
                pendingSnapshots,
                accessibleAttemptSnapshots(current.attemptGroupId(), user),
                user
        );

        for (int index = 0; index < pendingSnapshots.size(); index++) {
            TaskAttemptSnapshot snapshot = pendingSnapshots.get(index);
            addAttemptSnapshots(
                    uniqueSnapshots,
                    pendingSnapshots,
                    accessibleAttemptSnapshots(snapshot.attemptGroupId(), user),
                    user
            );
            snapshot.retryOfKey()
                    .flatMap(key -> accessibleAttemptSnapshot(key, user))
                    .ifPresent(source -> addAttemptSnapshot(uniqueSnapshots, pendingSnapshots, source, user));
        }
        return List.copyOf(uniqueSnapshots.values());
    }

    private void addAttemptSnapshots(
            Map<TaskAttemptKey, TaskAttemptSnapshot> uniqueSnapshots,
            List<TaskAttemptSnapshot> pendingSnapshots,
            List<TaskAttemptSnapshot> snapshots,
            User user
    ) {
        snapshots.forEach(snapshot -> addAttemptSnapshot(uniqueSnapshots, pendingSnapshots, snapshot, user));
    }

    private void addAttemptSnapshot(
            Map<TaskAttemptKey, TaskAttemptSnapshot> uniqueSnapshots,
            List<TaskAttemptSnapshot> pendingSnapshots,
            TaskAttemptSnapshot snapshot,
            User user
    ) {
        if (snapshot == null || !canAccessAttempt(user, snapshot) || uniqueSnapshots.containsKey(snapshot.key())) {
            return;
        }
        uniqueSnapshots.put(snapshot.key(), snapshot);
        pendingSnapshots.add(snapshot);
    }

    private List<TaskAttemptSnapshot> accessibleAttemptSnapshots(String attemptGroupId, User user) {
        if (!StringUtils.hasText(attemptGroupId)) {
            return List.of();
        }
        List<TaskAttemptSnapshot> snapshots = new ArrayList<>();
        snapshots.addAll(selectMovieAttempts(attemptGroupId, user).stream()
                .map(this::movieSnapshot)
                .toList());
        snapshots.addAll(selectSeriesAttempts(attemptGroupId, user).stream()
                .map(this::seriesSnapshot)
                .toList());
        snapshots.addAll(selectAnimeAttempts(attemptGroupId, user).stream()
                .map(this::animeSnapshot)
                .toList());
        if (isAdmin(user)) {
            snapshots.addAll(selectAdultAttempts(attemptGroupId).stream()
                    .map(this::adultSnapshot)
                    .toList());
        }
        return snapshots;
    }

    private java.util.Optional<TaskAttemptSnapshot> accessibleAttemptSnapshot(
            TaskAttemptKey key,
            User user
    ) {
        TaskAttemptSnapshot snapshot = switch (key.taskType()) {
            case MOVIE_TASK_TYPE -> {
                MovieMagnetIngestTask task = movieTaskMapper.selectById(key.taskId());
                yield task == null ? null : movieSnapshot(task);
            }
            case SERIES_TASK_TYPE -> {
                SeriesMagnetIngestTask task = seriesTaskMapper.selectById(key.taskId());
                yield task == null ? null : seriesSnapshot(task);
            }
            case ANIME_TASK_TYPE -> {
                AnimeMagnetIngestTask task = animeTaskMapper.selectById(key.taskId());
                yield task == null ? null : animeSnapshot(task);
            }
            case ADULT_TASK_TYPE -> {
                if (!isAdmin(user)) {
                    yield null;
                }
                AdultMagnetIngestTask task = adultTaskMapper.selectById(key.taskId());
                yield task == null ? null : adultSnapshot(task);
            }
            default -> null;
        };
        if (snapshot == null || !canAccessAttempt(user, snapshot)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(snapshot);
    }

    private List<MovieMagnetIngestTask> selectMovieAttempts(String attemptGroupId, User user) {
        LambdaQueryWrapper<MovieMagnetIngestTask> queryWrapper = new LambdaQueryWrapper<MovieMagnetIngestTask>()
                .eq(MovieMagnetIngestTask::getAttemptGroupId, attemptGroupId);
        if (!isAdmin(user)) {
            queryWrapper.eq(MovieMagnetIngestTask::getCreatedByUserId, user.getId());
        }
        List<MovieMagnetIngestTask> tasks = movieTaskMapper.selectList(queryWrapper);
        return tasks == null ? List.of() : tasks;
    }

    private List<SeriesMagnetIngestTask> selectSeriesAttempts(String attemptGroupId, User user) {
        LambdaQueryWrapper<SeriesMagnetIngestTask> queryWrapper = new LambdaQueryWrapper<SeriesMagnetIngestTask>()
                .eq(SeriesMagnetIngestTask::getAttemptGroupId, attemptGroupId);
        if (!isAdmin(user)) {
            queryWrapper.eq(SeriesMagnetIngestTask::getCreatedByUserId, user.getId());
        }
        List<SeriesMagnetIngestTask> tasks = seriesTaskMapper.selectList(queryWrapper);
        return tasks == null ? List.of() : tasks;
    }

    private List<AnimeMagnetIngestTask> selectAnimeAttempts(String attemptGroupId, User user) {
        LambdaQueryWrapper<AnimeMagnetIngestTask> queryWrapper = new LambdaQueryWrapper<AnimeMagnetIngestTask>()
                .eq(AnimeMagnetIngestTask::getAttemptGroupId, attemptGroupId);
        if (!isAdmin(user)) {
            queryWrapper.eq(AnimeMagnetIngestTask::getCreatedByUserId, user.getId());
        }
        List<AnimeMagnetIngestTask> tasks = animeTaskMapper.selectList(queryWrapper);
        return tasks == null ? List.of() : tasks;
    }

    private List<AdultMagnetIngestTask> selectAdultAttempts(String attemptGroupId) {
        List<AdultMagnetIngestTask> tasks = adultTaskMapper.selectList(new LambdaQueryWrapper<AdultMagnetIngestTask>()
                .eq(AdultMagnetIngestTask::getAttemptGroupId, attemptGroupId));
        return tasks == null ? List.of() : tasks;
    }

    private OpenListIngestTaskCenterAttemptResponse attemptResponse(
            TaskAttemptSnapshot snapshot,
            TaskAttemptKey currentKey,
            Set<TaskAttemptKey> visibleKeys
    ) {
        TaskAttemptKey retryOfKey = snapshot.retryOfKey()
                .filter(visibleKeys::contains)
                .orElse(null);
        return new OpenListIngestTaskCenterAttemptResponse(
                snapshot.taskType(),
                snapshot.id(),
                snapshot.productType(),
                snapshot.title(),
                snapshot.status(),
                snapshot.stage(),
                snapshot.sourceType(),
                snapshot.createdByUserId(),
                snapshot.createdByUsername(),
                retryOfKey == null ? null : retryOfKey.taskType(),
                retryOfKey == null ? null : retryOfKey.taskId(),
                snapshot.key().equals(currentKey),
                taskCenterDetailPath(snapshot.taskType(), snapshot.id()),
                snapshot.createdAt(),
                snapshot.updatedAt()
        );
    }

    private Comparator<TaskAttemptSnapshot> attemptOrdering() {
        Comparator<TaskAttemptSnapshot> byCreatedAt =
                Comparator.comparing(this::effectiveAttemptCreatedAt);
        Comparator<TaskAttemptSnapshot> byUpdatedAt =
                Comparator.comparing(this::effectiveAttemptUpdatedAt);
        return byCreatedAt
                .thenComparing(byUpdatedAt)
                .thenComparing(TaskAttemptSnapshot::taskType)
                .thenComparing(TaskAttemptSnapshot::id);
    }

    private LocalDateTime effectiveAttemptCreatedAt(TaskAttemptSnapshot attempt) {
        LocalDateTime createdAt = attempt.createdAt();
        return createdAt == null ? LocalDateTime.MIN : createdAt;
    }

    private LocalDateTime effectiveAttemptUpdatedAt(TaskAttemptSnapshot attempt) {
        LocalDateTime updatedAt = attempt.updatedAt();
        return updatedAt == null ? LocalDateTime.MIN : updatedAt;
    }

    private MovieMagnetIngestTask getAccessibleMovieTask(String taskId, User user) {
        MovieMagnetIngestTask task = movieTaskMapper.selectById(taskId);
        if (task == null || !canAccessTask(user, task.getCreatedByUserId())) {
            throw taskNotFoundException();
        }
        return task;
    }

    private SeriesMagnetIngestTask getAccessibleSeriesTask(String taskId, User user) {
        SeriesMagnetIngestTask task = seriesTaskMapper.selectById(taskId);
        if (task == null || !canAccessTask(user, task.getCreatedByUserId())) {
            throw taskNotFoundException();
        }
        return task;
    }

    private AnimeMagnetIngestTask getAccessibleAnimeTask(String taskId, User user) {
        AnimeMagnetIngestTask task = animeTaskMapper.selectById(taskId);
        if (task == null || !canAccessTask(user, task.getCreatedByUserId())) {
            throw taskNotFoundException();
        }
        return task;
    }

    private AdultMagnetIngestTask getAccessibleAdultTask(String taskId, User user) {
        if (!isAdmin(user)) {
            throw taskNotFoundException();
        }
        AdultMagnetIngestTask task = adultTaskMapper.selectById(taskId);
        if (task == null) {
            throw taskNotFoundException();
        }
        return task;
    }

    private boolean canAccessTask(User user, Long createdByUserId) {
        return isAdmin(user) || (createdByUserId != null && createdByUserId.equals(user.getId()));
    }

    private List<OpenListIngestTaskCenterLogResponse> movieLogs(String taskId) {
        return movieTaskLogMapper.selectList(new LambdaQueryWrapper<MovieMagnetIngestTaskLog>()
                        .eq(MovieMagnetIngestTaskLog::getTaskId, taskId)
                        .orderByAsc(MovieMagnetIngestTaskLog::getId))
                .stream()
                .map(log -> new OpenListIngestTaskCenterLogResponse(
                        log.getId(),
                        log.getTaskId(),
                        log.getLevel(),
                        log.getStage(),
                        log.getMessage(),
                        log.getDetail(),
                        log.getCreatedAt()
                ))
                .toList();
    }

    private List<OpenListIngestTaskCenterLogResponse> seriesLogs(String taskId) {
        return seriesTaskLogMapper.selectList(new LambdaQueryWrapper<SeriesMagnetIngestTaskLog>()
                        .eq(SeriesMagnetIngestTaskLog::getTaskId, taskId)
                        .orderByAsc(SeriesMagnetIngestTaskLog::getId))
                .stream()
                .map(log -> new OpenListIngestTaskCenterLogResponse(
                        log.getId(),
                        log.getTaskId(),
                        log.getLevel(),
                        log.getStage(),
                        log.getMessage(),
                        log.getDetail(),
                        log.getCreatedAt()
                ))
                .toList();
    }

    private List<OpenListIngestTaskCenterLogResponse> animeLogs(String taskId) {
        return animeTaskLogMapper.selectList(new LambdaQueryWrapper<AnimeMagnetIngestTaskLog>()
                        .eq(AnimeMagnetIngestTaskLog::getTaskId, taskId)
                        .orderByAsc(AnimeMagnetIngestTaskLog::getId))
                .stream()
                .map(log -> new OpenListIngestTaskCenterLogResponse(
                        log.getId(),
                        log.getTaskId(),
                        log.getLevel(),
                        log.getStage(),
                        log.getMessage(),
                        log.getDetail(),
                        log.getCreatedAt()
                ))
                .toList();
    }

    private List<OpenListIngestTaskCenterLogResponse> adultLogs(String taskId) {
        return adultTaskLogMapper.selectList(new LambdaQueryWrapper<AdultMagnetIngestTaskLog>()
                        .eq(AdultMagnetIngestTaskLog::getTaskId, taskId)
                        .orderByAsc(AdultMagnetIngestTaskLog::getId))
                .stream()
                .map(log -> new OpenListIngestTaskCenterLogResponse(
                        log.getId(),
                        log.getTaskId(),
                        log.getLevel(),
                        log.getStage(),
                        log.getMessage(),
                        log.getDetail(),
                        log.getCreatedAt()
                ))
                .toList();
    }

    private OpenListIngestTaskCenterProgressResponse organizedProgress(
            Integer organizedCount,
            Integer skippedCount
    ) {
        return new OpenListIngestTaskCenterProgressResponse(
                safeCount(organizedCount),
                safeCount(skippedCount),
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private OpenListIngestTaskCenterProgressResponse adultProgress(AdultMagnetIngestTask task) {
        return new OpenListIngestTaskCenterProgressResponse(
                null,
                null,
                safeCount(task.getSubmittedCount()),
                safeCount(task.getSucceededCount()),
                safeCount(task.getFailedCount()),
                safeCount(task.getDuplicateCount()),
                safeCount(task.getKeptCount()),
                safeCount(task.getDeletedCount())
        );
    }

    private OpenListIngestTaskCenterLogResponse lastWarningOrErrorLog(
            List<OpenListIngestTaskCenterLogResponse> logs
    ) {
        for (int index = logs.size() - 1; index >= 0; index--) {
            OpenListIngestTaskCenterLogResponse log = logs.get(index);
            if ("WARN".equalsIgnoreCase(log.level()) || "ERROR".equalsIgnoreCase(log.level())) {
                return log;
            }
        }
        return null;
    }

    private boolean isActive(String status) {
        return IN_PROGRESS_STATUSES.contains(status);
    }

    private String pendingExplanation(String status) {
        if (!"PENDING".equals(status)) {
            return null;
        }
        return "任务已创建，正在等待对应任务执行器处理。";
    }

    private List<String> splitTags(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    private String creatorUsername(Long creatorId) {
        User creator = creatorId == null ? null : userMapper.selectById(creatorId);
        return creator == null ? null : creator.getUsername();
    }

    private String normalizeTaskType(String taskType) {
        return StringUtils.hasText(taskType) ? taskType.trim().toUpperCase(Locale.ROOT) : "";
    }

    private BusinessException taskNotFoundException() {
        return new BusinessException(ErrorCode.BAD_REQUEST, "任务不存在", HttpStatus.NOT_FOUND);
    }

    private BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }

    private BusinessException manualMagnetRetryNotSupportedException() {
        return badRequest("当前任务类型不支持手动 magnet 重试");
    }

    private BusinessException releaseRetryNotSupportedException() {
        return badRequest("当前任务类型不支持重新选择发布资源");
    }

    private LambdaQueryWrapper<MovieMagnetIngestTask> ownedMovieTasks(User user) {
        LambdaQueryWrapper<MovieMagnetIngestTask> queryWrapper = new LambdaQueryWrapper<>();
        if (!isAdmin(user)) {
            queryWrapper.eq(MovieMagnetIngestTask::getCreatedByUserId, user.getId());
        }
        return queryWrapper;
    }

    private LambdaQueryWrapper<SeriesMagnetIngestTask> ownedSeriesTasks(User user) {
        LambdaQueryWrapper<SeriesMagnetIngestTask> queryWrapper = new LambdaQueryWrapper<>();
        if (!isAdmin(user)) {
            queryWrapper.eq(SeriesMagnetIngestTask::getCreatedByUserId, user.getId());
        }
        return queryWrapper;
    }

    private LambdaQueryWrapper<AnimeMagnetIngestTask> ownedAnimeTasks(User user) {
        LambdaQueryWrapper<AnimeMagnetIngestTask> queryWrapper = new LambdaQueryWrapper<>();
        if (!isAdmin(user)) {
            queryWrapper.eq(AnimeMagnetIngestTask::getCreatedByUserId, user.getId());
        }
        return queryWrapper;
    }

    private List<AdultMagnetIngestTask> adultTasksVisibleTo(User user) {
        if (!isAdmin(user)) {
            return List.of();
        }
        return adultTaskMapper.selectList(new LambdaQueryWrapper<>());
    }

    private TaskCenterListingCandidate movieItem(
            MovieMagnetIngestTask task,
            Map<Long, User> creatorsById
    ) {
        OpenListIngestTaskCenterItemResponse response = new OpenListIngestTaskCenterItemResponse(
                MOVIE_TASK_TYPE,
                task.getId(),
                MOVIE_PRODUCT_TYPE,
                task.getCreatedByUserId(),
                creatorUsername(creatorsById, task.getCreatedByUserId()),
                movieDisplayTitle(task),
                task.getStatus(),
                task.getStage(),
                sourceType(task.getSourceType()),
                task.getReleaseTitle(),
                progressSummary(task.getOrganizedCount(), task.getSkippedCount()),
                1,
                taskCenterDetailPath(MOVIE_TASK_TYPE, task.getId()),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
        return listingCandidate(
                response,
                task.getMagnetHash(),
                task.getAttemptGroupId(),
                task.getRetryOfTaskType(),
                task.getRetryOfTaskId(),
                task.getTitle(),
                task.getOriginalTitle()
        );
    }

    private TaskCenterListingCandidate seriesItem(
            SeriesMagnetIngestTask task,
            Map<Long, User> creatorsById
    ) {
        OpenListIngestTaskCenterItemResponse response = new OpenListIngestTaskCenterItemResponse(
                SERIES_TASK_TYPE,
                task.getId(),
                seriesProductType(task.getTaskProductType()),
                task.getCreatedByUserId(),
                creatorUsername(creatorsById, task.getCreatedByUserId()),
                seriesDisplayTitle(task),
                task.getStatus(),
                task.getStage(),
                sourceType(task.getSourceType()),
                task.getReleaseTitle(),
                progressSummary(task.getOrganizedCount(), task.getSkippedCount()),
                1,
                taskCenterDetailPath(SERIES_TASK_TYPE, task.getId()),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
        return listingCandidate(
                response,
                task.getMagnetHash(),
                task.getAttemptGroupId(),
                task.getRetryOfTaskType(),
                task.getRetryOfTaskId(),
                task.getTitle(),
                task.getOriginalTitle(),
                task.getSeriesName()
        );
    }

    private TaskCenterListingCandidate animeItem(
            AnimeMagnetIngestTask task,
            Map<Long, User> creatorsById
    ) {
        OpenListIngestTaskCenterItemResponse response = new OpenListIngestTaskCenterItemResponse(
                ANIME_TASK_TYPE,
                task.getId(),
                ANIME_PRODUCT_TYPE,
                task.getCreatedByUserId(),
                creatorUsername(creatorsById, task.getCreatedByUserId()),
                animeDisplayTitle(task),
                task.getStatus(),
                task.getStage(),
                sourceType(task.getSourceType()),
                task.getReleaseTitle(),
                progressSummary(task.getOrganizedCount(), task.getSkippedCount()),
                1,
                taskCenterDetailPath(ANIME_TASK_TYPE, task.getId()),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
        return listingCandidate(
                response,
                task.getMagnetHash(),
                task.getAttemptGroupId(),
                task.getRetryOfTaskType(),
                task.getRetryOfTaskId(),
                task.getTitle(),
                task.getNameCn(),
                task.getName()
        );
    }

    private TaskCenterListingCandidate adultItem(
            AdultMagnetIngestTask task,
            Map<Long, User> creatorsById
    ) {
        OpenListIngestTaskCenterItemResponse response = new OpenListIngestTaskCenterItemResponse(
                ADULT_TASK_TYPE,
                task.getId(),
                ADULT_PRODUCT_TYPE,
                task.getCreatedByUserId(),
                creatorUsername(creatorsById, task.getCreatedByUserId()),
                adultTitle(task),
                task.getStatus(),
                task.getStage(),
                MANUAL_MAGNET_SOURCE,
                null,
                adultProgressSummary(task),
                1,
                taskCenterDetailPath(ADULT_TASK_TYPE, task.getId()),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
        return listingCandidate(
                response,
                null,
                task.getAttemptGroupId(),
                task.getRetryOfTaskType(),
                task.getRetryOfTaskId()
        );
    }

    private TaskAttemptSnapshot movieSnapshot(MovieMagnetIngestTask task) {
        return new TaskAttemptSnapshot(
                MOVIE_TASK_TYPE,
                task.getId(),
                effectiveAttemptGroupId(MOVIE_TASK_TYPE, task.getId(), task.getAttemptGroupId()),
                MOVIE_PRODUCT_TYPE,
                movieDisplayTitle(task),
                task.getStatus(),
                task.getStage(),
                sourceType(task.getSourceType()),
                task.getCreatedByUserId(),
                creatorUsername(task.getCreatedByUserId()),
                normalizeTaskType(task.getRetryOfTaskType()),
                task.getRetryOfTaskId(),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }

    private TaskAttemptSnapshot seriesSnapshot(SeriesMagnetIngestTask task) {
        return new TaskAttemptSnapshot(
                SERIES_TASK_TYPE,
                task.getId(),
                effectiveAttemptGroupId(SERIES_TASK_TYPE, task.getId(), task.getAttemptGroupId()),
                seriesProductType(task.getTaskProductType()),
                seriesDisplayTitle(task),
                task.getStatus(),
                task.getStage(),
                sourceType(task.getSourceType()),
                task.getCreatedByUserId(),
                creatorUsername(task.getCreatedByUserId()),
                normalizeTaskType(task.getRetryOfTaskType()),
                task.getRetryOfTaskId(),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }

    private TaskAttemptSnapshot animeSnapshot(AnimeMagnetIngestTask task) {
        return new TaskAttemptSnapshot(
                ANIME_TASK_TYPE,
                task.getId(),
                effectiveAttemptGroupId(ANIME_TASK_TYPE, task.getId(), task.getAttemptGroupId()),
                ANIME_PRODUCT_TYPE,
                animeDisplayTitle(task),
                task.getStatus(),
                task.getStage(),
                sourceType(task.getSourceType()),
                task.getCreatedByUserId(),
                creatorUsername(task.getCreatedByUserId()),
                normalizeTaskType(task.getRetryOfTaskType()),
                task.getRetryOfTaskId(),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }

    private TaskAttemptSnapshot adultSnapshot(AdultMagnetIngestTask task) {
        return new TaskAttemptSnapshot(
                ADULT_TASK_TYPE,
                task.getId(),
                effectiveAttemptGroupId(ADULT_TASK_TYPE, task.getId(), task.getAttemptGroupId()),
                ADULT_PRODUCT_TYPE,
                adultTitle(task),
                task.getStatus(),
                task.getStage(),
                MANUAL_MAGNET_SOURCE,
                task.getCreatedByUserId(),
                creatorUsername(task.getCreatedByUserId()),
                normalizeTaskType(task.getRetryOfTaskType()),
                task.getRetryOfTaskId(),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }

    private String effectiveAttemptGroupId(String taskType, String taskId, String attemptGroupId) {
        if (StringUtils.hasText(attemptGroupId)) {
            return attemptGroupId;
        }
        return "LEGACY:%s:%s".formatted(taskType, taskId);
    }

    private boolean canAccessAttempt(User user, TaskAttemptSnapshot snapshot) {
        if (ADULT_TASK_TYPE.equals(snapshot.taskType())) {
            return isAdmin(user);
        }
        return canAccessTask(user, snapshot.createdByUserId());
    }

    private String taskCenterDetailPath(String taskType, String taskId) {
        return "/tasks/%s/%s".formatted(taskType.toLowerCase(Locale.ROOT), taskId);
    }

    private TaskCenterListingCandidate listingCandidate(
            OpenListIngestTaskCenterItemResponse response,
            String magnetHash,
            String attemptGroupId,
            String retryOfTaskType,
            String retryOfTaskId,
            String... extraSearchValues
    ) {
        TaskAttemptKey taskKey = new TaskAttemptKey(response.taskType(), response.id());
        TaskAttemptKey retryOfKey = taskAttemptKey(retryOfTaskType, retryOfTaskId);
        return new TaskCenterListingCandidate(
                response,
                effectiveAttemptGroupId(response.taskType(), response.id(), attemptGroupId),
                searchValues(response, magnetHash, extraSearchValues),
                taskKey,
                retryOfKey,
                response.sourceType()
        );
    }

    private List<TaskCenterListingCandidate> collapseAttemptChains(List<TaskCenterListingCandidate> candidates) {
        Map<String, List<TaskCenterListingCandidate>> candidatesByAttemptGroup = candidates.stream()
                .collect(Collectors.groupingBy(
                        TaskCenterListingCandidate::attemptGroupId,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        return candidatesByAttemptGroup.values().stream()
                .map(this::latestAttemptCandidate)
                .toList();
    }

    private TaskCenterListingCandidate latestAttemptCandidate(List<TaskCenterListingCandidate> attempts) {
        TaskCenterListingCandidate latestAttempt = attempts.stream()
                .sorted(taskCenterOrdering())
                .findFirst()
                .orElseThrow();
        List<String> searchValues = attempts.stream()
                .flatMap(attempt -> attempt.searchValues().stream())
                .distinct()
                .toList();
        Map<TaskAttemptKey, TaskCenterListingCandidate> attemptsByKey = attempts.stream()
                .collect(Collectors.toMap(TaskCenterListingCandidate::taskKey, Function.identity()));
        return new TaskCenterListingCandidate(
                withAttemptCount(latestAttempt.response(), attempts.size()),
                latestAttempt.attemptGroupId(),
                searchValues,
                latestAttempt.taskKey(),
                latestAttempt.retryOfKey(),
                originSourceType(latestAttempt, attemptsByKey)
        );
    }

    private String originSourceType(
            TaskCenterListingCandidate latestAttempt,
            Map<TaskAttemptKey, TaskCenterListingCandidate> attemptsByKey
    ) {
        TaskCenterListingCandidate current = latestAttempt;
        Set<TaskAttemptKey> visited = new HashSet<>();
        while (current.retryOfKey() != null && visited.add(current.taskKey())) {
            TaskCenterListingCandidate parent = attemptsByKey.get(current.retryOfKey());
            if (parent == null) {
                break;
            }
            current = parent;
        }
        return current.response().sourceType();
    }

    private TaskAttemptKey taskAttemptKey(String taskType, String taskId) {
        if (!StringUtils.hasText(taskType) || !StringUtils.hasText(taskId)) {
            return null;
        }
        return new TaskAttemptKey(normalizeTaskType(taskType), taskId);
    }

    private OpenListIngestTaskCenterItemResponse withAttemptCount(
            OpenListIngestTaskCenterItemResponse response,
            int attemptCount
    ) {
        return new OpenListIngestTaskCenterItemResponse(
                response.taskType(),
                response.id(),
                response.productType(),
                response.createdByUserId(),
                response.createdByUsername(),
                response.title(),
                response.status(),
                response.stage(),
                response.sourceType(),
                response.releaseTitle(),
                response.progressSummary(),
                attemptCount,
                response.detailPath(),
                response.createdAt(),
                response.updatedAt()
        );
    }

    private List<String> searchValues(
            OpenListIngestTaskCenterItemResponse response,
            String magnetHash,
            String... extraSearchValues
    ) {
        List<String> values = new ArrayList<>();
        addSearchValue(values, response.id());
        addSearchValue(values, response.title());
        addSearchValue(values, response.releaseTitle());
        addSearchValue(values, magnetHash);
        if (extraSearchValues != null) {
            Arrays.stream(extraSearchValues).forEach(value -> addSearchValue(values, value));
        }
        return List.copyOf(values);
    }

    private void addSearchValue(List<String> values, String value) {
        if (StringUtils.hasText(value)) {
            values.add(value);
        }
    }

    private Comparator<TaskCenterListingCandidate> taskCenterOrdering() {
        Comparator<TaskCenterListingCandidate> byUpdatedAt =
                Comparator.comparing(this::effectiveUpdatedAt);
        Comparator<TaskCenterListingCandidate> byCreatedAt =
                Comparator.comparing(this::effectiveCreatedAt);
        return byUpdatedAt.reversed()
                .thenComparing(byCreatedAt.reversed())
                .thenComparing(item -> item.response().taskType())
                .thenComparing(item -> item.response().id());
    }

    private LocalDateTime effectiveUpdatedAt(TaskCenterListingCandidate item) {
        LocalDateTime updatedAt = item.response().updatedAt() == null
                ? item.response().createdAt()
                : item.response().updatedAt();
        return updatedAt == null ? LocalDateTime.MIN : updatedAt;
    }

    private LocalDateTime effectiveCreatedAt(TaskCenterListingCandidate item) {
        LocalDateTime createdAt = item.response().createdAt();
        return createdAt == null ? LocalDateTime.MIN : createdAt;
    }

    private String sourceType(String sourceType) {
        return StringUtils.hasText(sourceType) ? sourceType : MANUAL_MAGNET_SOURCE;
    }

    private String seriesProductType(String taskProductType) {
        if (!StringUtils.hasText(taskProductType)) {
            return SERIES_PRODUCT_TYPE;
        }
        return ANIME_PRODUCT_TYPE.equals(taskProductType) ? ANIME_PRODUCT_TYPE : SERIES_PRODUCT_TYPE;
    }

    private String progressSummary(Integer organizedCount, Integer skippedCount) {
        int organized = organizedCount == null ? 0 : organizedCount;
        int skipped = skippedCount == null ? 0 : skippedCount;
        return "已整理 %d，跳过 %d".formatted(organized, skipped);
    }

    private String adultTitle(AdultMagnetIngestTask task) {
        String category = StringUtils.hasText(task.getCategory()) ? task.getCategory() : ADULT_PRODUCT_TYPE;
        if (!StringUtils.hasText(task.getDateFolder())) {
            return "%s 批量任务".formatted(category);
        }
        return "%s 批量任务 %s".formatted(category, task.getDateFolder());
    }

    private String movieDisplayTitle(MovieMagnetIngestTask task) {
        String title = preferredChineseText(task.getTitle(), task.getOriginalTitle());
        String fallbackTitle = StringUtils.hasText(title) ? title : "电影任务";
        return task.getYear() == null ? fallbackTitle : "%s (%s)".formatted(fallbackTitle, task.getYear());
    }

    private String seriesDisplayTitle(SeriesMagnetIngestTask task) {
        String title = preferredChineseText(task.getTitle(), task.getSeriesName(), task.getOriginalTitle());
        String fallbackTitle = StringUtils.hasText(title) ? title : "剧集任务";
        String seasonFolder = trimToNull(task.getSeasonFolder());
        return seasonFolder == null ? fallbackTitle : "%s %s".formatted(fallbackTitle, seasonFolder);
    }

    private String animeDisplayTitle(AnimeMagnetIngestTask task) {
        String title = preferredChineseText(task.getNameCn(), task.getTitle(), task.getName());
        String fallbackTitle = StringUtils.hasText(title) ? title : "动漫任务";
        Integer seasonNumber = task.getSeasonNumber();
        return seasonNumber == null ? fallbackTitle : "%s S%02d".formatted(fallbackTitle, seasonNumber);
    }

    private String preferredChineseText(String... values) {
        String fallback = null;
        for (String value : values) {
            String candidate = trimToNull(value);
            if (!StringUtils.hasText(candidate)) {
                continue;
            }
            if (fallback == null) {
                fallback = candidate;
            }
            if (containsChinese(candidate)) {
                return candidate;
            }
        }
        return fallback;
    }

    private boolean containsChinese(String value) {
        return StringUtils.hasText(value) && value.codePoints().anyMatch(codePoint ->
                codePoint >= 0x4E00 && codePoint <= 0x9FFF
        );
    }

    private String adultProgressSummary(AdultMagnetIngestTask task) {
        return "已提交 %d，成功 %d，失败 %d，重复 %d，保留 %d，删除 %d".formatted(
                safeCount(task.getSubmittedCount()),
                safeCount(task.getSucceededCount()),
                safeCount(task.getFailedCount()),
                safeCount(task.getDuplicateCount()),
                safeCount(task.getKeptCount()),
                safeCount(task.getDeletedCount())
        );
    }

    private List<String> adultDownloadLinks(String downloadLinksJson) {
        if (!StringUtils.hasText(downloadLinksJson)) {
            return null;
        }
        try {
            return objectMapper.readValue(downloadLinksJson, new TypeReference<List<String>>() { });
        } catch (Exception exception) {
            return null;
        }
    }

    private int safeCount(Integer count) {
        return count == null ? 0 : count;
    }

    private boolean matchesProductType(TaskCenterListingCandidate item, String productType) {
        return ALL_FILTER.equals(productType) || productType.equals(item.response().productType());
    }

    private boolean matchesSourceType(TaskCenterListingCandidate item, String sourceType) {
        if (ADULT_PRODUCT_TYPE.equals(item.response().productType())) {
            return ALL_FILTER.equals(sourceType);
        }
        return ALL_FILTER.equals(sourceType) || sourceType.equals(item.originSourceType());
    }

    private boolean matchesView(TaskCenterListingCandidate item, String view) {
        String status = item.response().status();
        return switch (view) {
            case IN_PROGRESS_VIEW -> IN_PROGRESS_STATUSES.contains(status);
            case NEEDS_ATTENTION_VIEW -> NEEDS_ATTENTION_STATUSES.contains(status);
            case SUCCEEDED_VIEW -> "SUCCEEDED".equals(status);
            default -> true;
        };
    }

    private boolean matchesKeyword(TaskCenterListingCandidate item, String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return true;
        }
        return item.searchValues().stream()
                .anyMatch(value -> containsIgnoreCase(value, keyword));
    }

    private boolean containsIgnoreCase(String value, String keyword) {
        return StringUtils.hasText(value)
                && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private int countByView(List<TaskCenterListingCandidate> items, String view) {
        return (int) items.stream()
                .filter(item -> matchesView(item, view))
                .count();
    }

    private String normalizeView(String view) {
        return normalizeToken(view);
    }

    private String normalizeProductType(String productType) {
        return normalizeToken(productType);
    }

    private String normalizeSourceType(String sourceType) {
        return normalizeToken(sourceType);
    }

    private String normalizeToken(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : ALL_FILTER;
    }

    private String normalizeKeyword(String keyword) {
        return StringUtils.hasText(keyword) ? keyword.trim().toLowerCase(Locale.ROOT) : "";
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private int normalizePage(Integer page, int total, int pageSize) {
        int requestedPage = page == null ? DEFAULT_PAGE : page;
        if (total <= 0) {
            return DEFAULT_PAGE;
        }
        int maxPage = (int) Math.ceil((double) total / pageSize);
        return Math.min(requestedPage, maxPage);
    }

    private int normalizePageSize(Integer pageSize) {
        return pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
    }

    private Map<Long, User> creatorsById(
            List<MovieMagnetIngestTask> movieTasks,
            List<SeriesMagnetIngestTask> seriesTasks,
            List<AnimeMagnetIngestTask> animeTasks,
            List<AdultMagnetIngestTask> adultTasks
    ) {
        Set<Long> creatorIds = new HashSet<>();
        movieTasks.stream()
                .map(MovieMagnetIngestTask::getCreatedByUserId)
                .filter(id -> id != null)
                .forEach(creatorIds::add);
        seriesTasks.stream()
                .map(SeriesMagnetIngestTask::getCreatedByUserId)
                .filter(id -> id != null)
                .forEach(creatorIds::add);
        animeTasks.stream()
                .map(AnimeMagnetIngestTask::getCreatedByUserId)
                .filter(id -> id != null)
                .forEach(creatorIds::add);
        adultTasks.stream()
                .map(AdultMagnetIngestTask::getCreatedByUserId)
                .filter(id -> id != null)
                .forEach(creatorIds::add);
        if (creatorIds.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(creatorIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private String creatorUsername(Map<Long, User> creatorsById, Long creatorId) {
        User creator = creatorId == null ? null : creatorsById.get(creatorId);
        return creator == null ? null : creator.getUsername();
    }

    private boolean isAdmin(User user) {
        return user != null && ADMIN_ROLE.equalsIgnoreCase(user.getRole());
    }

    private record TaskCenterListingCandidate(
            OpenListIngestTaskCenterItemResponse response,
            String attemptGroupId,
            List<String> searchValues,
            TaskAttemptKey taskKey,
            TaskAttemptKey retryOfKey,
            String originSourceType
    ) {
    }

    private record TaskAttemptSnapshot(
            String taskType,
            String id,
            String attemptGroupId,
            String productType,
            String title,
            String status,
            String stage,
            String sourceType,
            Long createdByUserId,
            String createdByUsername,
            String retryOfTaskType,
            String retryOfTaskId,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        TaskAttemptKey key() {
            return new TaskAttemptKey(taskType, id);
        }

        java.util.Optional<TaskAttemptKey> retryOfKey() {
            if (!StringUtils.hasText(retryOfTaskType) || !StringUtils.hasText(retryOfTaskId)) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new TaskAttemptKey(retryOfTaskType, retryOfTaskId));
        }
    }

    private record TaskAttemptKey(
            String taskType,
            String taskId
    ) {
        boolean matches(String otherTaskType, String otherTaskId) {
            return taskType.equals(otherTaskType) && taskId.equals(otherTaskId);
        }
    }

    private record ReleaseRetryTitles(
            String title,
            String originalTitle
    ) {
    }
}
