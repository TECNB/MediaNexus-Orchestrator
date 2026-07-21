package com.medianexus.orchestrator.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.config.OpenListProperties;
import com.medianexus.orchestrator.dto.magnet.request.MovieMagnetIngestRequest;
import com.medianexus.orchestrator.dto.magnet.request.SeriesMagnetIngestRequest;
import com.medianexus.orchestrator.dto.magnet.response.MovieMagnetIngestTaskListResponse;
import com.medianexus.orchestrator.dto.magnet.response.MovieMagnetIngestTaskLogListResponse;
import com.medianexus.orchestrator.dto.magnet.response.MovieMagnetIngestTaskLogResponse;
import com.medianexus.orchestrator.dto.magnet.response.MovieMagnetIngestTaskResponse;
import com.medianexus.orchestrator.dto.magnet.response.SeriesMagnetIngestTaskListResponse;
import com.medianexus.orchestrator.dto.magnet.response.SeriesMagnetIngestTaskLogListResponse;
import com.medianexus.orchestrator.dto.magnet.response.SeriesMagnetIngestTaskLogResponse;
import com.medianexus.orchestrator.dto.magnet.response.SeriesMagnetIngestTaskResponse;
import com.medianexus.orchestrator.integration.openlist.OpenListClient;
import com.medianexus.orchestrator.integration.openlist.OpenListClientException;
import com.medianexus.orchestrator.integration.openlist.OpenListDirectoryPrepareException;
import com.medianexus.orchestrator.integration.openlist.OpenListFileInfo;
import com.medianexus.orchestrator.integration.openlist.OpenListOfflineTaskInfo;
import com.medianexus.orchestrator.mapper.MovieMagnetIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.MovieMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.SeriesMagnetIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.SeriesMagnetIngestTaskMapper;
import com.medianexus.orchestrator.model.MovieMagnetIngestTask;
import com.medianexus.orchestrator.model.MovieMagnetIngestTaskLog;
import com.medianexus.orchestrator.model.SeriesMagnetIngestTask;
import com.medianexus.orchestrator.model.SeriesMagnetIngestTaskLog;
import com.medianexus.orchestrator.model.User;
import com.medianexus.orchestrator.model.UserActionType;
import com.medianexus.orchestrator.service.organization.LibraryOrganizer;
import com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MagnetIngestService {

    private static final Logger log = LoggerFactory.getLogger(MagnetIngestService.class);
    private static final Pattern MAGNET_HASH_PATTERN =
            Pattern.compile("xt=urn:btih:([a-zA-Z0-9]+)", Pattern.CASE_INSENSITIVE);
    private static final List<String> ACTIVE_STATUSES = List.of("PENDING", "SUBMITTED", "DOWNLOADING", "ORGANIZING");
    private static final List<String> UNFINISHED_STATUSES = List.of("PENDING", "SUBMITTED", "DOWNLOADING", "ORGANIZING");
    private static final List<String> TERMINAL_STATUSES = List.of("SUCCEEDED", "PARTIAL_SUCCESS", "FAILED", "INTERRUPTED");
    private static final Duration SAVING_FILES_VISIBLE_GRACE = Duration.ofMinutes(5);
    private static final int FIRST_MOVIE_YEAR = 1888;
    private static final long MIN_MOVIE_VIDEO_BYTES = 100L * 1024L * 1024L;
    private static final int OPENLIST_ORGANIZE_BATCH_SIZE = 10;
    private static final String ADMIN_ROLE = "ADMIN";
    private static final String SERIES_PRODUCT_TYPE = "SERIES";
    private static final String ANIME_PRODUCT_TYPE = "ANIME";
    private static final Set<String> KNOWN_ANIME_AUXILIARY_DIRECTORY_NAMES = Set.of(
            "cd",
            "cds",
            "scan",
            "scans",
            "sp",
            "sps",
            "pv",
            "pvs",
            "menu",
            "menus",
            "font",
            "fonts",
            "extra",
            "extras",
            "special",
            "specials",
            "ost",
            "soundtrack",
            "soundtracks",
            "booklet",
            "booklets",
            "ncop",
            "nced",
            "特典",
            "扫图",
            "扫描"
    );

    private final MovieMagnetIngestTaskMapper movieTaskMapper;
    private final MovieMagnetIngestTaskLogMapper movieTaskLogMapper;
    private final SeriesMagnetIngestTaskMapper seriesTaskMapper;
    private final SeriesMagnetIngestTaskLogMapper seriesTaskLogMapper;
    private final OpenListClient openListClient;
    private final OpenListProperties openListProperties;
    private final MovieSeriesFileRenameService renameService;
    private final LibraryOrganizer animeLibraryOrganizer;
    private final LibraryOrganizer movieLibraryOrganizer;
    private final LibraryOrganizer seriesLibraryOrganizer;
    private final AuthService authService;
    private final UserActionQuotaService userActionQuotaService;
    private final AutoSymlinkRefreshService autoSymlinkRefreshService;
    private final MediaLibraryPresenceService mediaLibraryPresenceService;
    private final ExecutorService executorService;

    @Autowired
    public MagnetIngestService(
            MovieMagnetIngestTaskMapper movieTaskMapper,
            MovieMagnetIngestTaskLogMapper movieTaskLogMapper,
            SeriesMagnetIngestTaskMapper seriesTaskMapper,
            SeriesMagnetIngestTaskLogMapper seriesTaskLogMapper,
            OpenListClient openListClient,
            OpenListProperties openListProperties,
            MovieSeriesFileRenameService renameService,
            @Qualifier("animeLibraryOrganizer") LibraryOrganizer animeLibraryOrganizer,
            @Qualifier("movieLibraryOrganizer") LibraryOrganizer movieLibraryOrganizer,
            @Qualifier("seriesLibraryOrganizer") LibraryOrganizer seriesLibraryOrganizer,
            AuthService authService,
            UserActionQuotaService userActionQuotaService,
            AutoSymlinkRefreshService autoSymlinkRefreshService,
            MediaLibraryPresenceService mediaLibraryPresenceService
    ) {
        this.movieTaskMapper = movieTaskMapper;
        this.movieTaskLogMapper = movieTaskLogMapper;
        this.seriesTaskMapper = seriesTaskMapper;
        this.seriesTaskLogMapper = seriesTaskLogMapper;
        this.openListClient = openListClient;
        this.openListProperties = openListProperties;
        this.renameService = renameService;
        this.animeLibraryOrganizer = animeLibraryOrganizer;
        this.movieLibraryOrganizer = movieLibraryOrganizer;
        this.seriesLibraryOrganizer = seriesLibraryOrganizer;
        this.authService = authService;
        this.userActionQuotaService = userActionQuotaService;
        this.autoSymlinkRefreshService = autoSymlinkRefreshService;
        this.mediaLibraryPresenceService = mediaLibraryPresenceService;
        this.executorService = Executors.newSingleThreadExecutor(new WorkerThreadFactory());
    }

    MagnetIngestService(
            MovieMagnetIngestTaskMapper movieTaskMapper,
            MovieMagnetIngestTaskLogMapper movieTaskLogMapper,
            SeriesMagnetIngestTaskMapper seriesTaskMapper,
            SeriesMagnetIngestTaskLogMapper seriesTaskLogMapper,
            OpenListClient openListClient,
            OpenListProperties openListProperties,
            MovieSeriesFileRenameService renameService,
            LibraryOrganizer libraryOrganizer,
            AuthService authService,
            UserActionQuotaService userActionQuotaService,
            AutoSymlinkRefreshService autoSymlinkRefreshService,
            MediaLibraryPresenceService mediaLibraryPresenceService
    ) {
        this(
                movieTaskMapper,
                movieTaskLogMapper,
                seriesTaskMapper,
                seriesTaskLogMapper,
                openListClient,
                openListProperties,
                renameService,
                libraryOrganizer,
                libraryOrganizer,
                libraryOrganizer,
                authService,
                userActionQuotaService,
                autoSymlinkRefreshService,
                mediaLibraryPresenceService
        );
    }

    @EventListener(ApplicationReadyEvent.class)
    public void markUnfinishedTasksInterrupted() {
        int movieInterruptedCount = movieTaskMapper.update(new LambdaUpdateWrapper<MovieMagnetIngestTask>()
                .in(MovieMagnetIngestTask::getStatus, UNFINISHED_STATUSES)
                .set(MovieMagnetIngestTask::getStatus, "INTERRUPTED")
                .set(MovieMagnetIngestTask::getStage, "interrupted")
                .set(MovieMagnetIngestTask::getErrorMessage, "服务重启，任务已中断")
                .set(MovieMagnetIngestTask::getFinishedAt, LocalDateTime.now()));
        int seriesInterruptedCount = seriesTaskMapper.update(new LambdaUpdateWrapper<SeriesMagnetIngestTask>()
                .in(SeriesMagnetIngestTask::getStatus, UNFINISHED_STATUSES)
                .set(SeriesMagnetIngestTask::getStatus, "INTERRUPTED")
                .set(SeriesMagnetIngestTask::getStage, "interrupted")
                .set(SeriesMagnetIngestTask::getErrorMessage, "服务重启，任务已中断")
                .set(SeriesMagnetIngestTask::getFinishedAt, LocalDateTime.now()));
        if (movieInterruptedCount > 0 || seriesInterruptedCount > 0) {
            log.info(
                    "Marked unfinished movie/series magnet ingest tasks interrupted movieCount={} seriesCount={}",
                    movieInterruptedCount,
                    seriesInterruptedCount
            );
        }
    }

    public MovieMagnetIngestTaskResponse createMovieTask(MovieMagnetIngestRequest request) {
        return createMovieTask(request, ReleaseIngestMetadata.manual());
    }

    public MovieMagnetIngestTaskResponse createMovieTask(
            MovieMagnetIngestRequest request,
            ReleaseIngestMetadata metadata
    ) {
        User user = authService.requireCurrentUser();
        MovieTaskPlan plan = buildMoviePlan(request);
        mediaLibraryPresenceService.requireMovieAbsent(plan.tmdbId());
        ReleaseIngestMetadata releaseMetadata = metadata == null ? ReleaseIngestMetadata.manual() : metadata;

        MovieMagnetIngestTask activeTask = movieTaskMapper.selectOne(new LambdaQueryWrapper<MovieMagnetIngestTask>()
                .eq(MovieMagnetIngestTask::getMagnetHash, plan.magnetHash())
                .in(MovieMagnetIngestTask::getStatus, ACTIVE_STATUSES)
                .last("LIMIT 1"));
        if (activeTask != null) {
            if (canAccessMovieTask(user, activeTask)) {
                writeMovieLog(activeTask.getId(), "INFO", activeTask.getStage(), "发现相同 magnet 正在处理，返回已有任务", null);
                return toMovieResponse(activeTask);
            }
            throw badRequest("相同 magnet 正在处理中，请稍后再试");
        }

        return insertMovieTask(user, plan, releaseMetadata, null);
    }

    public MovieMagnetIngestTaskResponse createMovieRetryTask(
            MovieMagnetIngestTask originalTask,
            String magnet,
            TaskRetryReference retryReference
    ) {
        return createMovieRetryTask(originalTask, magnet, ReleaseIngestMetadata.manual(), retryReference);
    }

    public MovieMagnetIngestTaskResponse createMovieRetryTask(
            MovieMagnetIngestTask originalTask,
            String magnet,
            ReleaseIngestMetadata releaseMetadata,
            TaskRetryReference retryReference
    ) {
        User user = authService.requireCurrentUser();
        mediaLibraryPresenceService.requireMovieAbsent(originalTask.getTmdbId());
        String normalizedMagnet = normalizeMagnet(magnet);
        String magnetHash = extractMagnetHash(normalizedMagnet);
        MovieMagnetIngestTask activeTask = movieTaskMapper.selectOne(new LambdaQueryWrapper<MovieMagnetIngestTask>()
                .eq(MovieMagnetIngestTask::getMagnetHash, magnetHash)
                .in(MovieMagnetIngestTask::getStatus, ACTIVE_STATUSES)
                .last("LIMIT 1"));
        if (activeTask != null) {
            throw badRequest("相同 magnet 正在处理中，请稍后再试");
        }

        MovieTaskPlan plan = new MovieTaskPlan(
                normalizedMagnet,
                magnetHash,
                originalTask.getTitle(),
                trimToNull(originalTask.getOriginalTitle()),
                originalTask.getYear(),
                originalTask.getTmdbId(),
                null,
                originalTask.getSavePath()
        );
        return insertMovieTask(user, plan, releaseMetadata, retryReference);
    }

    private MovieMagnetIngestTaskResponse insertMovieTask(
            User user,
            MovieTaskPlan plan,
            ReleaseIngestMetadata releaseMetadata,
            TaskRetryReference retryReference
    ) {
        userActionQuotaService.consumeDailyContentCreate(user, UserActionType.MAGNET_INGEST_CREATE);
        String taskId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();

        MovieMagnetIngestTask task = new MovieMagnetIngestTask();
        task.setId(taskId);
        task.setStatus("PENDING");
        task.setStage("created");
        task.setMagnet(plan.magnet());
        task.setMagnetHash(plan.magnetHash());
        task.setTitle(plan.title());
        task.setOriginalTitle(plan.originalTitle());
        task.setYear(plan.year());
        task.setTmdbId(plan.tmdbId());
        applyReleaseMetadata(task, releaseMetadata);
        task.setSavePath(plan.savePath());
        task.setTempPath(plan.savePath());
        task.setAttemptGroupId(retryReference == null ? taskId : retryReference.attemptGroupId());
        if (retryReference != null) {
            task.setRetryOfTaskType(retryReference.taskType());
            task.setRetryOfTaskId(retryReference.taskId());
        }
        task.setCreatedByUserId(user.getId());
        task.setOrganizedCount(0);
        task.setSkippedCount(0);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        movieTaskMapper.insert(task);
        MovieMagnetIngestTaskResponse response = toMovieResponse(task);
        try {
            writeMovieLog(taskId, "INFO", "created", "已创建电影磁力任务", "savePath=" + plan.savePath());
            log.info(
                    "Created movie magnet ingest task taskId={} userId={} magnetHash={} savePath={}",
                    taskId,
                    user.getId(),
                    plan.magnetHash(),
                    plan.savePath()
            );
            executorService.submit(() -> runMovieTask(taskId));
            return response;
        } catch (RuntimeException exception) {
            removeMovieTaskAfterCreationFailure(taskId, exception);
            throw exception;
        }
    }

    public SeriesMagnetIngestTaskResponse createSeriesTask(SeriesMagnetIngestRequest request) {
        return createSeriesTask(request, ReleaseIngestMetadata.manual());
    }

    public SeriesMagnetIngestTaskResponse createSeriesTask(
            SeriesMagnetIngestRequest request,
            ReleaseIngestMetadata metadata
    ) {
        User user = authService.requireCurrentUser();
        SeriesTaskPlan plan = buildSeriesPlan(request);
        mediaLibraryPresenceService.requireSeriesSeasonAbsent(plan.tmdbId(), plan.seasonNumber());
        ReleaseIngestMetadata releaseMetadata = metadata == null ? ReleaseIngestMetadata.manual() : metadata;
        return createPlannedSeriesTask(user, plan, releaseMetadata, SERIES_PRODUCT_TYPE);
    }

    public SeriesMagnetIngestTaskResponse createAnimeSeasonSeriesTask(
            SeriesMagnetIngestRequest request,
            ReleaseIngestMetadata metadata
    ) {
        User user = authService.requireCurrentUser();
        SeriesTaskPlan plan = buildAnimeSeasonSeriesPlan(request);
        mediaLibraryPresenceService.requireSeriesSeasonAbsent(plan.tmdbId(), plan.seasonNumber());
        ReleaseIngestMetadata releaseMetadata = metadata == null ? ReleaseIngestMetadata.manual() : metadata;
        return createPlannedSeriesTask(user, plan, releaseMetadata, ANIME_PRODUCT_TYPE);
    }

    public SeriesMagnetIngestTaskResponse createSeriesRetryTask(
            SeriesMagnetIngestTask originalTask,
            String magnet,
            TaskRetryReference retryReference
    ) {
        return createSeriesRetryTask(originalTask, magnet, ReleaseIngestMetadata.manual(), retryReference);
    }

    public SeriesMagnetIngestTaskResponse createSeriesRetryTask(
            SeriesMagnetIngestTask originalTask,
            String magnet,
            ReleaseIngestMetadata releaseMetadata,
            TaskRetryReference retryReference
    ) {
        User user = authService.requireCurrentUser();
        mediaLibraryPresenceService.requireSeriesSeasonAbsent(
                originalTask.getTmdbId(),
                originalTask.getSeasonNumber()
        );
        String normalizedMagnet = normalizeMagnet(magnet);
        String magnetHash = extractMagnetHash(normalizedMagnet);
        SeriesMagnetIngestTask activeTask = seriesTaskMapper.selectOne(new LambdaQueryWrapper<SeriesMagnetIngestTask>()
                .eq(SeriesMagnetIngestTask::getMagnetHash, magnetHash)
                .in(SeriesMagnetIngestTask::getStatus, ACTIVE_STATUSES)
                .last("LIMIT 1"));
        if (activeTask != null) {
            throw badRequest("相同 magnet 正在处理中，请稍后再试");
        }

        SeriesTaskPlan plan = new SeriesTaskPlan(
                normalizedMagnet,
                magnetHash,
                originalTask.getTitle(),
                trimToNull(originalTask.getOriginalTitle()),
                originalTask.getSeasonNumber(),
                originalTask.getTmdbId(),
                originalTask.getSeriesName(),
                originalTask.getSeasonFolder(),
                originalTask.getSavePath()
        );
        return insertSeriesTask(
                user,
                plan,
                releaseMetadata,
                seriesTaskProductType(originalTask),
                retryReference
        );
    }

    private SeriesMagnetIngestTaskResponse createPlannedSeriesTask(
            User user,
            SeriesTaskPlan plan,
            ReleaseIngestMetadata releaseMetadata,
            String persistedProductType
    ) {
        SeriesMagnetIngestTask activeTask = seriesTaskMapper.selectOne(new LambdaQueryWrapper<SeriesMagnetIngestTask>()
                .eq(SeriesMagnetIngestTask::getMagnetHash, plan.magnetHash())
                .in(SeriesMagnetIngestTask::getStatus, ACTIVE_STATUSES)
                .last("LIMIT 1"));
        if (activeTask != null) {
            if (canAccessSeriesTask(user, activeTask)) {
                writeSeriesLog(activeTask.getId(), "INFO", activeTask.getStage(), "发现相同 magnet 正在处理，返回已有任务", null);
                return toSeriesResponse(activeTask);
            }
            throw badRequest("相同 magnet 正在处理中，请稍后再试");
        }

        return insertSeriesTask(user, plan, releaseMetadata, persistedProductType, null);
    }

    private SeriesMagnetIngestTaskResponse insertSeriesTask(
            User user,
            SeriesTaskPlan plan,
            ReleaseIngestMetadata releaseMetadata,
            String persistedProductType,
            TaskRetryReference retryReference
    ) {
        userActionQuotaService.consumeDailyContentCreate(user, UserActionType.MAGNET_INGEST_CREATE);
        String taskId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();

        SeriesMagnetIngestTask task = new SeriesMagnetIngestTask();
        task.setId(taskId);
        task.setStatus("PENDING");
        task.setStage("created");
        task.setMagnet(plan.magnet());
        task.setMagnetHash(plan.magnetHash());
        task.setTitle(plan.title());
        task.setOriginalTitle(plan.originalTitle());
        task.setSeasonNumber(plan.seasonNumber());
        task.setTmdbId(plan.tmdbId());
        task.setTaskProductType(persistedProductType);
        applyReleaseMetadata(task, releaseMetadata);
        task.setSeriesName(plan.seriesName());
        task.setSeasonFolder(plan.seasonFolder());
        task.setSavePath(plan.savePath());
        task.setTempPath(plan.savePath());
        task.setAttemptGroupId(retryReference == null ? taskId : retryReference.attemptGroupId());
        if (retryReference != null) {
            task.setRetryOfTaskType(retryReference.taskType());
            task.setRetryOfTaskId(retryReference.taskId());
        }
        task.setCreatedByUserId(user.getId());
        task.setOrganizedCount(0);
        task.setSkippedCount(0);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        seriesTaskMapper.insert(task);
        SeriesMagnetIngestTaskResponse response = toSeriesResponse(task);
        try {
            writeSeriesLog(taskId, "INFO", "created", "已创建磁力任务", "savePath=" + plan.savePath());
            log.info(
                    "Created {} magnet ingest task taskId={} userId={} magnetHash={} savePath={}",
                    persistedProductType,
                    taskId,
                    user.getId(),
                    plan.magnetHash(),
                    plan.savePath()
            );
            executorService.submit(() -> runSeriesTask(taskId));
            return response;
        } catch (RuntimeException exception) {
            removeSeriesTaskAfterCreationFailure(taskId, exception);
            throw exception;
        }
    }

    public MovieMagnetIngestTaskListResponse listMovieTasks() {
        User user = authService.requireCurrentUser();
        LambdaQueryWrapper<MovieMagnetIngestTask> queryWrapper = new LambdaQueryWrapper<MovieMagnetIngestTask>()
                .orderByDesc(MovieMagnetIngestTask::getCreatedAt)
                .last("LIMIT 20");
        if (!isAdmin(user)) {
            queryWrapper.eq(MovieMagnetIngestTask::getCreatedByUserId, user.getId());
        }
        List<MovieMagnetIngestTaskResponse> items = movieTaskMapper.selectList(queryWrapper).stream()
                .map(this::toMovieResponse)
                .toList();
        return new MovieMagnetIngestTaskListResponse(items, items.size());
    }

    public SeriesMagnetIngestTaskListResponse listSeriesTasks() {
        User user = authService.requireCurrentUser();
        LambdaQueryWrapper<SeriesMagnetIngestTask> queryWrapper = new LambdaQueryWrapper<SeriesMagnetIngestTask>()
                .orderByDesc(SeriesMagnetIngestTask::getCreatedAt)
                .last("LIMIT 20");
        if (!isAdmin(user)) {
            queryWrapper.eq(SeriesMagnetIngestTask::getCreatedByUserId, user.getId());
        }
        List<SeriesMagnetIngestTaskResponse> items = seriesTaskMapper.selectList(queryWrapper).stream()
                .map(this::toSeriesResponse)
                .toList();
        return new SeriesMagnetIngestTaskListResponse(items, items.size());
    }

    public MovieMagnetIngestTaskResponse getMovieTask(String taskId) {
        User user = authService.requireCurrentUser();
        return toMovieResponse(getAccessibleMovieTask(taskId, user));
    }

    public SeriesMagnetIngestTaskResponse getSeriesTask(String taskId) {
        User user = authService.requireCurrentUser();
        return toSeriesResponse(getAccessibleSeriesTask(taskId, user));
    }

    public MovieMagnetIngestTaskLogListResponse getMovieTaskLogs(String taskId) {
        User user = authService.requireCurrentUser();
        getAccessibleMovieTask(taskId, user);
        List<MovieMagnetIngestTaskLogResponse> items = movieTaskLogMapper.selectList(new LambdaQueryWrapper<MovieMagnetIngestTaskLog>()
                        .eq(MovieMagnetIngestTaskLog::getTaskId, taskId)
                        .orderByAsc(MovieMagnetIngestTaskLog::getId))
                .stream()
                .map(this::toMovieLogResponse)
                .toList();
        return new MovieMagnetIngestTaskLogListResponse(items, items.size());
    }

    public SeriesMagnetIngestTaskLogListResponse getSeriesTaskLogs(String taskId) {
        User user = authService.requireCurrentUser();
        getAccessibleSeriesTask(taskId, user);
        List<SeriesMagnetIngestTaskLogResponse> items = seriesTaskLogMapper.selectList(new LambdaQueryWrapper<SeriesMagnetIngestTaskLog>()
                        .eq(SeriesMagnetIngestTaskLog::getTaskId, taskId)
                        .orderByAsc(SeriesMagnetIngestTaskLog::getId))
                .stream()
                .map(this::toSeriesLogResponse)
                .toList();
        return new SeriesMagnetIngestTaskLogListResponse(items, items.size());
    }

    private void runMovieTask(String taskId) {
        try {
            MovieMagnetIngestTask task = getExistingMovieTask(taskId);
            prepareMovieDownloadRoot(task);

            markMovieSubmitted(task);
            String openListTaskId = openListClient.addOfflineDownload(task.getTempPath(), task.getMagnet());
            markMovieDownloading(taskId, openListTaskId);

            waitForOfflineTask(
                    taskId,
                    openListTaskId,
                    task.getTempPath(),
                    false,
                    (level, stage, message, detail) -> writeMovieLog(taskId, level, stage, message, detail),
                    () -> refreshMovieTaskUpdatedAt(taskId)
            );

            markMovieOrganizing(taskId, openListTaskId, task.getTempPath());
            OrganizeResult result = organizeMovieFiles(getExistingMovieTask(taskId));
            if (result.videoCount() < 1) {
                markMovieNoOrganizedFiles(taskId, openListTaskId, result);
                return;
            }

            refreshMovieAutoSymlink(taskId);
            markMovieSucceeded(taskId, openListTaskId, result);
        } catch (Exception exception) {
            log.warn("Movie magnet ingest task failed id={}", taskId, exception);
            markMovieFailed(taskId, safeMessage(exception));
        }
    }

    private void runSeriesTask(String taskId) {
        try {
            SeriesMagnetIngestTask task = getExistingSeriesTask(taskId);
            if (ANIME_PRODUCT_TYPE.equals(task.getTaskProductType())) {
                runAnimeSeasonSeriesTask(task);
            } else {
                runTelevisionSeriesTask(task);
            }
        } catch (Exception exception) {
            log.warn("Series magnet ingest task failed id={}", taskId, exception);
            markSeriesFailed(taskId, safeMessage(exception));
        }
    }

    private void runTelevisionSeriesTask(SeriesMagnetIngestTask task) {
        prepareTelevisionSeriesDownloadRoot(task);
        executePreparedSeriesTask(task);
    }

    private void runAnimeSeasonSeriesTask(SeriesMagnetIngestTask task) {
        prepareAnimeSeasonDownloadRoot(task);
        executePreparedSeriesTask(task);
    }

    private void executePreparedSeriesTask(SeriesMagnetIngestTask task) {
        String taskId = task.getId();
        markSeriesSubmitted(task);
        String openListTaskId = openListClient.addOfflineDownload(task.getTempPath(), task.getMagnet());
        markSeriesDownloading(taskId, openListTaskId);

        waitForOfflineTask(
                taskId,
                openListTaskId,
                task.getTempPath(),
                ANIME_PRODUCT_TYPE.equals(task.getTaskProductType()),
                (level, stage, message, detail) -> writeSeriesLog(taskId, level, stage, message, detail),
                () -> refreshSeriesTaskUpdatedAt(taskId)
        );

        markSeriesOrganizing(taskId, openListTaskId, task.getTempPath());
        OrganizeResult result = organizeSeriesFiles(getExistingSeriesTask(taskId));
        if (result.videoCount() < 1) {
            markSeriesNoOrganizedFiles(taskId, openListTaskId, result);
            return;
        }

        if (ANIME_PRODUCT_TYPE.equals(task.getTaskProductType())) {
            refreshAnimeAutoSymlink(taskId);
        } else {
            refreshSeriesAutoSymlink(taskId);
        }
        markSeriesSucceeded(taskId, openListTaskId, result);
    }

    private void prepareMovieDownloadRoot(MovieMagnetIngestTask task) {
        writeMovieLog(task.getId(), "INFO", "created", "正在准备 OpenList 保存目录", task.getSavePath());
        try {
            openListClient.ensureDirectoryReady(task.getSavePath(), configuredRootPath(
                    openListProperties.getMovieRootPath(),
                    "OpenList 电影基础路径尚未配置"
            ));
        } catch (OpenListDirectoryPrepareException exception) {
            throw mapDirectoryPrepareException(exception, "OpenList 电影基础路径不存在");
        }
        writeMovieLog(task.getId(), "INFO", "created", "OpenList 保存目录准备完成", task.getSavePath());
    }

    private void prepareTelevisionSeriesDownloadRoot(SeriesMagnetIngestTask task) {
        writeSeriesLog(task.getId(), "INFO", "created", "正在准备 OpenList 保存目录", task.getSavePath());
        try {
            openListClient.ensureDirectoryReady(task.getSavePath(), configuredRootPath(
                    openListProperties.getTvRootPath(),
                    "OpenList 剧集基础路径尚未配置"
            ));
        } catch (OpenListDirectoryPrepareException exception) {
            throw mapDirectoryPrepareException(exception, "OpenList 剧集基础路径不存在");
        }
        writeSeriesLog(task.getId(), "INFO", "created", "OpenList 保存目录准备完成", task.getSavePath());
    }

    private void prepareAnimeSeasonDownloadRoot(SeriesMagnetIngestTask task) {
        writeSeriesLog(task.getId(), "INFO", "created", "正在准备 OpenList 动漫保存目录", task.getSavePath());
        openListClient.ensureDirectoryHierarchy(task.getSavePath());
        writeSeriesLog(task.getId(), "INFO", "created", "OpenList 动漫保存目录准备完成", task.getSavePath());
    }

    private void markMovieSubmitted(MovieMagnetIngestTask task) {
        updateMovieTask(task.getId(), "SUBMITTED", "submitted", null, null, null);
        writeMovieLog(task.getId(), "INFO", "submitted", "正在提交 OpenList 离线下载", task.getTempPath());
    }

    private void markSeriesSubmitted(SeriesMagnetIngestTask task) {
        updateSeriesTask(task.getId(), "SUBMITTED", "submitted", null, null, null);
        writeSeriesLog(task.getId(), "INFO", "submitted", "正在提交 OpenList 离线下载", task.getTempPath());
    }

    private void markMovieDownloading(String taskId, String openListTaskId) {
        updateMovieTask(taskId, "DOWNLOADING", "downloading", openListTaskId, null, null);
        writeMovieLog(taskId, "INFO", "downloading", "OpenList 离线任务已创建", openListTaskId);
    }

    private void markSeriesDownloading(String taskId, String openListTaskId) {
        updateSeriesTask(taskId, "DOWNLOADING", "downloading", openListTaskId, null, null);
        writeSeriesLog(taskId, "INFO", "downloading", "OpenList 离线任务已创建", openListTaskId);
    }

    private void markMovieOrganizing(String taskId, String openListTaskId, String tempPath) {
        updateMovieTask(taskId, "ORGANIZING", "organizing", openListTaskId, null, null);
        writeMovieLog(taskId, "INFO", "organizing", "离线下载完成，开始整理文件", tempPath);
    }

    private void markSeriesOrganizing(String taskId, String openListTaskId, String tempPath) {
        updateSeriesTask(taskId, "ORGANIZING", "organizing", openListTaskId, null, null);
        writeSeriesLog(taskId, "INFO", "organizing", "离线下载完成，开始整理文件", tempPath);
    }

    private void markMovieNoOrganizedFiles(String taskId, String openListTaskId, OrganizeResult result) {
        writeMovieLog(taskId, "ERROR", "failed", "没有识别到可入库的视频文件", null);
        updateMovieTask(taskId, "FAILED", "failed", openListTaskId, result, "没有识别到可入库的视频文件");
    }

    private void markSeriesNoOrganizedFiles(String taskId, String openListTaskId, OrganizeResult result) {
        writeSeriesLog(taskId, "ERROR", "failed", "没有识别到可入库的视频文件", null);
        updateSeriesTask(taskId, "FAILED", "failed", openListTaskId, result, "没有识别到可入库的视频文件");
    }

    private void markMovieSucceeded(String taskId, String openListTaskId, OrganizeResult result) {
        writeMovieLog(
                taskId,
                "INFO",
                "succeeded",
                "任务完成",
                "organized=" + result.organizedCount() + ", skipped=" + result.skippedCount()
        );
        updateMovieTask(taskId, "SUCCEEDED", "succeeded", openListTaskId, result, null);
        log.info("Movie magnet ingest task succeeded taskId={} openListTaskId={} organized={} skipped={}",
                taskId, openListTaskId, result.organizedCount(), result.skippedCount());
    }

    private void markSeriesSucceeded(String taskId, String openListTaskId, OrganizeResult result) {
        writeSeriesLog(
                taskId,
                "INFO",
                "succeeded",
                "任务完成",
                "organized=" + result.organizedCount() + ", skipped=" + result.skippedCount()
        );
        updateSeriesTask(taskId, "SUCCEEDED", "succeeded", openListTaskId, result, null);
        log.info("Series magnet ingest task succeeded taskId={} openListTaskId={} organized={} skipped={}",
                taskId, openListTaskId, result.organizedCount(), result.skippedCount());
    }

    private void refreshMovieAutoSymlink(String taskId) {
        refreshAutoSymlink(
                taskId,
                "Movie",
                autoSymlinkRefreshService::refreshMovie,
                (level, message, detail) -> writeMovieLog(taskId, level, "succeeded", message, detail)
        );
    }

    private void refreshSeriesAutoSymlink(String taskId) {
        refreshAutoSymlink(
                taskId,
                "Series",
                autoSymlinkRefreshService::refreshSeries,
                (level, message, detail) -> writeSeriesLog(taskId, level, "succeeded", message, detail)
        );
    }

    private void refreshAnimeAutoSymlink(String taskId) {
        refreshAutoSymlink(
                taskId,
                "Anime",
                autoSymlinkRefreshService::refreshAnime,
                (level, message, detail) -> writeSeriesLog(taskId, level, "succeeded", message, detail)
        );
    }

    private void refreshAutoSymlink(
            String taskId,
            String mediaType,
            AutoSymlinkRefreshOperation refreshOperation,
            AutoSymlinkTaskLogWriter taskLogWriter
    ) {
        try {
            taskLogWriter.write("INFO", "正在触发 AutoSymlink 刷新", null);
            AutoSymlinkRefreshService.RefreshOutcome outcome = refreshOperation.refresh();
            String level = outcome.status() == AutoSymlinkRefreshService.Status.SUBMITTED ? "INFO" : "WARN";
            taskLogWriter.write(level, outcome.message(), outcome.detail());
        } catch (Exception exception) {
            log.warn("{} AutoSymlink refresh logging failed taskId={}", mediaType, taskId, exception);
            try {
                taskLogWriter.write("WARN", "AutoSymlink 刷新任务提交失败，已跳过", null);
            } catch (Exception logException) {
                log.warn("{} AutoSymlink refresh task log write failed taskId={}", mediaType, taskId, logException);
            }
        }
    }

    private interface AutoSymlinkRefreshOperation {
        AutoSymlinkRefreshService.RefreshOutcome refresh();
    }

    private interface AutoSymlinkTaskLogWriter {
        void write(String level, String message, String detail);
    }

    private void markMovieFailed(String taskId, String errorMessage) {
        writeMovieLog(taskId, "ERROR", "failed", "任务失败", errorMessage);
        updateMovieTask(taskId, "FAILED", "failed", null, null, errorMessage);
    }

    private void markSeriesFailed(String taskId, String errorMessage) {
        writeSeriesLog(taskId, "ERROR", "failed", "任务失败", errorMessage);
        updateSeriesTask(taskId, "FAILED", "failed", null, null, errorMessage);
    }

    private void waitForOfflineTask(
            String taskId,
            String openListTaskId,
            String tempPath,
            boolean animePrimaryLayerOnly,
            TaskLogWriter taskLogWriter,
            Runnable refreshTaskUpdatedAt
    ) {
        Instant startedAt = Instant.now();
        int retryCount = 0;
        Duration timeout = openListProperties.getOfflineTimeout();
        Duration pollInterval = openListProperties.getPollInterval();

        while (Duration.between(startedAt, Instant.now()).compareTo(timeout) < 0) {
            OpenListOfflineTaskInfo taskInfo = openListClient.offlineTaskInfo(openListTaskId);
            Integer state = taskInfo.state();
            if (state != null && state == 2) {
                taskLogWriter.write("INFO", "downloading", "OpenList 离线下载完成", openListTaskId);
                safeDeleteOpenListTask(taskLogWriter, openListTaskId);
                return;
            }
            if (state != null && state == 1
                    && Duration.between(startedAt, Instant.now()).compareTo(SAVING_FILES_VISIBLE_GRACE) >= 0
                    && hasVideoFiles(tempPath, animePrimaryLayerOnly)) {
                taskLogWriter.write("WARN", "downloading", "OpenList 仍显示保存中但临时目录已有视频文件，尝试继续整理", openListTaskId);
                return;
            }
            if (state != null && List.of(3, 4).contains(state)) {
                throw new OpenListClientException("OpenList 离线任务已取消");
            }
            if (state != null && state >= 5) {
                if (hasVideoFiles(tempPath, animePrimaryLayerOnly)) {
                    taskLogWriter.write("WARN", "downloading", "OpenList 状态异常但发现视频文件，尝试继续整理", taskInfo.error());
                    safeDeleteOpenListTask(taskLogWriter, openListTaskId);
                    return;
                }
                if (retryCount >= Math.max(0, openListProperties.getRetryLimit())) {
                    throw new OpenListClientException("OpenList 离线任务失败: " + taskInfo.error());
                }
                retryCount++;
                taskLogWriter.write(
                        "WARN",
                        "downloading",
                        "OpenList 离线任务失败，正在重试",
                        "retry=" + retryCount + ", error=" + taskInfo.error()
                );
                openListClient.retryOfflineTask(openListTaskId);
            } else {
                taskLogWriter.write(
                        "INFO",
                        "downloading",
                        offlineProgressMessage(taskInfo.progress()),
                        offlineProgressDetail(openListTaskId, taskInfo)
                );
                refreshTaskUpdatedAt.run();
            }
            sleep(pollInterval);
        }
        throw new OpenListClientException("OpenList 离线下载超时");
    }

    private OrganizeResult organizeMovieFiles(MovieMagnetIngestTask task) {
        TaskLogWriter taskLogWriter = (level, stage, message, detail) ->
                writeMovieLog(task.getId(), level, stage, message, detail);
        taskLogWriter.write("INFO", "organizing", "正在扫描临时目录并生成整理计划", task.getTempPath());
        List<OpenListFileInfo> files = openListClient.findFiles(task.getTempPath());
        OrganizationPlan plan = new OrganizationPlan();
        MovieVideoSelection videoSelection = selectMovieVideos(taskLogWriter, plan, files, task);
        Set<String> targetNames = targetNames(task.getSavePath());
        Map<String, String> videoQualityByBaseName = new HashMap<>();
        String mainVideoQuality = "";
        int organized = 0;
        int skipped = videoSelection.skippedCount();
        int videoCount = 0;

        for (MovieVideoCandidate candidate : videoSelection.videos()) {
            OpenListFileInfo file = candidate.file();
            MovieSeriesFileRenameService.RenameResult rename = candidate.rename();
            boolean accepted = planFile(taskLogWriter, task.getSavePath(), targetNames, plan, file, rename.fileName());
            if (accepted) {
                organized++;
                videoCount++;
                if (videoCount == 1) {
                    mainVideoQuality = rename.quality();
                }
                videoQualityByBaseName.put(rename.quality(), rename.baseName());
            } else {
                skipped++;
            }
        }

        for (OpenListFileInfo file : files) {
            if (renameService.isVideo(file.name())) {
                continue;
            }
            if (!renameService.isSubtitle(file.name())) {
                skipped++;
                skipFile(taskLogWriter, plan, file, "跳过无法识别的文件");
                continue;
            }
            if (videoCount < 1) {
                skipped++;
                skipFile(taskLogWriter, plan, file, "跳过无法匹配视频的字幕");
                continue;
            }

            String sourceQuality = renameService.qualityFull(file.name());
            String matchedQuality = videoQualityByBaseName.containsKey(sourceQuality) ? sourceQuality : mainVideoQuality;
            Optional<MovieSeriesFileRenameService.RenameResult> rename = renameService.movieSubtitle(
                    file.name(),
                    fileTitle(task.getTitle(), task.getOriginalTitle(), "电影标题不能为空"),
                    task.getYear(),
                    matchedQuality
            );
            if (rename.isEmpty()) {
                skipped++;
                skipFile(taskLogWriter, plan, file, "跳过无法识别的字幕");
                continue;
            }
            if (planFile(taskLogWriter, task.getSavePath(), targetNames, plan, file, rename.get().fileName())) {
                organized++;
            } else {
                skipped++;
            }
        }

        executeOrganizationPlan(movieLibraryOrganizer, taskLogWriter, task.getSavePath(), plan, organized, skipped, Set.of());
        return new OrganizeResult(organized, skipped, videoCount);
    }

    private MovieVideoSelection selectMovieVideos(
            TaskLogWriter taskLogWriter,
            OrganizationPlan plan,
            List<OpenListFileInfo> files,
            MovieMagnetIngestTask task
    ) {
        Map<String, MovieVideoCandidate> videoByBaseName = new HashMap<>();
        int skipped = 0;
        String title = fileTitle(task.getTitle(), task.getOriginalTitle(), "电影标题不能为空");

        for (OpenListFileInfo file : files) {
            if (!renameService.isVideo(file.name())) {
                continue;
            }
            if (file.size() == null) {
                throw new OpenListClientException(
                        "OpenList 未返回电影视频文件大小: " + file.path() + "/" + file.name()
                );
            }
            if (file.size() < MIN_MOVIE_VIDEO_BYTES) {
                skipped++;
                skipFile(
                        taskLogWriter,
                        plan,
                        file,
                        "跳过小于 100 MiB 的视频文件（size=" + file.size() + "）"
                );
                continue;
            }

            MovieSeriesFileRenameService.RenameResult rename = renameService.movieVideo(
                    file.name(),
                    title,
                    task.getYear()
            );
            MovieVideoCandidate candidate = new MovieVideoCandidate(file, rename);
            MovieVideoCandidate retained = videoByBaseName.get(rename.baseName());
            if (retained == null) {
                videoByBaseName.put(rename.baseName(), candidate);
                continue;
            }

            skipped++;
            if (compareMovieVideoCandidates(candidate, retained) > 0) {
                skipFile(taskLogWriter, plan, retained.file(), "跳过同一电影版本的较小重复视频");
                videoByBaseName.put(rename.baseName(), candidate);
            } else {
                skipFile(taskLogWriter, plan, file, "跳过同一电影版本的较小重复视频");
            }
        }

        List<MovieVideoCandidate> videos = videoByBaseName.values().stream()
                .sorted(Comparator
                        .comparingLong((MovieVideoCandidate candidate) -> candidate.file().size())
                        .reversed()
                        .thenComparing(candidate -> candidate.file().name(), String.CASE_INSENSITIVE_ORDER))
                .toList();
        return new MovieVideoSelection(videos, skipped);
    }

    private int compareMovieVideoCandidates(MovieVideoCandidate left, MovieVideoCandidate right) {
        int sizeComparison = Long.compare(left.file().size(), right.file().size());
        if (sizeComparison != 0) {
            return sizeComparison;
        }
        return String.CASE_INSENSITIVE_ORDER.compare(left.file().name(), right.file().name());
    }

    private OrganizeResult organizeSeriesFiles(SeriesMagnetIngestTask task) {
        TaskLogWriter taskLogWriter = (level, stage, message, detail) ->
                writeSeriesLog(task.getId(), level, stage, message, detail);
        taskLogWriter.write("INFO", "organizing", "正在扫描临时目录并生成整理计划", task.getTempPath());
        AnimeSeriesOrganizeSelection selection = ANIME_PRODUCT_TYPE.equals(task.getTaskProductType())
                ? selectAnimeSeriesOrganizeFiles(taskLogWriter, task)
                : AnimeSeriesOrganizeSelection.passthrough(openListClient.findFiles(task.getTempPath()));
        List<OpenListFileInfo> files = selection.files();
        Set<String> targetNames = targetNames(task.getSavePath());
        OrganizationPlan plan = new OrganizationPlan(selection.contentToDeleteByDir());
        Set<String> episodeQualityKeys = new HashSet<>();
        Map<Integer, String> mainQualityByEpisode = new HashMap<>();
        int organized = 0;
        int skipped = 0;
        int videoCount = 0;

        for (OpenListFileInfo file : files) {
            if (!renameService.isVideo(file.name())) {
                continue;
            }
            Optional<MovieSeriesFileRenameService.RenameResult> rename = renameService.seriesVideo(
                    file.name(),
                    seriesFileTitle(task),
                    task.getSeasonNumber()
            );
            if (rename.isEmpty()) {
                skipped++;
                skipFile(taskLogWriter, plan, file, "跳过无法识别集数的视频");
                continue;
            }
            MovieSeriesFileRenameService.RenameResult result = rename.get();
            if (planFile(taskLogWriter, task.getSavePath(), targetNames, plan, file, result.fileName())) {
                organized++;
                videoCount++;
                episodeQualityKeys.add(episodeQualityKey(result.episodeNumber(), result.quality()));
                mainQualityByEpisode.putIfAbsent(result.episodeNumber(), result.quality());
            } else {
                skipped++;
            }
        }

        for (OpenListFileInfo file : files) {
            if (renameService.isVideo(file.name())) {
                continue;
            }
            if (!renameService.isSubtitle(file.name())) {
                skipped++;
                skipFile(taskLogWriter, plan, file, "跳过无法识别的文件");
                continue;
            }
            String sourceQuality = renameService.qualityFull(file.name());
            Optional<MovieSeriesFileRenameService.RenameResult> detected = renameService.seriesSubtitle(
                    file.name(),
                    seriesFileTitle(task),
                    task.getSeasonNumber(),
                    sourceQuality
            );
            if (detected.isEmpty()) {
                skipped++;
                skipFile(taskLogWriter, plan, file, "跳过无法识别集数的字幕");
                continue;
            }
            Integer episodeNumber = detected.get().episodeNumber();
            String matchedQuality = episodeQualityKeys.contains(episodeQualityKey(episodeNumber, sourceQuality))
                    ? sourceQuality
                    : mainQualityByEpisode.get(episodeNumber);
            if (matchedQuality == null) {
                skipped++;
                skipFile(taskLogWriter, plan, file, "跳过无法匹配视频的字幕");
                continue;
            }
            MovieSeriesFileRenameService.RenameResult result = renameService.seriesSubtitle(
                    file.name(),
                    seriesFileTitle(task),
                    task.getSeasonNumber(),
                    matchedQuality
            ).orElseThrow();
            if (planFile(taskLogWriter, task.getSavePath(), targetNames, plan, file, result.fileName())) {
                organized++;
            } else {
                skipped++;
            }
        }

        if (ANIME_PRODUCT_TYPE.equals(task.getTaskProductType())) {
            executeOrganizationPlan(
                    animeLibraryOrganizer,
                    taskLogWriter,
                    task.getSavePath(),
                    plan,
                    organized,
                    skipped,
                    selection.skippedDirectoryPaths()
            );
        } else {
            executeOrganizationPlan(
                    seriesLibraryOrganizer,
                    taskLogWriter,
                    task.getSavePath(),
                    plan,
                    organized,
                    skipped,
                    Set.of()
            );
        }
        return new OrganizeResult(organized, skipped, videoCount);
    }

    private AnimeSeriesOrganizeSelection selectAnimeSeriesOrganizeFiles(
            TaskLogWriter taskLogWriter,
            SeriesMagnetIngestTask task
    ) {
        String rootPath = openListClient.normalizePath(task.getTempPath());
        List<OpenListFileInfo> rootChildren = openListClient.listFiles(rootPath);
        List<OpenListFileInfo> rootFiles = directFiles(rootChildren);
        List<OpenListFileInfo> rootDirectories = directDirectories(rootChildren);
        Map<String, List<String>> contentToDeleteByDir = new LinkedHashMap<>();
        Set<String> skippedDirectoryPaths = new LinkedHashSet<>();

        if (hasRecognizableAnimeSeriesVideo(rootFiles, task)) {
            addAnimeDirectoriesToDelete(taskLogWriter, contentToDeleteByDir, skippedDirectoryPaths, rootPath, rootDirectories);
            return new AnimeSeriesOrganizeSelection(rootFiles, contentToDeleteByDir, skippedDirectoryPaths);
        }

        List<OpenListFileInfo> knownAuxiliaryDirectories = rootDirectories.stream()
                .filter(directory -> isKnownAnimeAuxiliaryDirectory(directory.name()))
                .toList();
        addAnimeDirectoriesToDelete(
                taskLogWriter,
                contentToDeleteByDir,
                skippedDirectoryPaths,
                rootPath,
                knownAuxiliaryDirectories
        );

        List<OpenListFileInfo> selectedFiles = new ArrayList<>();
        List<String> rootLooseFiles = rootFiles.stream().map(OpenListFileInfo::name).toList();
        List<OpenListFileInfo> rootDirectoriesWithoutMedia = new ArrayList<>();
        for (OpenListFileInfo directory : rootDirectories) {
            if (isKnownAnimeAuxiliaryDirectory(directory.name())) {
                continue;
            }
            String directoryPath = openListClient.joinPath(rootPath, directory.name());
            List<OpenListFileInfo> children = openListClient.listFiles(directoryPath);
            List<OpenListFileInfo> files = directFiles(children);
            List<OpenListFileInfo> directories = directDirectories(children);
            if (hasRecognizableAnimeSeriesVideo(files, task)) {
                selectedFiles.addAll(files);
                addAnimeDirectoriesToDelete(taskLogWriter, contentToDeleteByDir, skippedDirectoryPaths, directoryPath, directories);
            } else {
                rootDirectoriesWithoutMedia.add(directory);
            }
        }

        if (!selectedFiles.isEmpty()) {
            addAnimeContentToDelete(contentToDeleteByDir, rootPath, rootLooseFiles);
            addAnimeDirectoriesToDelete(
                    taskLogWriter,
                    contentToDeleteByDir,
                    skippedDirectoryPaths,
                    rootPath,
                    rootDirectoriesWithoutMedia
            );
        }
        return new AnimeSeriesOrganizeSelection(selectedFiles, contentToDeleteByDir, skippedDirectoryPaths);
    }

    private List<OpenListFileInfo> directFiles(List<OpenListFileInfo> children) {
        return children.stream()
                .filter(child -> !Boolean.TRUE.equals(child.isDir()))
                .toList();
    }

    private List<OpenListFileInfo> directDirectories(List<OpenListFileInfo> children) {
        return children.stream()
                .filter(child -> Boolean.TRUE.equals(child.isDir()))
                .toList();
    }

    private boolean hasRecognizableAnimeSeriesVideo(List<OpenListFileInfo> files, SeriesMagnetIngestTask task) {
        return files.stream().anyMatch(file -> renameService.isVideo(file.name())
                && renameService.seriesVideo(
                        file.name(),
                        seriesFileTitle(task),
                        task.getSeasonNumber()
                ).isPresent());
    }

    private String seriesFileTitle(SeriesMagnetIngestTask task) {
        if (ANIME_PRODUCT_TYPE.equals(task.getTaskProductType())) {
            return displayTitle(task.getTitle(), task.getOriginalTitle(), "动漫标题不能为空");
        }
        return fileTitle(task.getTitle(), task.getOriginalTitle(), "剧集标题不能为空");
    }

    private void addAnimeDirectoriesToDelete(
            TaskLogWriter taskLogWriter,
            Map<String, List<String>> contentToDeleteByDir,
            Set<String> skippedDirectoryPaths,
            String parentPath,
            List<OpenListFileInfo> directories
    ) {
        addAnimeContentToDelete(contentToDeleteByDir, parentPath, directories.stream().map(OpenListFileInfo::name).toList());
        for (OpenListFileInfo directory : directories) {
            String directoryPath = openListClient.normalizePath(openListClient.joinPath(parentPath, directory.name()));
            skippedDirectoryPaths.add(directoryPath);
            taskLogWriter.write("WARN", "organizing", "跳过辅助目录", directoryPath);
        }
    }

    private void addAnimeContentToDelete(Map<String, List<String>> contentToDeleteByDir, String sourceDir, List<String> names) {
        if (names.isEmpty()) {
            return;
        }
        contentToDeleteByDir.computeIfAbsent(sourceDir, ignored -> new ArrayList<>()).addAll(names);
    }

    private boolean isKnownAnimeAuxiliaryDirectory(String name) {
        String normalized = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        return KNOWN_ANIME_AUXILIARY_DIRECTORY_NAMES.contains(normalized);
    }

    private boolean planFile(
            TaskLogWriter taskLogWriter,
            String savePath,
            Set<String> targetNames,
            OrganizationPlan plan,
            OpenListFileInfo file,
            String targetName
    ) {
        String filePath = openListClient.normalizePath(file.path());
        String normalizedSavePath = openListClient.normalizePath(savePath);
        if (filePath.equals(normalizedSavePath) && file.name().equals(targetName)) {
            plan.plannedTargetNames().add(targetName);
            return true;
        }

        if (targetNames.contains(targetName) || plan.plannedTargetNames().contains(targetName)) {
            taskLogWriter.write("WARN", "organizing", "目标文件已存在或重复，删除当前文件", file.path() + "/" + file.name());
            plan.deleteByDir().computeIfAbsent(file.path(), ignored -> new ArrayList<>()).add(file.name());
            return false;
        }

        if (!file.name().equals(targetName)) {
            plan.renameByDir().computeIfAbsent(file.path(), ignored -> new HashMap<>()).put(file.name(), targetName);
        }
        if (!filePath.equals(normalizedSavePath)) {
            plan.moveByDir().computeIfAbsent(file.path(), ignored -> new ArrayList<>()).add(targetName);
        }
        plan.plannedTargetNames().add(targetName);
        return true;
    }

    private void skipFile(
            TaskLogWriter taskLogWriter,
            OrganizationPlan plan,
            OpenListFileInfo file,
            String message
    ) {
        taskLogWriter.write("WARN", "organizing", message, file.path() + "/" + file.name());
        plan.deleteByDir().computeIfAbsent(file.path(), ignored -> new ArrayList<>()).add(file.name());
    }

    private void executeOrganizationPlan(
            TaskLogWriter taskLogWriter,
            String savePath,
            OrganizationPlan plan,
            int organized,
            int skipped
    ) {
        executeOrganizationPlan(taskLogWriter, savePath, plan, organized, skipped, Set.of());
    }

    private void executeOrganizationPlan(
            TaskLogWriter taskLogWriter,
            String savePath,
            OrganizationPlan plan,
            int organized,
            int skipped,
            Set<String> skippedDirectoryPaths
    ) {
        int renameCount = countRenameOperations(plan.renameByDir());
        int moveCount = countFileOperations(plan.moveByDir());
        int deleteCount = countFileOperations(plan.deleteByDir());
        taskLogWriter.write(
                "INFO",
                "organizing",
                "整理计划已生成",
                "rename=" + renameCount + ", move=" + moveCount + ", delete=" + deleteCount
                        + ", organized=" + organized + ", skipped=" + skipped
        );

        for (Map.Entry<String, Map<String, String>> entry : plan.renameByDir().entrySet()) {
            List<Map<String, String>> batches = chunkMap(entry.getValue());
            for (int index = 0; index < batches.size(); index++) {
                Map<String, String> batch = batches.get(index);
                String detail = sourceBatchDetail(entry.getKey(), batch.size(), index + 1, batches.size());
                taskLogWriter.write("INFO", "organizing", "正在批量重命名文件", detail);
                openListClient.batchRename(entry.getKey(), batch);
                taskLogWriter.write("INFO", "organizing", "批量重命名完成", detail);
                for (Map.Entry<String, String> renameEntry : batch.entrySet()) {
                    taskLogWriter.write("INFO", "organizing", "重命名文件", renameEntry.getKey() + " ==> " + renameEntry.getValue());
                }
            }
        }
        for (Map.Entry<String, List<String>> entry : plan.moveByDir().entrySet()) {
            List<List<String>> batches = chunkList(entry.getValue());
            for (int index = 0; index < batches.size(); index++) {
                List<String> batch = batches.get(index);
                String detail = moveBatchDetail(entry.getKey(), savePath, batch.size(), index + 1, batches.size());
                taskLogWriter.write("INFO", "organizing", "正在批量移动文件到目标目录", detail);
                openListClient.move(entry.getKey(), savePath, batch);
                taskLogWriter.write("INFO", "organizing", "批量移动完成", detail);
                for (String name : batch) {
                    taskLogWriter.write("INFO", "organizing", "移动文件到目标目录", name);
                }
            }
        }
        for (Map.Entry<String, List<String>> entry : plan.deleteByDir().entrySet()) {
            List<List<String>> batches = chunkList(entry.getValue());
            for (int index = 0; index < batches.size(); index++) {
                List<String> batch = batches.get(index);
                String detail = sourceBatchDetail(entry.getKey(), batch.size(), index + 1, batches.size());
                taskLogWriter.write("INFO", "organizing", "正在删除跳过文件", detail);
                openListClient.remove(entry.getKey(), batch);
                taskLogWriter.write("INFO", "organizing", "跳过文件删除完成", detail);
                for (String name : batch) {
                    taskLogWriter.write("INFO", "organizing", "删除跳过文件", entry.getKey() + "/" + name);
                }
            }
        }
        String normalizedSavePath = openListClient.normalizePath(savePath);
        taskLogWriter.write("INFO", "organizing", "正在清理空目录", normalizedSavePath);
        cleanupEmptyDirectories(taskLogWriter, normalizedSavePath, normalizedSavePath, skippedDirectoryPaths);
        taskLogWriter.write("INFO", "organizing", "空目录清理完成", normalizedSavePath);
    }

    private void executeOrganizationPlan(
            LibraryOrganizer libraryOrganizer,
            TaskLogWriter taskLogWriter,
            String savePath,
            OrganizationPlan plan,
            int organized,
            int skipped,
            Set<String> cleanupExcludedDirectories
    ) {
        int renameCount = countRenameOperations(plan.renameByDir());
        int moveCount = countFileOperations(plan.moveByDir());
        int deleteCount = countFileOperations(plan.deleteByDir());
        taskLogWriter.write(
                "INFO",
                "organizing",
                "整理计划已生成",
                "rename=" + renameCount + ", move=" + moveCount + ", delete=" + deleteCount
                        + ", organized=" + organized + ", skipped=" + skipped
        );
        LibraryOrganizationPlan organizationPlan = LibraryOrganizationPlan.fromGroupedOperations(
                savePath,
                plan.renameByDir(),
                plan.moveByDir(),
                plan.deleteByDir(),
                plan.plannedTargetNames(),
                cleanupExcludedDirectories
        );
        libraryOrganizer.organize(
                organizationPlan,
                (message, detail) -> taskLogWriter.write("INFO", "organizing", message, detail)
        );
    }

    private void cleanupEmptyDirectories(
            TaskLogWriter taskLogWriter,
            String rootPath,
            String currentPath,
            Set<String> skippedDirectoryPaths
    ) {
        List<OpenListFileInfo> children;
        try {
            children = openListClient.listFiles(currentPath);
        } catch (RuntimeException exception) {
            taskLogWriter.write("WARN", "organizing", "扫描空目录失败，跳过清理", currentPath);
            return;
        }

        for (OpenListFileInfo child : children) {
            if (Boolean.TRUE.equals(child.isDir())) {
                String childPath = openListClient.normalizePath(openListClient.joinPath(currentPath, child.name()));
                if (skippedDirectoryPaths.contains(childPath)) {
                    continue;
                }
                cleanupEmptyDirectories(taskLogWriter, rootPath, childPath, skippedDirectoryPaths);
            }
        }

        if (openListClient.normalizePath(currentPath).equals(openListClient.normalizePath(rootPath))) {
            return;
        }

        try {
            if (openListClient.listFiles(currentPath).isEmpty()) {
                openListClient.remove(parentPath(currentPath), List.of(pathName(currentPath)));
                taskLogWriter.write("INFO", "organizing", "删除空目录", currentPath);
            }
        } catch (RuntimeException exception) {
            taskLogWriter.write("WARN", "organizing", "删除空目录失败", currentPath);
        }
    }

    private MovieTaskPlan buildMoviePlan(MovieMagnetIngestRequest request) {
        if (request == null) {
            throw badRequest("请求不能为空");
        }
        String magnet = normalizeMagnet(request.magnet());
        String magnetHash = extractMagnetHash(magnet);
        int year = validateMovieYear(request.year());
        String title = displayTitle(request.title(), request.originalTitle(), "电影标题不能为空");
        String fileTitle = fileTitle(request.title(), request.originalTitle(), "电影标题不能为空");
        String rootPath = configuredRootPath(openListProperties.getMovieRootPath(), "OpenList 电影基础路径尚未配置");
        String folderName = renameService.movieFolderName(fileTitle, year);
        String savePath = openListClient.joinPath(rootPath, folderName);
        return new MovieTaskPlan(
                magnet,
                magnetHash,
                title,
                trimToNull(request.originalTitle()),
                year,
                request.tmdbId(),
                rootPath,
                savePath
        );
    }

    private SeriesTaskPlan buildSeriesPlan(SeriesMagnetIngestRequest request) {
        if (request == null) {
            throw badRequest("请求不能为空");
        }
        String magnet = normalizeMagnet(request.magnet());
        String magnetHash = extractMagnetHash(magnet);
        int seasonNumber = validateSeasonNumber(request.seasonNumber());
        String title = displayTitle(request.title(), request.originalTitle(), "剧集标题不能为空");
        String fileTitle = fileTitle(request.title(), request.originalTitle(), "剧集标题不能为空");
        String rootPath = configuredRootPath(openListProperties.getTvRootPath(), "OpenList 剧集基础路径尚未配置");
        String seriesName = renameService.seriesFolderName(fileTitle);
        String seasonFolder = renameService.seasonFolderName(seasonNumber);
        String savePath = openListClient.joinPath(openListClient.joinPath(rootPath, seriesName), seasonFolder);
        return new SeriesTaskPlan(
                magnet,
                magnetHash,
                title,
                trimToNull(request.originalTitle()),
                seasonNumber,
                request.tmdbId(),
                seriesName,
                seasonFolder,
                savePath
        );
    }

    private SeriesTaskPlan buildAnimeSeasonSeriesPlan(SeriesMagnetIngestRequest request) {
        if (request == null) {
            throw badRequest("请求不能为空");
        }
        String magnet = normalizeMagnet(request.magnet());
        String magnetHash = extractMagnetHash(magnet);
        int seasonNumber = validateSeasonNumber(request.seasonNumber());
        String title = displayTitle(request.title(), request.originalTitle(), "动漫标题不能为空");
        String originalTitle = trimToNull(request.originalTitle());
        String seriesName = renameService.seriesFolderName(title);
        String seasonFolder = renameService.seasonFolderName(seasonNumber);
        String savePath = renderAnimeSeasonPath(title, seasonNumber);
        return new SeriesTaskPlan(
                magnet,
                magnetHash,
                title,
                originalTitle,
                seasonNumber,
                request.tmdbId(),
                seriesName,
                seasonFolder,
                savePath
        );
    }

    String renderAnimeSeasonPath(String title, int seasonNumber) {
        String template = cleanConfigValue(openListProperties.getAnimePathTemplate());
        if (!StringUtils.hasText(template)) {
            throw serviceUnavailable("OpenList 动漫保存路径尚未配置");
        }
        String folderTitle = sanitizePathSegment(title);
        String path = replaceTemplateValue(template, "themoviedbName", folderTitle);
        path = replaceTemplateValue(path, "title", folderTitle);
        path = replaceTemplateValue(path, "season", String.valueOf(seasonNumber));
        path = replaceTemplateValue(path, "seasonFormat", String.format("%02d", seasonNumber));
        return openListClient.normalizePath(path);
    }

    private String sanitizePathSegment(String value) {
        String trimmed = trimToNull(value);
        if (!StringUtils.hasText(trimmed)) {
            return "";
        }
        return trimmed.replaceAll("[\\\\/:*?\"<>|]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String replaceTemplateValue(String template, String key, String value) {
        return template
                .replace("{" + key + "}", value)
                .replace("${" + key + "}", value);
    }

    private String normalizeMagnet(String magnet) {
        String normalized = magnet == null ? "" : magnet.trim();
        if (!normalized.toLowerCase(Locale.ROOT).startsWith("magnet:?")) {
            throw badRequest("magnet 链接需以 magnet:? 开头");
        }
        return normalized;
    }

    private String extractMagnetHash(String magnet) {
        Matcher matcher = MAGNET_HASH_PATTERN.matcher(magnet);
        if (!matcher.find()) {
            throw badRequest("magnet 缺少 btih hash");
        }
        return matcher.group(1).toLowerCase(Locale.ROOT);
    }

    private int validateMovieYear(Integer year) {
        int maxYear = Year.now().getValue() + 2;
        if (year == null || year < FIRST_MOVIE_YEAR || year > maxYear) {
            throw badRequest("年份无效");
        }
        return year;
    }

    private int validateSeasonNumber(Integer seasonNumber) {
        if (seasonNumber == null || seasonNumber < 1) {
            throw badRequest("季数无效");
        }
        return seasonNumber;
    }

    private String displayTitle(String title, String originalTitle, String missingMessage) {
        String normalizedTitle = trimToNull(title);
        if (StringUtils.hasText(normalizedTitle)) {
            return normalizedTitle;
        }
        String normalizedOriginalTitle = trimToNull(originalTitle);
        if (StringUtils.hasText(normalizedOriginalTitle)) {
            return normalizedOriginalTitle;
        }
        throw badRequest(missingMessage);
    }

    private String fileTitle(String title, String originalTitle, String missingMessage) {
        String normalizedOriginalTitle = trimToNull(originalTitle);
        if (StringUtils.hasText(normalizedOriginalTitle)) {
            return normalizedOriginalTitle;
        }
        String normalizedTitle = trimToNull(title);
        if (StringUtils.hasText(normalizedTitle)) {
            return normalizedTitle;
        }
        throw badRequest(missingMessage);
    }

    private String configuredRootPath(String configuredPath, String missingMessage) {
        String cleanedPath = cleanConfigValue(configuredPath);
        if (!StringUtils.hasText(cleanedPath)) {
            throw serviceUnavailable(missingMessage);
        }
        return openListClient.normalizePath(cleanedPath);
    }

    private String cleanConfigValue(String value) {
        if (!StringUtils.hasText(value)) {
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

    private Set<String> targetNames(String savePath) {
        return openListClient.listFiles(savePath).stream()
                .map(OpenListFileInfo::name)
                .collect(HashSet::new, HashSet::add, HashSet::addAll);
    }

    private String offlineProgressMessage(Integer progress) {
        if (progress == null) {
            return "OpenList 离线下载进行中";
        }
        return "OpenList 离线下载进度 " + progress + "%";
    }

    private String offlineProgressDetail(String openListTaskId, OpenListOfflineTaskInfo taskInfo) {
        List<String> details = new ArrayList<>();
        if (taskInfo.progress() != null) {
            details.add("progress=" + taskInfo.progress());
        }
        if (StringUtils.hasText(taskInfo.status())) {
            details.add("status=" + taskInfo.status().trim());
        }
        if (taskInfo.totalBytes() != null && taskInfo.totalBytes() > 0) {
            details.add("totalBytes=" + taskInfo.totalBytes());
        }
        if (taskInfo.state() != null) {
            details.add("state=" + taskInfo.state());
        }
        if (StringUtils.hasText(taskInfo.error())) {
            details.add("error=" + taskInfo.error().trim());
        }
        details.add("openListTaskId=" + openListTaskId);
        return String.join(", ", details);
    }

    private boolean hasVideoFiles(String path, boolean animePrimaryLayerOnly) {
        if (!animePrimaryLayerOnly) {
            return openListClient.findFiles(path).stream().anyMatch(file -> renameService.isVideo(file.name()));
        }

        String rootPath = openListClient.normalizePath(path);
        List<OpenListFileInfo> rootChildren = openListClient.listFiles(rootPath);
        if (directFiles(rootChildren).stream().anyMatch(file -> renameService.isVideo(file.name()))) {
            return true;
        }

        for (OpenListFileInfo directory : directDirectories(rootChildren)) {
            if (isKnownAnimeAuxiliaryDirectory(directory.name())) {
                continue;
            }
            String directoryPath = openListClient.joinPath(rootPath, directory.name());
            boolean directoryHasVideo = directFiles(openListClient.listFiles(directoryPath)).stream()
                    .anyMatch(file -> renameService.isVideo(file.name()));
            if (directoryHasVideo) {
                return true;
            }
        }
        return false;
    }

    private void safeDeleteOpenListTask(TaskLogWriter taskLogWriter, String openListTaskId) {
        try {
            openListClient.deleteOfflineTask(openListTaskId);
        } catch (RuntimeException exception) {
            taskLogWriter.write("WARN", "downloading", "删除 OpenList 离线任务记录失败，继续后续整理", null);
        }
    }

    private int countRenameOperations(Map<String, Map<String, String>> renameByDir) {
        return renameByDir.values().stream()
                .mapToInt(Map::size)
                .sum();
    }

    private int countFileOperations(Map<String, List<String>> namesByDir) {
        return namesByDir.values().stream()
                .mapToInt(List::size)
                .sum();
    }

    private String sourceBatchDetail(String sourceDir, int count) {
        return "srcDir=" + sourceDir + ", count=" + count;
    }

    private String sourceBatchDetail(String sourceDir, int count, int batchNumber, int batchCount) {
        return sourceBatchDetail(sourceDir, count) + ", batch=" + batchNumber + "/" + batchCount;
    }

    private String moveBatchDetail(String sourceDir, String destinationDir, int count) {
        return "srcDir=" + sourceDir + ", dstDir=" + destinationDir + ", count=" + count;
    }

    private String moveBatchDetail(String sourceDir, String destinationDir, int count, int batchNumber, int batchCount) {
        return moveBatchDetail(sourceDir, destinationDir, count) + ", batch=" + batchNumber + "/" + batchCount;
    }

    private List<Map<String, String>> chunkMap(Map<String, String> values) {
        List<Map.Entry<String, String>> entries = new ArrayList<>(values.entrySet());
        List<Map<String, String>> chunks = new ArrayList<>();
        for (int start = 0; start < entries.size(); start += OPENLIST_ORGANIZE_BATCH_SIZE) {
            Map<String, String> chunk = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : entries.subList(
                    start,
                    Math.min(start + OPENLIST_ORGANIZE_BATCH_SIZE, entries.size())
            )) {
                chunk.put(entry.getKey(), entry.getValue());
            }
            chunks.add(chunk);
        }
        return chunks;
    }

    private List<List<String>> chunkList(List<String> values) {
        List<List<String>> chunks = new ArrayList<>();
        for (int start = 0; start < values.size(); start += OPENLIST_ORGANIZE_BATCH_SIZE) {
            chunks.add(new ArrayList<>(values.subList(
                    start,
                    Math.min(start + OPENLIST_ORGANIZE_BATCH_SIZE, values.size())
            )));
        }
        return chunks;
    }

    private String parentPath(String path) {
        String normalized = openListClient.normalizePath(path);
        int index = normalized.lastIndexOf('/');
        return index <= 0 ? "/" : normalized.substring(0, index);
    }

    private String pathName(String path) {
        String normalized = openListClient.normalizePath(path);
        int index = normalized.lastIndexOf('/');
        return index < 0 ? normalized : normalized.substring(index + 1);
    }

    private String episodeQualityKey(Integer episodeNumber, String quality) {
        return episodeNumber + "|" + (quality == null ? "" : quality);
    }

    private void updateMovieTask(
            String taskId,
            String status,
            String stage,
            String openListTaskId,
            OrganizeResult result,
            String errorMessage
    ) {
        LambdaUpdateWrapper<MovieMagnetIngestTask> updateWrapper = new LambdaUpdateWrapper<MovieMagnetIngestTask>()
                .eq(MovieMagnetIngestTask::getId, taskId)
                .set(MovieMagnetIngestTask::getStatus, status)
                .set(MovieMagnetIngestTask::getStage, stage);
        if (openListTaskId != null) {
            updateWrapper.set(MovieMagnetIngestTask::getOpenlistTaskId, openListTaskId);
        }
        if (result != null) {
            updateWrapper
                    .set(MovieMagnetIngestTask::getOrganizedCount, result.organizedCount())
                    .set(MovieMagnetIngestTask::getSkippedCount, result.skippedCount());
        }
        if (errorMessage != null) {
            updateWrapper.set(MovieMagnetIngestTask::getErrorMessage, truncate(errorMessage, 1000));
        }
        if (TERMINAL_STATUSES.contains(status)) {
            updateWrapper.set(MovieMagnetIngestTask::getFinishedAt, LocalDateTime.now());
        }
        movieTaskMapper.update(updateWrapper);
    }

    private void updateSeriesTask(
            String taskId,
            String status,
            String stage,
            String openListTaskId,
            OrganizeResult result,
            String errorMessage
    ) {
        LambdaUpdateWrapper<SeriesMagnetIngestTask> updateWrapper = new LambdaUpdateWrapper<SeriesMagnetIngestTask>()
                .eq(SeriesMagnetIngestTask::getId, taskId)
                .set(SeriesMagnetIngestTask::getStatus, status)
                .set(SeriesMagnetIngestTask::getStage, stage);
        if (openListTaskId != null) {
            updateWrapper.set(SeriesMagnetIngestTask::getOpenlistTaskId, openListTaskId);
        }
        if (result != null) {
            updateWrapper
                    .set(SeriesMagnetIngestTask::getOrganizedCount, result.organizedCount())
                    .set(SeriesMagnetIngestTask::getSkippedCount, result.skippedCount());
        }
        if (errorMessage != null) {
            updateWrapper.set(SeriesMagnetIngestTask::getErrorMessage, truncate(errorMessage, 1000));
        }
        if (TERMINAL_STATUSES.contains(status)) {
            updateWrapper.set(SeriesMagnetIngestTask::getFinishedAt, LocalDateTime.now());
        }
        seriesTaskMapper.update(updateWrapper);
    }

    private void removeMovieTaskAfterCreationFailure(String taskId, RuntimeException cause) {
        try {
            movieTaskLogMapper.delete(new LambdaQueryWrapper<MovieMagnetIngestTaskLog>()
                    .eq(MovieMagnetIngestTaskLog::getTaskId, taskId));
        } catch (RuntimeException cleanupException) {
            cause.addSuppressed(cleanupException);
        }
        try {
            movieTaskMapper.deleteById(taskId);
        } catch (RuntimeException cleanupException) {
            cause.addSuppressed(cleanupException);
        }
    }

    private void removeSeriesTaskAfterCreationFailure(String taskId, RuntimeException cause) {
        try {
            seriesTaskLogMapper.delete(new LambdaQueryWrapper<SeriesMagnetIngestTaskLog>()
                    .eq(SeriesMagnetIngestTaskLog::getTaskId, taskId));
        } catch (RuntimeException cleanupException) {
            cause.addSuppressed(cleanupException);
        }
        try {
            seriesTaskMapper.deleteById(taskId);
        } catch (RuntimeException cleanupException) {
            cause.addSuppressed(cleanupException);
        }
    }

    private void writeMovieLog(String taskId, String level, String stage, String message, String detail) {
        MovieMagnetIngestTaskLog taskLog = new MovieMagnetIngestTaskLog();
        taskLog.setTaskId(taskId);
        taskLog.setLevel(level);
        taskLog.setStage(stage);
        taskLog.setMessage(message);
        taskLog.setDetail(detail);
        movieTaskLogMapper.insert(taskLog);
    }

    private void writeSeriesLog(String taskId, String level, String stage, String message, String detail) {
        SeriesMagnetIngestTaskLog taskLog = new SeriesMagnetIngestTaskLog();
        taskLog.setTaskId(taskId);
        taskLog.setLevel(level);
        taskLog.setStage(stage);
        taskLog.setMessage(message);
        taskLog.setDetail(detail);
        seriesTaskLogMapper.insert(taskLog);
    }

    private void refreshMovieTaskUpdatedAt(String taskId) {
        movieTaskMapper.update(new LambdaUpdateWrapper<MovieMagnetIngestTask>()
                .eq(MovieMagnetIngestTask::getId, taskId)
                .set(MovieMagnetIngestTask::getUpdatedAt, LocalDateTime.now()));
    }

    private void refreshSeriesTaskUpdatedAt(String taskId) {
        seriesTaskMapper.update(new LambdaUpdateWrapper<SeriesMagnetIngestTask>()
                .eq(SeriesMagnetIngestTask::getId, taskId)
                .set(SeriesMagnetIngestTask::getUpdatedAt, LocalDateTime.now()));
    }

    private MovieMagnetIngestTask getExistingMovieTask(String taskId) {
        MovieMagnetIngestTask task = movieTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "任务不存在", HttpStatus.NOT_FOUND);
        }
        return task;
    }

    private SeriesMagnetIngestTask getExistingSeriesTask(String taskId) {
        SeriesMagnetIngestTask task = seriesTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "任务不存在", HttpStatus.NOT_FOUND);
        }
        return task;
    }

    private MovieMagnetIngestTask getAccessibleMovieTask(String taskId, User user) {
        MovieMagnetIngestTask task = getExistingMovieTask(taskId);
        if (!canAccessMovieTask(user, task)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "任务不存在", HttpStatus.NOT_FOUND);
        }
        return task;
    }

    private SeriesMagnetIngestTask getAccessibleSeriesTask(String taskId, User user) {
        SeriesMagnetIngestTask task = getExistingSeriesTask(taskId);
        if (!canAccessSeriesTask(user, task)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "任务不存在", HttpStatus.NOT_FOUND);
        }
        return task;
    }

    private boolean canAccessMovieTask(User user, MovieMagnetIngestTask task) {
        return isAdmin(user) || (task.getCreatedByUserId() != null && task.getCreatedByUserId().equals(user.getId()));
    }

    private boolean canAccessSeriesTask(User user, SeriesMagnetIngestTask task) {
        return isAdmin(user) || (task.getCreatedByUserId() != null && task.getCreatedByUserId().equals(user.getId()));
    }

    private boolean isAdmin(User user) {
        return user != null && ADMIN_ROLE.equalsIgnoreCase(user.getRole());
    }

    private MovieMagnetIngestTaskResponse toMovieResponse(MovieMagnetIngestTask task) {
        List<String> resolutionTags = deserializeTags(task.getResolutionTags());
        return new MovieMagnetIngestTaskResponse(
                task.getId(),
                task.getCreatedByUserId(),
                task.getStatus(),
                task.getStage(),
                task.getTitle(),
                task.getOriginalTitle(),
                task.getYear(),
                task.getSourceType() == null ? "MANUAL_MAGNET" : task.getSourceType(),
                task.getReleaseTitle(),
                task.getReleaseIndexer(),
                task.getReleaseSize(),
                resolutionTags,
                task.getQualityTag() != null
                        ? task.getQualityTag()
                        : resolutionTags.stream().findFirst().orElse(null),
                deserializeTags(task.getDynamicRangeTags()),
                task.getMagnetHash(),
                task.getSavePath(),
                task.getTempPath(),
                task.getOrganizedCount(),
                task.getSkippedCount(),
                task.getErrorMessage(),
                task.getCreatedAt(),
                task.getUpdatedAt(),
                task.getFinishedAt()
        );
    }

    private SeriesMagnetIngestTaskResponse toSeriesResponse(SeriesMagnetIngestTask task) {
        List<String> resolutionTags = deserializeTags(task.getResolutionTags());
        return new SeriesMagnetIngestTaskResponse(
                task.getId(),
                task.getCreatedByUserId(),
                task.getStatus(),
                task.getStage(),
                task.getTitle(),
                task.getOriginalTitle(),
                task.getSeasonNumber(),
                seriesTaskProductType(task),
                task.getSourceType() == null ? "MANUAL_MAGNET" : task.getSourceType(),
                task.getReleaseTitle(),
                task.getReleaseIndexer(),
                task.getReleaseSize(),
                resolutionTags,
                task.getQualityTag() != null
                        ? task.getQualityTag()
                        : resolutionTags.stream().findFirst().orElse(null),
                deserializeTags(task.getDynamicRangeTags()),
                task.getSeriesName(),
                task.getSeasonFolder(),
                task.getMagnetHash(),
                task.getSavePath(),
                task.getTempPath(),
                task.getOrganizedCount(),
                task.getSkippedCount(),
                task.getErrorMessage(),
                task.getCreatedAt(),
                task.getUpdatedAt(),
                task.getFinishedAt()
        );
    }

    private MovieMagnetIngestTaskLogResponse toMovieLogResponse(MovieMagnetIngestTaskLog taskLog) {
        return new MovieMagnetIngestTaskLogResponse(
                taskLog.getId(),
                taskLog.getTaskId(),
                taskLog.getLevel(),
                taskLog.getStage(),
                taskLog.getMessage(),
                taskLog.getDetail(),
                taskLog.getCreatedAt()
        );
    }

    private SeriesMagnetIngestTaskLogResponse toSeriesLogResponse(SeriesMagnetIngestTaskLog taskLog) {
        return new SeriesMagnetIngestTaskLogResponse(
                taskLog.getId(),
                taskLog.getTaskId(),
                taskLog.getLevel(),
                taskLog.getStage(),
                taskLog.getMessage(),
                taskLog.getDetail(),
                taskLog.getCreatedAt()
        );
    }

    private BusinessException mapDirectoryPrepareException(
            OpenListDirectoryPrepareException exception,
            String missingRootMessage
    ) {
        if (exception.getReason() == OpenListDirectoryPrepareException.Reason.ROOT_NOT_FOUND) {
            return serviceUnavailable(missingRootMessage);
        }
        if (exception.getReason() == OpenListDirectoryPrepareException.Reason.PATH_OUTSIDE_ROOT) {
            return internalError("OpenList 路径配置无效");
        }
        return new BusinessException(
                ErrorCode.INTERNAL_ERROR,
                "OpenList 目标目录创建失败",
                HttpStatus.BAD_GATEWAY
        );
    }

    private String seriesTaskProductType(SeriesMagnetIngestTask task) {
        return StringUtils.hasText(task.getTaskProductType()) ? task.getTaskProductType() : SERIES_PRODUCT_TYPE;
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

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private void applyReleaseMetadata(MovieMagnetIngestTask task, ReleaseIngestMetadata metadata) {
        List<String> resolutionTags = metadata.resolutionTags() == null ? List.of() : metadata.resolutionTags();
        task.setSourceType(trimToNull(metadata.sourceType()));
        task.setReleaseTitle(trimToNull(metadata.releaseTitle()));
        task.setReleaseIndexer(trimToNull(metadata.releaseIndexer()));
        task.setReleaseSize(metadata.releaseSize());
        task.setReleaseIndexerId(metadata.releaseIndexerId());
        task.setReleaseGuid(trimToNull(metadata.releaseGuid()));
        task.setResolutionTags(serializeTags(resolutionTags));
        task.setQualityTag(resolutionTags.stream().findFirst().map(this::trimToNull).orElse(null));
        task.setDynamicRangeTags(serializeTags(metadata.dynamicRangeTags()));
    }

    private void applyReleaseMetadata(SeriesMagnetIngestTask task, ReleaseIngestMetadata metadata) {
        List<String> resolutionTags = metadata.resolutionTags() == null ? List.of() : metadata.resolutionTags();
        task.setSourceType(trimToNull(metadata.sourceType()));
        task.setReleaseTitle(trimToNull(metadata.releaseTitle()));
        task.setReleaseIndexer(trimToNull(metadata.releaseIndexer()));
        task.setReleaseSize(metadata.releaseSize());
        task.setReleaseIndexerId(metadata.releaseIndexerId());
        task.setReleaseGuid(trimToNull(metadata.releaseGuid()));
        task.setResolutionTags(serializeTags(resolutionTags));
        task.setQualityTag(resolutionTags.stream().findFirst().map(this::trimToNull).orElse(null));
        task.setDynamicRangeTags(serializeTags(metadata.dynamicRangeTags()));
    }

    private String serializeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        List<String> cleanedTags = tags.stream()
                .map(this::trimToNull)
                .filter(tag -> StringUtils.hasText(tag))
                .distinct()
                .toList();
        return cleanedTags.isEmpty() ? null : String.join(",", cleanedTags);
    }

    private List<String> deserializeTags(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(this::trimToNull)
                .filter(tag -> StringUtils.hasText(tag))
                .toList();
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return StringUtils.hasText(message) ? truncate(message, 1000) : "任务执行失败";
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new OpenListClientException("任务线程被中断", exception);
        }
    }

    @FunctionalInterface
    private interface TaskLogWriter {
        void write(String level, String stage, String message, String detail);
    }

    private record MovieTaskPlan(
            String magnet,
            String magnetHash,
            String title,
            String originalTitle,
            Integer year,
            Integer tmdbId,
            String rootPath,
            String savePath
    ) {
    }

    private record SeriesTaskPlan(
            String magnet,
            String magnetHash,
            String title,
            String originalTitle,
            Integer seasonNumber,
            Integer tmdbId,
            String seriesName,
            String seasonFolder,
            String savePath
    ) {
    }

    private record OrganizationPlan(
            Map<String, Map<String, String>> renameByDir,
            Map<String, List<String>> moveByDir,
            Map<String, List<String>> deleteByDir,
            Set<String> plannedTargetNames
    ) {
        private OrganizationPlan() {
            this(new HashMap<>(), new HashMap<>(), new LinkedHashMap<>(), new HashSet<>());
        }

        private OrganizationPlan(Map<String, List<String>> deleteByDir) {
            this(new HashMap<>(), new HashMap<>(), new LinkedHashMap<>(deleteByDir), new HashSet<>());
        }
    }

    private record AnimeSeriesOrganizeSelection(
            List<OpenListFileInfo> files,
            Map<String, List<String>> contentToDeleteByDir,
            Set<String> skippedDirectoryPaths
    ) {
        private static AnimeSeriesOrganizeSelection passthrough(List<OpenListFileInfo> files) {
            return new AnimeSeriesOrganizeSelection(files, Map.of(), Set.of());
        }
    }

    private record MovieVideoCandidate(
            OpenListFileInfo file,
            MovieSeriesFileRenameService.RenameResult rename
    ) {
    }

    private record MovieVideoSelection(List<MovieVideoCandidate> videos, int skippedCount) {
    }

    private record OrganizeResult(int organizedCount, int skippedCount, int videoCount) {
    }

    private static class WorkerThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable);
            thread.setName("movie-series-magnet-ingest-worker");
            thread.setDaemon(true);
            return thread;
        }
    }
}
