package com.medianexus.orchestrator.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medianexus.orchestrator.dto.taskcenter.response.OpenListIngestTaskCenterItemResponse;
import com.medianexus.orchestrator.dto.taskcenter.response.OpenListIngestTaskCenterListResponse;
import com.medianexus.orchestrator.mapper.AnimeMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.MovieMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.SeriesMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.UserMapper;
import com.medianexus.orchestrator.model.AnimeMagnetIngestTask;
import com.medianexus.orchestrator.model.MovieMagnetIngestTask;
import com.medianexus.orchestrator.model.SeriesMagnetIngestTask;
import com.medianexus.orchestrator.model.User;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
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
    private static final String MOVIE_PRODUCT_TYPE = "MOVIE";
    private static final String SERIES_PRODUCT_TYPE = "SERIES";
    private static final String ANIME_PRODUCT_TYPE = "ANIME";
    private static final String MANUAL_MAGNET_SOURCE = "MANUAL_MAGNET";

    private final AuthService authService;
    private final MovieMagnetIngestTaskMapper movieTaskMapper;
    private final SeriesMagnetIngestTaskMapper seriesTaskMapper;
    private final AnimeMagnetIngestTaskMapper animeTaskMapper;
    private final UserMapper userMapper;

    public OpenListIngestTaskCenterService(
            AuthService authService,
            MovieMagnetIngestTaskMapper movieTaskMapper,
            SeriesMagnetIngestTaskMapper seriesTaskMapper,
            AnimeMagnetIngestTaskMapper animeTaskMapper,
            UserMapper userMapper
    ) {
        this.authService = authService;
        this.movieTaskMapper = movieTaskMapper;
        this.seriesTaskMapper = seriesTaskMapper;
        this.animeTaskMapper = animeTaskMapper;
        this.userMapper = userMapper;
    }

    public OpenListIngestTaskCenterListResponse listOpenListIngestTasks() {
        User user = authService.requireCurrentUser();
        List<MovieMagnetIngestTask> movieTasks = movieTaskMapper.selectList(ownedMovieTasks(user));
        List<SeriesMagnetIngestTask> seriesTasks = seriesTaskMapper.selectList(ownedSeriesTasks(user));
        List<AnimeMagnetIngestTask> animeTasks = animeTaskMapper.selectList(ownedAnimeTasks(user));
        Map<Long, User> creatorsById = creatorsById(movieTasks, seriesTasks, animeTasks);
        List<OpenListIngestTaskCenterItemResponse> items = new ArrayList<>();

        items.addAll(movieTasks.stream()
                .map(task -> movieItem(task, creatorsById))
                .toList());
        items.addAll(seriesTasks.stream()
                .map(task -> seriesItem(task, creatorsById))
                .toList());
        items.addAll(animeTasks.stream()
                .map(task -> animeItem(task, creatorsById))
                .toList());

        List<OpenListIngestTaskCenterItemResponse> sortedItems = items.stream()
                .sorted(taskCenterOrdering())
                .toList();
        return new OpenListIngestTaskCenterListResponse(sortedItems, sortedItems.size());
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

    private OpenListIngestTaskCenterItemResponse movieItem(
            MovieMagnetIngestTask task,
            Map<Long, User> creatorsById
    ) {
        return new OpenListIngestTaskCenterItemResponse(
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
    }

    private OpenListIngestTaskCenterItemResponse seriesItem(
            SeriesMagnetIngestTask task,
            Map<Long, User> creatorsById
    ) {
        return new OpenListIngestTaskCenterItemResponse(
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
    }

    private OpenListIngestTaskCenterItemResponse animeItem(
            AnimeMagnetIngestTask task,
            Map<Long, User> creatorsById
    ) {
        return new OpenListIngestTaskCenterItemResponse(
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
    }

    private Comparator<OpenListIngestTaskCenterItemResponse> taskCenterOrdering() {
        Comparator<OpenListIngestTaskCenterItemResponse> byUpdatedAt =
                Comparator.comparing(this::effectiveUpdatedAt);
        Comparator<OpenListIngestTaskCenterItemResponse> byCreatedAt =
                Comparator.comparing(this::effectiveCreatedAt);
        return byUpdatedAt.reversed()
                .thenComparing(byCreatedAt.reversed())
                .thenComparing(OpenListIngestTaskCenterItemResponse::taskType)
                .thenComparing(OpenListIngestTaskCenterItemResponse::id);
    }

    private LocalDateTime effectiveUpdatedAt(OpenListIngestTaskCenterItemResponse item) {
        LocalDateTime updatedAt = item.updatedAt() == null ? item.createdAt() : item.updatedAt();
        return updatedAt == null ? LocalDateTime.MIN : updatedAt;
    }

    private LocalDateTime effectiveCreatedAt(OpenListIngestTaskCenterItemResponse item) {
        return item.createdAt() == null ? LocalDateTime.MIN : item.createdAt();
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

    private Map<Long, User> creatorsById(
            List<MovieMagnetIngestTask> movieTasks,
            List<SeriesMagnetIngestTask> seriesTasks,
            List<AnimeMagnetIngestTask> animeTasks
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
}
