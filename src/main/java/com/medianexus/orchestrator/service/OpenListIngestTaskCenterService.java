package com.medianexus.orchestrator.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medianexus.orchestrator.dto.taskcenter.response.OpenListIngestTaskCenterItemResponse;
import com.medianexus.orchestrator.dto.taskcenter.response.OpenListIngestTaskCenterListResponse;
import com.medianexus.orchestrator.mapper.AdultMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.AnimeMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.MovieMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.SeriesMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.UserMapper;
import com.medianexus.orchestrator.model.AdultMagnetIngestTask;
import com.medianexus.orchestrator.model.AnimeMagnetIngestTask;
import com.medianexus.orchestrator.model.MovieMagnetIngestTask;
import com.medianexus.orchestrator.model.SeriesMagnetIngestTask;
import com.medianexus.orchestrator.model.User;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
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
    private final UserMapper userMapper;

    public OpenListIngestTaskCenterService(
            AuthService authService,
            MovieMagnetIngestTaskMapper movieTaskMapper,
            SeriesMagnetIngestTaskMapper seriesTaskMapper,
            AnimeMagnetIngestTaskMapper animeTaskMapper,
            AdultMagnetIngestTaskMapper adultTaskMapper,
            UserMapper userMapper
    ) {
        this.authService = authService;
        this.movieTaskMapper = movieTaskMapper;
        this.seriesTaskMapper = seriesTaskMapper;
        this.animeTaskMapper = animeTaskMapper;
        this.adultTaskMapper = adultTaskMapper;
        this.userMapper = userMapper;
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

        String normalizedView = normalizeView(view);
        String normalizedProductType = normalizeProductType(productType);
        String normalizedSourceType = normalizeSourceType(sourceType);
        String normalizedKeyword = normalizeKeyword(keyword);
        int normalizedPageSize = normalizePageSize(pageSize);

        List<TaskCenterListingCandidate> searchableItems = candidates.stream()
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
                "%s (%s)".formatted(task.getTitle(), task.getYear()),
                task.getStatus(),
                task.getStage(),
                sourceType(task.getSourceType()),
                task.getReleaseTitle(),
                progressSummary(task.getOrganizedCount(), task.getSkippedCount()),
                "/resources/ingest/movie/" + task.getId(),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
        return new TaskCenterListingCandidate(response, task.getMagnetHash());
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
                "%s %s".formatted(task.getSeriesName(), task.getSeasonFolder()),
                task.getStatus(),
                task.getStage(),
                sourceType(task.getSourceType()),
                task.getReleaseTitle(),
                progressSummary(task.getOrganizedCount(), task.getSkippedCount()),
                "/resources/ingest/series/" + task.getId(),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
        return new TaskCenterListingCandidate(response, task.getMagnetHash());
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
                "%s S%02d".formatted(task.getTitle(), task.getSeasonNumber()),
                task.getStatus(),
                task.getStage(),
                MANUAL_MAGNET_SOURCE,
                null,
                progressSummary(task.getOrganizedCount(), task.getSkippedCount()),
                "/magnet-ingest",
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
        return new TaskCenterListingCandidate(response, task.getMagnetHash());
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
                "/magnet-ingest",
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
        return new TaskCenterListingCandidate(response, null);
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
        return ALL_FILTER.equals(sourceType) || sourceType.equals(item.response().sourceType());
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
        OpenListIngestTaskCenterItemResponse response = item.response();
        return containsIgnoreCase(response.title(), keyword)
                || containsIgnoreCase(response.releaseTitle(), keyword)
                || containsIgnoreCase(item.magnetHash(), keyword);
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
            String magnetHash
    ) {
    }
}
