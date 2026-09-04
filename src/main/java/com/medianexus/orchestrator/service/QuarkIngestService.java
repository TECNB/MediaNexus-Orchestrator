package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.config.QasProperties;
import com.medianexus.orchestrator.config.TmdbProperties;
import com.medianexus.orchestrator.dto.quark.request.MovieQuarkIngestRequest;
import com.medianexus.orchestrator.dto.quark.request.SeriesQuarkIngestRequest;
import com.medianexus.orchestrator.dto.quark.response.QuarkIngestPreviewResponse;
import com.medianexus.orchestrator.dto.quark.response.QuarkIngestPreviewTaskResponse;
import com.medianexus.orchestrator.dto.quark.response.QuarkIngestTaskResponse;
import com.medianexus.orchestrator.dto.quark.response.QuarkSharePreviewNodeResponse;
import com.medianexus.orchestrator.integration.qas.QasClient;
import com.medianexus.orchestrator.integration.qas.QasClientException;
import com.medianexus.orchestrator.integration.qas.QasCreatedTask;
import com.medianexus.orchestrator.integration.qas.QasExecutionObserver;
import com.medianexus.orchestrator.integration.qas.QasExistingTask;
import com.medianexus.orchestrator.integration.qas.QasShareInspectionException;
import com.medianexus.orchestrator.integration.qas.QasShareNode;
import com.medianexus.orchestrator.integration.qas.QasShareTree;
import com.medianexus.orchestrator.integration.qas.QasTaskCreateCommand;
import com.medianexus.orchestrator.integration.quark.QuarkDirectClient;
import com.medianexus.orchestrator.integration.smartstrm.SmartStrmWebhookClient;
import com.medianexus.orchestrator.integration.tmdb.TmdbClient;
import com.medianexus.orchestrator.integration.tmdb.TmdbClientException;
import com.medianexus.orchestrator.mapper.QuarkIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.QuarkIngestTaskMapper;
import com.medianexus.orchestrator.model.QuarkIngestTask;
import com.medianexus.orchestrator.model.QuarkIngestTaskLog;
import com.medianexus.orchestrator.model.User;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medianexus.orchestrator.dto.quark.response.QuarkIngestTaskListResponse;
import com.medianexus.orchestrator.dto.quark.response.QuarkIngestTaskLogListResponse;
import com.medianexus.orchestrator.dto.quark.response.QuarkIngestTaskLogResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.Year;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class QuarkIngestService {

    private static final Logger log = LoggerFactory.getLogger(QuarkIngestService.class);
    private static final int FIRST_MOVIE_YEAR = 1888;
    private static final String ADMIN_ROLE = "ADMIN";
    private static final Pattern QUARK_SHARE_PATH = Pattern.compile(
            "^/s/[A-Za-z0-9]+(?:/[A-Fa-f0-9]{32})?/?$"
    );

    private final QasClient qasClient;
    private final QuarkShareTreeService shareTreeService;
    private final QasProperties qasProperties;
    private final TmdbProperties tmdbProperties;
    private final MovieSeriesFileRenameService renameService;
    private final AuthService authService;
    private final QuarkIngestPlanner ingestPlanner;
    private final TmdbClient tmdbClient;
    private final QuarkIngestTaskMapper taskMapper;
    private final QuarkIngestTaskLogMapper taskLogMapper;
    private final QuarkDirectClient quarkDirectClient;
    private final SmartStrmWebhookClient smartStrmWebhookClient;
    private final QuarkTaskCenterRecorder taskCenterRecorder;
    private final ExecutorService directExecutor = Executors.newFixedThreadPool(2);

    @Autowired
    public QuarkIngestService(
            QasClient qasClient,
            QuarkShareTreeService shareTreeService,
            QasProperties qasProperties,
            TmdbProperties tmdbProperties,
            MovieSeriesFileRenameService renameService,
            AuthService authService,
            QuarkIngestPlanner ingestPlanner,
            TmdbClient tmdbClient,
            QuarkIngestTaskMapper taskMapper,
            QuarkIngestTaskLogMapper taskLogMapper,
            QuarkDirectClient quarkDirectClient,
            SmartStrmWebhookClient smartStrmWebhookClient,
            QuarkTaskCenterRecorder taskCenterRecorder
    ) {
        this.qasClient = qasClient;
        this.shareTreeService = shareTreeService;
        this.qasProperties = qasProperties;
        this.tmdbProperties = tmdbProperties;
        this.renameService = renameService;
        this.authService = authService;
        this.ingestPlanner = ingestPlanner;
        this.tmdbClient = tmdbClient;
        this.taskMapper = taskMapper;
        this.taskLogMapper = taskLogMapper;
        this.quarkDirectClient = quarkDirectClient;
        this.smartStrmWebhookClient = smartStrmWebhookClient;
        this.taskCenterRecorder = taskCenterRecorder;
    }

    /** Backward-compatible constructor used by focused unit tests. */
    public QuarkIngestService(
            QasClient qasClient,
            QuarkShareTreeService shareTreeService,
            QasProperties qasProperties,
            TmdbProperties tmdbProperties,
            MovieSeriesFileRenameService renameService,
            AuthService authService,
            QuarkIngestPlanner ingestPlanner,
            TmdbClient tmdbClient,
            QuarkIngestTaskMapper taskMapper,
            QuarkIngestTaskLogMapper taskLogMapper
    ) {
        this(qasClient, shareTreeService, qasProperties, tmdbProperties, renameService, authService,
                ingestPlanner, tmdbClient, taskMapper, taskLogMapper,
                new QuarkDirectClient(qasProperties, new com.fasterxml.jackson.databind.ObjectMapper()),
                new SmartStrmWebhookClient(qasProperties, new com.fasterxml.jackson.databind.ObjectMapper()),
                null);
    }

    public QuarkIngestTaskResponse ingestMovie(MovieQuarkIngestRequest request) {
        PreparedIngest prepared = prepareMovie(request, true);
        return createAndTrigger("MOVIE", requiredText(request.title(), "电影标题不能为空"), prepared.plan(),
                prepared.shareTree(), request.sourceType());
    }

    public QuarkIngestTaskResponse ingestSeries(SeriesQuarkIngestRequest request) {
        return ingestSeasonMedia(request, "SERIES", qasProperties.getTvRootPath());
    }

    public QuarkIngestTaskResponse ingestVariety(SeriesQuarkIngestRequest request) {
        return ingestSeasonMedia(request, "VARIETY", qasProperties.getVarietyRootPath());
    }

    public QuarkIngestPreviewResponse previewMovie(MovieQuarkIngestRequest request) {
        return preview("MOVIE", prepareMovie(request, false));
    }

    public QuarkIngestPreviewResponse previewSeries(SeriesQuarkIngestRequest request) {
        return preview("SERIES", prepareSeasonMedia(request, "SERIES", qasProperties.getTvRootPath(), false));
    }

    public QuarkIngestPreviewResponse previewVariety(SeriesQuarkIngestRequest request) {
        return preview("VARIETY", prepareSeasonMedia(request, "VARIETY", qasProperties.getVarietyRootPath(), false));
    }

    private QuarkIngestTaskResponse ingestSeasonMedia(
            SeriesQuarkIngestRequest request,
            String mediaType,
            String configuredRoot
    ) {
        PreparedIngest prepared = prepareSeasonMedia(request, mediaType, configuredRoot, true);
        return createAndTrigger(mediaType, requiredText(request.title(), "标题不能为空"), prepared.plan(),
                prepared.shareTree(), "MANUAL_QUARK");
    }

    private PreparedIngest prepareMovie(MovieQuarkIngestRequest request, boolean allowTimeoutFallback) {
        authService.requireCurrentUser();
        if (request == null) {
            throw badRequest("请求不能为空");
        }
        String shareUrl = validateShareUrl(request.shareUrl());
        String title = requiredText(request.title(), "电影标题不能为空");
        int year = validateMovieYear(request.year());
        String folderName = requiredText(renameService.movieFolderName(title, year), "电影目录名无效");
        String savePath = joinPath(configuredRoot(qasProperties.getMovieRootPath()), folderName);
        QasIngestPlan plan = new QasIngestPlan(
                List.of(new QasTaskPlan(folderName, shareUrl, savePath, "", "", null)),
                List.of()
        );
        try {
            QasShareTree tree = shareTreeService.inspectShare(shareUrl);
            try {
                plan = ingestPlanner.planMovie(folderName, savePath, tree);
            } catch (QuarkIngestPlanningException exception) {
                throw badRequest("无法安全规划 Quark 转存：" + exception.getMessage());
            }
            return new PreparedIngest(plan, tree);
        } catch (QasShareInspectionException exception) {
            if (allowTimeoutFallback && exception.isTimedOut() && !exception.isComplexStructureObserved()) {
                return new PreparedIngest(
                        new QasIngestPlan(plan.tasks(), List.of("QAS 分享预览超时，已继续创建电影任务")),
                        new QasShareTree(shareUrl, List.of())
                );
            }
            throw mapInspectionFailure(exception);
        }
    }

    private PreparedIngest prepareSeasonMedia(
            SeriesQuarkIngestRequest request,
            String mediaType,
            String configuredRoot,
            boolean allowTimeoutFallback
    ) {
        authService.requireCurrentUser();
        if (request == null) {
            throw badRequest("请求不能为空");
        }
        String shareUrl = validateShareUrl(request.shareUrl());
        String title = requiredText(request.title(), "标题不能为空");
        int seasonNumber = validateSeasonNumber(request.seasonNumber());
        String seriesName = requiredText(renameService.seriesFolderName(title), "媒体目录名无效");
        String seasonFolder = String.format(Locale.ROOT, "Season %02d", seasonNumber);
        String savePath = joinPath(joinPath(configuredRoot(configuredRoot), seriesName), seasonFolder);
        String taskName = seriesName + " S" + String.format(Locale.ROOT, "%02d", seasonNumber);
        QasIngestPlan plan;
        QasShareTree shareTree;
        try {
            shareTree = shareTreeService.inspectShare(shareUrl);
            plan = planSeasonMedia(
                    request,
                    mediaType,
                    seriesName,
                    seasonNumber,
                    savePath,
                    shareTree
            );
        } catch (QasShareInspectionException exception) {
            if (allowTimeoutFallback && exception.isTimedOut() && !exception.isComplexStructureObserved()) {
                plan = new QasIngestPlan(
                        List.of(new QasTaskPlan(taskName, shareUrl, savePath, "", "", null)),
                        List.of("QAS 分享预览超时，已使用空重命名规则继续创建任务")
                );
                shareTree = new QasShareTree(shareUrl, List.of());
            } else {
                throw mapInspectionFailure(exception);
            }
        }
        return new PreparedIngest(plan, shareTree);
    }

    private QuarkIngestPreviewResponse preview(String mediaType, PreparedIngest prepared) {
        ShareStats stats = shareStats(prepared.shareTree().entries(), 1);
        List<QuarkIngestPreviewTaskResponse> tasks = prepared.plan().tasks().stream()
                .map(task -> new QuarkIngestPreviewTaskResponse(
                        task.taskName(),
                        task.versionLabel(),
                        StringUtils.hasText(task.pattern()) && StringUtils.hasText(task.replace())
                ))
                .toList();
        return new QuarkIngestPreviewResponse(
                true,
                mediaType,
                prepared.plan().tasks().get(0).savePath(),
                prepared.plan().tasks().size(),
                stats.videoCount(),
                stats.subtitleCount(),
                stats.directoryCount(),
                stats.maxDepth(),
                prepared.shareTree().entries().stream().map(this::previewNode).toList(),
                tasks,
                prepared.plan().warnings(),
                prepared.plan().tasks().size() == 1
                        ? "分享结构检查完成，可以提交入库"
                        : "分享结构检查完成，将创建 " + prepared.plan().tasks().size() + " 个入库执行单元"
        );
    }

    private QuarkSharePreviewNodeResponse previewNode(QasShareNode node) {
        return new QuarkSharePreviewNodeResponse(
                node.name(),
                node.directory(),
                node.size(),
                node.children().stream().map(this::previewNode).toList()
        );
    }

    private ShareStats shareStats(List<QasShareNode> nodes, int depth) {
        int videos = 0;
        int subtitles = 0;
        int directories = 0;
        int maxDepth = nodes.isEmpty() ? 0 : depth;
        for (QasShareNode node : nodes) {
            if (node.directory()) {
                directories++;
                ShareStats children = shareStats(node.children(), depth + 1);
                videos += children.videoCount();
                subtitles += children.subtitleCount();
                directories += children.directoryCount();
                maxDepth = Math.max(maxDepth, children.maxDepth());
            } else if (isSubtitle(node.name())) {
                subtitles++;
            } else if (isVideo(node)) {
                videos++;
            }
        }
        return new ShareStats(videos, subtitles, directories, maxDepth);
    }

    private boolean isVideo(QasShareNode node) {
        String name = node.name().toLowerCase(Locale.ROOT);
        return "video".equalsIgnoreCase(node.category())
                || name.matches(".*\\.(mkv|mp4|avi|mov|wmv|flv|ts|m2ts|webm|rmvb)$");
    }

    private boolean isSubtitle(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).matches(".*\\.(srt|ass|ssa|vtt|sub)$");
    }

    private QasIngestPlan planSeasonMedia(
            SeriesQuarkIngestRequest request,
            String mediaType,
            String seriesName,
            int seasonNumber,
            String savePath,
            QasShareTree shareTree
    ) {
        try {
            return planWithAvailableAirDates(
                    mediaType, seriesName, seasonNumber, savePath, shareTree, Map.of()
            );
        } catch (QuarkIngestPlanningException exception) {
            if (exception.getReason() != QuarkIngestPlanningException.Reason.DATE_MAPPING_REQUIRED) {
                throw badRequest("无法安全规划 Quark 转存：" + exception.getMessage());
            }
        }

        if (request.tmdbId() == null || request.tmdbId() <= 0) {
            throw badRequest("日期型资源需要有效的 TMDB ID 才能推断年份或映射集号");
        }
        Map<LocalDate, List<Integer>> airDateEpisodes = loadAirDateEpisodes(request.tmdbId(), seasonNumber);
        try {
            return planWithAvailableAirDates(
                    mediaType, seriesName, seasonNumber, savePath, shareTree, airDateEpisodes
            );
        } catch (QuarkIngestPlanningException exception) {
            throw badRequest("无法安全规划 Quark 转存：" + exception.getMessage());
        }
    }

    private QasIngestPlan planWithAvailableAirDates(
            String mediaType,
            String seriesName,
            int seasonNumber,
            String savePath,
            QasShareTree shareTree,
            Map<LocalDate, List<Integer>> airDateEpisodes
    ) {
        if ("VARIETY".equals(mediaType)) {
            return ingestPlanner.planVariety(
                    seriesName, seasonNumber, savePath, shareTree, airDateEpisodes
            );
        }
        return ingestPlanner.planSeries(
                seriesName, seasonNumber, savePath, shareTree, airDateEpisodes
        );
    }

    private Map<LocalDate, List<Integer>> loadAirDateEpisodes(int tmdbId, int seasonNumber) {
        JsonNode details;
        try {
            details = tmdbClient.getTvSeasonDetails(
                    tmdbId,
                    seasonNumber,
                    cleanLanguage(tmdbProperties.getDefaultLanguage())
            );
        } catch (TmdbClientException exception) {
            throw new BusinessException(
                    ErrorCode.BAD_GATEWAY,
                    "TMDB 季度详情获取失败：" + safeMessage(exception.getMessage()),
                    HttpStatus.BAD_GATEWAY
            );
        }
        JsonNode episodes = details.path("episodes");
        if (!episodes.isArray()) {
            throw new BusinessException(
                    ErrorCode.BAD_GATEWAY,
                    "TMDB 季度详情缺少 episodes",
                    HttpStatus.BAD_GATEWAY
            );
        }
        Map<LocalDate, List<Integer>> result = new LinkedHashMap<>();
        for (JsonNode episode : episodes) {
            int episodeNumber = episode.path("episode_number").asInt(0);
            String airDate = episode.path("air_date").asText("");
            if (episodeNumber <= 0 || !StringUtils.hasText(airDate)) {
                continue;
            }
            try {
                LocalDate date = LocalDate.parse(airDate);
                result.computeIfAbsent(date, ignored -> new ArrayList<>()).add(episodeNumber);
            } catch (DateTimeParseException ignored) {
                // Invalid dates cannot safely participate in an episode mapping.
            }
        }
        result.replaceAll((date, values) -> values.stream().distinct().sorted().toList());
        return Map.copyOf(result);
    }

    private QuarkIngestTaskResponse createAndTrigger(
            String mediaType,
            String title,
            QasIngestPlan plan,
            QasShareTree shareTree,
            String sourceType
    ) {
        User user = authService.requireCurrentUser();
        QuarkIngestTask ingestTask = createIngestRecord(user, mediaType, title, plan, sourceType);
        List<String> childIds = taskCenterRecorder == null ? List.of() : taskCenterRecorder.recordInitialPlan(
                    ingestTask.getId(), mediaType, sourceType,
                    plan.tasks(), Set.of(), user.getId());
        boolean direct = StringUtils.hasText(qasProperties.getQuarkCookie())
                && StringUtils.hasText(qasProperties.getSmartstrmWebhook());
        if (direct) {
        writePlanLogs(ingestTask.getId(), mediaType, plan);
        writeLog(ingestTask.getId(), "INFO", "submitted", "一次性入库已提交", null);
        updateAcceptedIngestRecord(ingestTask.getId(), "STARTED", plan.tasks().size(), plan.tasks().size(),
                plan.tasks().size() + " 个执行单元已提交，正在直接调用夸克");
        directExecutor.submit(() -> executeDirectIngest(ingestTask.getId(), mediaType, plan, shareTree, childIds));
        return new QuarkIngestTaskResponse(
                ingestTask.getId(), "STARTED", mediaType,
                String.join(", ", plan.tasks().stream().map(QasTaskPlan::taskName).toList()),
                plan.tasks().get(0).savePath(), true, plan.tasks().size(), plan.tasks().size(),
                plan.warnings(), "一次性入库已开始执行"
        );
        }
        List<QasCreatedTask> createdTasks = new ArrayList<>();
        List<String> creationFailures = new ArrayList<>();
        QasClientException firstFailure = null;
        for (int taskIndex = 0; taskIndex < plan.tasks().size(); taskIndex++) {
            QasTaskPlan task = plan.tasks().get(taskIndex);
            String childId = childIdAt(childIds, taskIndex);
            try {
                createdTasks.add(qasClient.createTask(new QasTaskCreateCommand(
                        task.taskName(),
                        task.sourceUrl(),
                        task.savePath(),
                        task.pattern(),
                        task.replace()
                )));
                if (taskCenterRecorder != null) {
                    taskCenterRecorder.markChildStatus(ingestTask.getId(), childId, "SUBMITTED", null);
                }
                writeLog(ingestTask.getId(), "INFO", "creating", "一次性入库已提交", task.taskName());
            } catch (QasClientException exception) {
                if (firstFailure == null) {
                    firstFailure = exception;
                }
                creationFailures.add(task.taskName() + "：" + safeUpstreamMessage(exception));
                if (taskCenterRecorder != null) {
                    taskCenterRecorder.markChildStatus(
                            ingestTask.getId(), childId, "FAILED", safeUpstreamMessage(exception));
                }
                writeLog(ingestTask.getId(), "ERROR", "creating", "一次性入库提交失败",
                        task.taskName() + "：" + safeUpstreamMessage(exception));
            }
        }
        if (createdTasks.isEmpty()) {
            updateIngestRecord(ingestTask.getId(), "FAILED", "failed", false, 0,
                    plan.tasks().size(), "没有成功提交入库任务");
            throw mapCreateFailure(firstFailure == null
                    ? new QasClientException(QasClientException.Reason.UPSTREAM, "没有成功提交入库任务")
                    : firstFailure);
        }

        int plannedCount = plan.tasks().size();
        int createdCount = createdTasks.size();
        boolean partial = createdCount < plannedCount;
        writeLog(ingestTask.getId(), partial ? "WARN" : "INFO", "submitted",
                "已提交 " + createdCount + "/" + plannedCount + " 个一次性入库任务，正在请求立即执行", null);
        updateStage(ingestTask.getId(), "submitted");

        boolean triggered = false;
        String triggerFailure = null;
        try {
            qasClient.triggerTasksNow(createdTasks, executionObserver(ingestTask.getId()));
            triggered = true;
        } catch (QasClientException exception) {
            triggerFailure = safeUpstreamMessage(exception);
        }

        String status = partial ? "PARTIAL" : triggered ? "STARTED" : "SCHEDULED";
        List<String> warnings = new ArrayList<>(plan.warnings());
        if (!creationFailures.isEmpty()) {
            warnings.addAll(creationFailures);
        }
        if (triggerFailure != null) {
            warnings.add("立即执行失败：" + triggerFailure + "；系统将继续处理");
        }
        String message = resultMessage(createdCount, plannedCount, partial, triggered, creationFailures, triggerFailure);
        String taskNames = String.join(", ", createdTasks.stream().map(QasCreatedTask::taskName).toList());
        String savePath = plan.tasks().get(0).savePath();
        if (triggered) {
            updateAcceptedIngestRecord(
                    ingestTask.getId(), status, createdCount, plannedCount, message
            );
        } else {
            updateIngestRecord(
                    ingestTask.getId(), status, partial ? "partial" : "scheduled",
                    false, createdCount, plannedCount, message
            );
            writeLog(ingestTask.getId(), "WARN", partial ? "partial" : "scheduled", message, null);
        }
        log.info(
                "Created QAS ingest tasks mediaType={} createdCount={} plannedCount={} immediateStarted={}",
                mediaType,
                createdCount,
                plannedCount,
                triggered
        );
        return new QuarkIngestTaskResponse(
                ingestTask.getId(),
                status,
                mediaType,
                taskNames,
                savePath,
                triggered,
                createdCount,
                plannedCount,
                List.copyOf(warnings),
                message
        );
    }

    private void executeDirectIngest(
            String taskId,
            String mediaType,
            QasIngestPlan plan,
            QasShareTree shareTree,
            List<String> childIds
    ) {
        int completed = 0;
        try {
            for (int taskIndex = 0; taskIndex < plan.tasks().size(); taskIndex++) {
                QasTaskPlan task = plan.tasks().get(taskIndex);
                String childId = childIdAt(childIds, taskIndex);
                if (taskCenterRecorder != null) {
                    taskCenterRecorder.markChildStatus(taskId, childId, "PROCESSING", null);
                }
                writeLog(taskId, "INFO", "direct_transfer", "开始一次性入库", task.taskName());
                quarkDirectClient.transfer(task, shareTree,
                        message -> writeLog(taskId, "INFO", "direct_transfer", message, task.taskName()));
                writeLog(taskId, "INFO", "renaming", "入库完成，正在整理文件名称", task.taskName());
                smartStrmWebhookClient.trigger(task.savePath(), mediaType);
                writeLog(taskId, "INFO", "smartstrm", "后续媒体处理已触发", task.savePath());
                if (taskCenterRecorder != null) {
                    taskCenterRecorder.markChildStatus(taskId, childId, "SUCCEEDED", null);
                }
                completed++;
                updateIngestRecord(taskId, completed == plan.tasks().size() ? "COMPLETED" : "STARTED",
                        completed == plan.tasks().size() ? "completed" : "direct_transfer", true,
                        completed, plan.tasks().size(), "已完成 " + completed + "/" + plan.tasks().size() + " 个执行单元");
            }
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null ? "一次性入库失败" : exception.getMessage();
            if (taskCenterRecorder != null) {
                taskCenterRecorder.markAggregateChildren(taskId, "FAILED", message);
            }
            writeLog(taskId, completed > 0 ? "WARN" : "ERROR", "failed", message, null);
            updateIngestRecord(taskId, completed > 0 ? "PARTIAL" : "FAILED", "failed", true,
                    completed, plan.tasks().size(), message);
        }
    }

    @PreDestroy
    void shutdownDirectExecutor() {
        directExecutor.shutdownNow();
    }

    private void ensureNoDuplicateQasTasks(QasIngestPlan plan) {
        List<QasExistingTask> existing;
        try {
            existing = qasClient.listTasks();
        } catch (QasClientException exception) {
            throw mapCreateFailure(exception);
        }
        if (existing == null) {
            existing = List.of();
        }
        for (QasTaskPlan planned : plan.tasks()) {
            boolean duplicate = existing.stream().anyMatch(task ->
                    planned.taskName().equals(task.taskName())
                            && planned.sourceUrl().equals(task.shareUrl())
            );
            if (duplicate) {
                throw badRequest("QAS 中已存在同名同来源任务：" + planned.taskName());
            }
        }
    }

    private String resultMessage(
            int createdCount,
            int plannedCount,
            boolean partial,
            boolean triggered,
            List<String> failures,
            String triggerFailure
    ) {
        if (partial) {
            String base = "已提交 " + createdCount + "/" + plannedCount + " 个一次性入库任务";
            String detail = failures.isEmpty() ? "" : "；" + String.join("；", failures);
            return base + detail + (triggered ? "；已开始执行成功提交的任务" : "；系统将继续处理");
        }
        if (triggerFailure != null) {
            return "一次性入库已提交，但立即执行失败：" + triggerFailure + "；系统将继续处理";
        }
        return plannedCount == 1
                ? "一次性入库已提交并开始执行"
                : "已提交 " + plannedCount + " 个一次性入库任务并开始执行";
    }

    public QuarkIngestTaskListResponse listTasks() {
        User user = authService.requireCurrentUser();
        LambdaQueryWrapper<QuarkIngestTask> query = new LambdaQueryWrapper<QuarkIngestTask>()
                .orderByDesc(QuarkIngestTask::getCreatedAt)
                .last("LIMIT 20");
        if (!isAdmin(user)) {
            query.eq(QuarkIngestTask::getCreatedByUserId, user.getId());
        }
        List<QuarkIngestTaskResponse> items = taskMapper.selectList(query).stream()
                .map(this::toTaskResponse)
                .toList();
        return new QuarkIngestTaskListResponse(items, items.size());
    }

    public QuarkIngestTaskLogListResponse getTaskLogs(String taskId) {
        User user = authService.requireCurrentUser();
        getAccessibleTask(taskId, user);
        List<QuarkIngestTaskLogResponse> items = taskLogMapper.selectList(
                        new LambdaQueryWrapper<QuarkIngestTaskLog>()
                                .eq(QuarkIngestTaskLog::getTaskId, taskId)
                                .orderByAsc(QuarkIngestTaskLog::getId)
                ).stream()
                .map(logEntry -> new QuarkIngestTaskLogResponse(
                        logEntry.getId(), logEntry.getTaskId(), logEntry.getLevel(), logEntry.getStage(),
                        logEntry.getMessage(), logEntry.getDetail(), logEntry.getCreatedAt()
                ))
                .toList();
        return new QuarkIngestTaskLogListResponse(items, items.size());
    }

    private QuarkIngestTask createIngestRecord(
            User user,
            String mediaType,
            String title,
            QasIngestPlan plan,
            String sourceType
    ) {
        QuarkIngestTask task = new QuarkIngestTask();
        task.setId(UUID.randomUUID().toString());
        task.setCreatedByUserId(user.getId());
        task.setMediaType(mediaType);
        task.setSourceType(StringUtils.hasText(sourceType) ? sourceType : "MANUAL_QUARK");
        task.setTitle(title);
        task.setStatus("PLANNED");
        task.setStage("planning");
        task.setTaskNames(String.join(", ", plan.tasks().stream().map(QasTaskPlan::taskName).toList()));
        task.setSavePath(plan.tasks().get(0).savePath());
        task.setImmediateExecutionStarted(false);
        task.setCreatedTaskCount(0);
        task.setPlannedTaskCount(plan.tasks().size());
        task.setMessage("入库计划已生成");
        LocalDateTime now = LocalDateTime.now();
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        taskMapper.insert(task);
        writeLog(task.getId(), "INFO", "planning", "分享检查与保存规划完成",
                "计划创建 " + plan.tasks().size() + " 个入库执行单元；目标目录：" + task.getSavePath());
        return task;
    }

    private void writePlanLogs(String taskId, String mediaType, QasIngestPlan plan) {
        for (QasTaskPlan task : plan.tasks()) {
            if (!StringUtils.hasText(task.pattern()) || !StringUtils.hasText(task.replace())) {
                boolean movie = "MOVIE".equals(mediaType);
                writeLog(taskId, movie ? "INFO" : "WARN", "planning",
                        movie ? "电影任务保留来源文件名" : "未启用自动重命名",
                        movie
                                ? "第一版电影内部文件不改名；仅保存到规范电影目录"
                                : task.versionLabel() == null
                                ? "任务：" + task.taskName() + "；将保留来源文件名和目录结构"
                                : "任务：" + task.taskName() + "；版本：" + task.versionLabel());
                continue;
            }
            String rule = StringUtils.hasText(task.renameRule()) ? task.renameRule() : "自动识别";
            writeLog(taskId, "INFO", "planning", "已生成重命名计划",
                    "任务：" + task.taskName() + "；规则：" + rule + "；匹配文件：" + task.matchedFileCount());
            for (QasRenameSample sample : task.renameSamples().stream().limit(20).toList()) {
                writeLog(taskId, "INFO", "rename_preview", "改名预览",
                        sample.sourceName() + " → " + sample.targetName());
            }
            if (task.matchedFileCount() > 20) {
                writeLog(taskId, "INFO", "rename_preview", "改名预览已截断",
                        "仅展示前 20/" + task.matchedFileCount() + " 个文件");
            }
        }
        for (String warning : plan.warnings()) {
            writeLog(taskId, "WARN", "planning", warning, null);
        }
    }

    private QasExecutionObserver executionObserver(String taskId) {
        return new QasExecutionObserver() {
            @Override
            public void onOutput(String level, String message) {
                String stage = message.contains("重命名") ? "renaming" : "processing";
                writeLog(taskId, level, stage, message, null);
            }

            @Override
            public void onCompleted() {
                writeLog(taskId, "INFO", "execution_ended", "入库处理输出已结束",
                        "这表示本次处理输出结束，不代表媒体已经完成入库");
                updateStage(taskId, "execution_ended");
            }

            @Override
            public void onInterrupted() {
                writeLog(taskId, "WARN", "execution_stream_interrupted", "入库处理输出意外中断",
                        "已提交的自动更新任务不会因此删除，后续仍可继续处理");
                updateStage(taskId, "execution_stream_interrupted");
            }
        };
    }

    private void writeLog(String taskId, String level, String stage, String message, String detail) {
        QuarkIngestTaskLog entry = new QuarkIngestTaskLog();
        entry.setTaskId(taskId);
        entry.setLevel(level);
        entry.setStage(stage);
        entry.setMessage(truncate(userFacingLogText(message), 1024));
        entry.setDetail(StringUtils.hasText(detail) ? truncate(userFacingLogText(detail), 4000) : null);
        entry.setCreatedAt(LocalDateTime.now());
        taskLogMapper.insert(entry);
    }

    private void updateIngestRecord(
            String taskId,
            String status,
            String stage,
            boolean immediateStarted,
            int createdCount,
            int plannedCount,
            String message
    ) {
        QuarkIngestTask task = new QuarkIngestTask();
        task.setId(taskId);
        task.setStatus(status);
        task.setStage(stage);
        task.setImmediateExecutionStarted(immediateStarted);
        task.setCreatedTaskCount(createdCount);
        task.setPlannedTaskCount(plannedCount);
        task.setMessage(truncate(message, 1024));
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
    }

    private void updateStage(String taskId, String stage) {
        QuarkIngestTask task = new QuarkIngestTask();
        task.setId(taskId);
        task.setStage(stage);
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
    }

    private void updateAcceptedIngestRecord(
            String taskId,
            String status,
            int createdCount,
            int plannedCount,
            String message
    ) {
        QuarkIngestTask task = new QuarkIngestTask();
        task.setId(taskId);
        task.setStatus(status);
        task.setImmediateExecutionStarted(true);
        task.setCreatedTaskCount(createdCount);
        task.setPlannedTaskCount(plannedCount);
        task.setMessage(truncate(message, 1024));
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
    }

    private QuarkIngestTask getAccessibleTask(String taskId, User user) {
        QuarkIngestTask task = taskMapper.selectById(taskId);
        if (task == null || (!isAdmin(user) && !task.getCreatedByUserId().equals(user.getId()))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "任务不存在", HttpStatus.NOT_FOUND);
        }
        return task;
    }

    private boolean isAdmin(User user) {
        return user != null && ADMIN_ROLE.equalsIgnoreCase(user.getRole());
    }

    private QuarkIngestTaskResponse toTaskResponse(QuarkIngestTask task) {
        return new QuarkIngestTaskResponse(
                task.getId(), task.getStatus(), task.getMediaType(), task.getTaskNames(), task.getSavePath(),
                Boolean.TRUE.equals(task.getImmediateExecutionStarted()), task.getCreatedTaskCount(),
                task.getPlannedTaskCount(), List.of(), task.getMessage()
        );
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 1) + "…";
    }

    private String validateShareUrl(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!StringUtils.hasText(normalized)) {
            throw badRequest("Quark 分享链接不能为空");
        }
        try {
            URI uri = new URI(normalized);
            boolean valid = "https".equalsIgnoreCase(uri.getScheme())
                    && "pan.quark.cn".equalsIgnoreCase(uri.getHost())
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && uri.getUserInfo() == null
                    && uri.getPath() != null
                    && QUARK_SHARE_PATH.matcher(uri.getPath()).matches();
            if (!valid) {
                throw badRequest("请输入合法的 pan.quark.cn 分享链接");
            }
            return uri.toString();
        } catch (URISyntaxException exception) {
            throw badRequest("请输入合法的 pan.quark.cn 分享链接");
        }
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

    private String requiredText(String value, String message) {
        String normalized = value == null ? "" : value.trim();
        if (!StringUtils.hasText(normalized)) {
            throw badRequest(message);
        }
        return normalized;
    }

    private String configuredRoot(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!StringUtils.hasText(normalized)) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE,
                    "QAS 保存路径尚未配置",
                    HttpStatus.SERVICE_UNAVAILABLE
            );
        }
        return normalizePath(normalized);
    }

    private String joinPath(String parent, String child) {
        return normalizePath(parent + "/" + child);
    }

    private String normalizePath(String value) {
        String normalized = value.trim().replaceAll("/{2,}", "/");
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        return normalized.length() > 1 ? normalized.replaceAll("/+$", "") : normalized;
    }

    private BusinessException mapCreateFailure(QasClientException exception) {
        return switch (exception.getReason()) {
            case CONFIGURATION -> new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE,
                    "QAS 服务尚未配置",
                    HttpStatus.SERVICE_UNAVAILABLE
            );
            case AUTHENTICATION -> new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE,
                    "QAS API Token 无效或已失效",
                    HttpStatus.SERVICE_UNAVAILABLE
            );
            case UPSTREAM, INVALID_RESPONSE -> new BusinessException(
                    ErrorCode.BAD_GATEWAY,
                    "QAS 创建任务失败：" + safeUpstreamMessage(exception),
                    HttpStatus.BAD_GATEWAY
            );
        };
    }

    private BusinessException mapInspectionFailure(QasShareInspectionException exception) {
        if (exception.isComplexStructureObserved()) {
            return new BusinessException(
                    ErrorCode.BAD_GATEWAY,
                    "QAS 分享中已发现复杂多目录，但未能完整检查：" + safeUpstreamMessage(exception),
                    HttpStatus.BAD_GATEWAY
            );
        }
        return switch (exception.getReason()) {
            case CONFIGURATION -> new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE,
                    "QAS 服务尚未配置",
                    HttpStatus.SERVICE_UNAVAILABLE
            );
            case AUTHENTICATION -> new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE,
                    "QAS API Token 无效或已失效",
                    HttpStatus.SERVICE_UNAVAILABLE
            );
            case UPSTREAM, INVALID_RESPONSE -> new BusinessException(
                    ErrorCode.BAD_GATEWAY,
                    "QAS 分享检查失败：" + safeUpstreamMessage(exception),
                    HttpStatus.BAD_GATEWAY
            );
        };
    }

    private String safeUpstreamMessage(QasClientException exception) {
        return safeMessage(exception.getMessage());
    }

    private String safeMessage(String message) {
        return StringUtils.hasText(message) ? message : "上游服务未返回错误原因";
    }

    private String userFacingLogText(String value) {
        if (value == null) {
            return null;
        }
        return value.replaceAll("(?i)QAS", "入库服务");
    }

    private String childIdAt(List<String> childIds, int index) {
        return index >= 0 && index < childIds.size() ? childIds.get(index) : null;
    }

    private String cleanLanguage(String language) {
        return StringUtils.hasText(language) ? language.trim() : "zh-CN";
    }

    private BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, HttpStatus.BAD_REQUEST);
    }

    private record PreparedIngest(QasIngestPlan plan, QasShareTree shareTree) {
    }

    private record ShareStats(int videoCount, int subtitleCount, int directoryCount, int maxDepth) {
    }
}
