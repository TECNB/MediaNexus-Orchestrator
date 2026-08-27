package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.config.QasProperties;
import com.medianexus.orchestrator.config.TmdbProperties;
import com.medianexus.orchestrator.dto.quark.request.QuarkFileSelectionRequest;
import com.medianexus.orchestrator.dto.quark.request.QuarkMultiSourceRequest;
import com.medianexus.orchestrator.dto.quark.request.QuarkSourceSelectionRequest;
import com.medianexus.orchestrator.dto.quark.response.QuarkMultiSourcePreviewResponse;
import com.medianexus.orchestrator.dto.quark.response.QuarkMultiSourceTaskResponse;
import com.medianexus.orchestrator.dto.quark.response.QuarkRenamePreviewResponse;
import com.medianexus.orchestrator.dto.quark.response.QuarkSourcePlanResponse;
import com.medianexus.orchestrator.dto.quark.response.QuarkSourceTaskResultResponse;
import com.medianexus.orchestrator.dto.quark.response.QuarkSourceTreeNodeResponse;
import com.medianexus.orchestrator.dto.quark.response.QuarkSeasonCoverageResponse;
import com.medianexus.orchestrator.integration.qas.QasClient;
import com.medianexus.orchestrator.integration.qas.QasClientException;
import com.medianexus.orchestrator.integration.qas.QasCreatedTask;
import com.medianexus.orchestrator.integration.qas.QasExecutionObserver;
import com.medianexus.orchestrator.integration.qas.QasShareInspectionException;
import com.medianexus.orchestrator.integration.qas.QasShareNode;
import com.medianexus.orchestrator.integration.qas.QasShareTree;
import com.medianexus.orchestrator.integration.qas.QasTaskCreateCommand;
import com.medianexus.orchestrator.integration.tmdb.TmdbClient;
import com.medianexus.orchestrator.integration.tmdb.TmdbClientException;
import com.medianexus.orchestrator.mapper.QuarkIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.QuarkIngestTaskMapper;
import com.medianexus.orchestrator.model.QuarkIngestTask;
import com.medianexus.orchestrator.model.QuarkIngestTaskLog;
import com.medianexus.orchestrator.model.User;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Multi-source TV/variety orchestration.  It deliberately sits beside the
 * legacy single-source service so movie requests and their public contract
 * remain unchanged while the share-tree flow can enforce candidate freshness.
 */
@Service
public class QuarkMultiSourceIngestService {

    private static final Pattern SHARE_PATH = Pattern.compile("^/s/[A-Za-z0-9]+(?:/[A-Fa-f0-9]{32})?/?$");
    private static final Pattern ILLEGAL_TASK_CHARACTER = Pattern.compile("[\\\\/:*?\"<>|]");
    private static final Pattern STANDARD_EPISODE = Pattern.compile("(?i).*?[Ss]\\d{1,2}[Ee](\\d{1,3}).*");
    private static final Pattern NXNN_EPISODE = Pattern.compile("(?i).*?\\d{1,2}[xX](\\d{1,3}).*");
    private static final Pattern CHINESE_EPISODE = Pattern.compile(".*?第\\s*(\\d{1,3})[集话期].*");
    private static final Pattern LEADING_EPISODE = Pattern.compile("^(\\d{1,3})(?:[ _.-].*)?\\.[^.]+$");
    private static final String MEDIA_EXTENSIONS = "mkv|mp4|avi|mov|wmv|flv|ts|m2ts|webm|rmvb|srt|ass|ssa|vtt|sub";
    private static final List<Integer> ALL_WEEKDAYS = List.of(1, 2, 3, 4, 5, 6, 7);

    private final QasClient qasClient;
    private final QuarkShareTreeService shareTreeService;
    private final QasProperties qasProperties;
    private final TmdbProperties tmdbProperties;
    private final AuthService authService;
    private final QuarkIngestPlanner planner;
    private final TmdbClient tmdbClient;
    private final QuarkShareSourceRegistry registry;
    private final QuarkIngestTaskMapper taskMapper;
    private final QuarkIngestTaskLogMapper taskLogMapper;

    public QuarkMultiSourceIngestService(
            QasClient qasClient,
            QuarkShareTreeService shareTreeService,
            QasProperties qasProperties,
            TmdbProperties tmdbProperties,
            AuthService authService,
            QuarkIngestPlanner planner,
            TmdbClient tmdbClient,
            QuarkShareSourceRegistry registry,
            QuarkIngestTaskMapper taskMapper,
            QuarkIngestTaskLogMapper taskLogMapper
    ) {
        this.qasClient = qasClient;
        this.shareTreeService = shareTreeService;
        this.qasProperties = qasProperties;
        this.tmdbProperties = tmdbProperties;
        this.authService = authService;
        this.planner = planner;
        this.tmdbClient = tmdbClient;
        this.registry = registry;
        this.taskMapper = taskMapper;
        this.taskLogMapper = taskLogMapper;
    }

    public QuarkMultiSourcePreviewResponse previewStructure(QuarkMultiSourceRequest request, String mediaType) {
        authService.requireCurrentUser();
        validateRequest(request, mediaType);
        QasShareTree tree = inspect(request.shareUrl());
        QuarkShareSourceRegistry.PreviewSession session = registry.create(request.shareUrl(), mediaType, tree);
        String saveRoot = rootPath(mediaType);
        String rootCandidateId = session.rootCandidateIds().isEmpty()
                ? null
                : session.rootCandidateIds().get(0);
        List<QuarkSourcePlanResponse> sources = session.candidates().values().stream()
                .map(candidate -> sourceResponse(
                        candidate,
                        new QuarkSourceSelectionRequest(candidate.id(), candidate.detectedSeason(), false, false),
                        candidate.detectedSeason(),
                        "PENDING",
                        List.of(),
                        List.of()
                ))
                .toList();
        return new QuarkMultiSourcePreviewResponse(
                !session.candidates().isEmpty(),
                session.previewId(),
                mediaType,
                saveRoot,
                rootCandidateId,
                tree.entries().stream().map(node -> treeNode(node, session, "")).toList(),
                sources,
                List.of(),
                List.of(),
                session.candidates().isEmpty()
                        ? noSourceMessage(tree)
                        : "分享目录检查完成，请为每个来源设置季度或忽略"
        );
    }

    private String noSourceMessage(QasShareTree tree) {
        long zipCount = countZipFiles(tree.entries());
        if (zipCount > 0) {
            return "未发现可播放视频；检测到 " + zipCount + " 个 ZIP 压缩包，当前链路不支持解压后入库";
        }
        return "未发现可播放视频；请确认分享中包含 MKV、MP4 等受支持的视频文件";
    }

    private long countZipFiles(List<QasShareNode> nodes) {
        long count = 0;
        for (QasShareNode node : nodes) {
            if (node.directory()) {
                count += countZipFiles(node.children());
            } else if (node.name().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                count++;
            }
        }
        return count;
    }

    public QuarkMultiSourcePreviewResponse previewPlan(QuarkMultiSourceRequest request, String mediaType) {
        authService.requireCurrentUser();
        validateRequest(request, mediaType);
        QuarkShareSourceRegistry.PreviewSession session = registry.require(request.previewId());
        validateSession(request, mediaType, session);
        QasShareTree freshTree = inspect(request.shareUrl());
        ensureTreeUnchanged(session, freshTree);
        Computation computation = compute(request, mediaType, session, freshTree);
        session.setPlanFingerprint(computation.planFingerprint());
        return new QuarkMultiSourcePreviewResponse(
                computation.ready(),
                session.previewId(),
                mediaType,
                rootPath(mediaType),
                session.rootCandidateIds().isEmpty() ? null : session.rootCandidateIds().get(0),
                freshTree.entries().stream().map(node -> treeNode(node, session, "")).toList(),
                computation.sources(),
                computation.seasonCoverages(),
                computation.warnings(),
                computation.message()
        );
    }

    public QuarkMultiSourceTaskResponse ingest(
            QuarkMultiSourceRequest request,
            String mediaType
    ) {
        User user = authService.requireCurrentUser();
        validateRequest(request, mediaType);
        QuarkShareSourceRegistry.PreviewSession session = registry.require(request.previewId());
        validateSession(request, mediaType, session);
        QasShareTree freshTree = inspect(request.shareUrl());
        ensureTreeUnchanged(session, freshTree);
        Computation computation = compute(request, mediaType, session, freshTree);
        if (!computation.ready()) {
            throw badRequest(computation.message());
        }
        if (!StringUtils.hasText(session.planFingerprint())
                || !session.planFingerprint().equals(computation.planFingerprint())) {
            throw badRequest("改名预览已过期，请刷新预览后再提交");
        }
        checkDuplicateTasks(computation.tasks());

        String localTaskId = UUID.randomUUID().toString();
        createLocalRecord(user, localTaskId, mediaType, request.title(), computation);
        List<QasCreatedTask> created = new ArrayList<>();
        List<QuarkSourceTaskResultResponse> sourceResults = new ArrayList<>();
        List<String> warnings = new ArrayList<>(computation.warnings());
        for (PlannedSource source : computation.tasks()) {
            try {
                QasCreatedTask createdTask = qasClient.createTask(new QasTaskCreateCommand(
                        source.task().taskName(),
                        source.task().sourceUrl(),
                        source.task().savePath(),
                        source.task().pattern(),
                        source.task().replace(),
                        request.followUpdatesEnabled() && source.followUpdates() ? ALL_WEEKDAYS : List.of(),
                        null
                ));
                created.add(createdTask);
                sourceResults.add(new QuarkSourceTaskResultResponse(
                        source.candidate().id(), source.task().taskName(), "CREATED", "QAS 任务已创建"
                ));
                String schedule = request.followUpdatesEnabled() && source.followUpdates()
                        ? "每日订阅"
                        : "一次性（首次执行后不定时）";
                writeLog(localTaskId, "INFO", "creating", "已创建 QAS 任务",
                        source.task().taskName() + "；调度：" + schedule);
            } catch (QasClientException exception) {
                String message = safeMessage(exception.getMessage());
                sourceResults.add(new QuarkSourceTaskResultResponse(
                        source.candidate().id(), source.task().taskName(), "FAILED", message
                ));
                warnings.add(source.task().taskName() + "：" + message);
                writeLog(localTaskId, "ERROR", "creating", "QAS 任务创建失败", source.task().taskName() + "：" + message);
            }
        }
        if (created.isEmpty()) {
            updateLocalRecord(localTaskId, "FAILED", false, 0, computation.tasks().size(), "没有成功创建 QAS 任务");
            return new QuarkMultiSourceTaskResponse(
                    localTaskId, "FAILED", mediaType, rootPath(mediaType), false,
                    computation.tasks().size(), 0, sourceResults, warnings, "没有成功创建 QAS 任务"
            );
        }

        boolean triggered = true;
        String triggerError = null;
        try {
            qasClient.triggerTasksNow(created, executionObserver(localTaskId));
        } catch (QasClientException exception) {
            triggered = false;
            triggerError = safeMessage(exception.getMessage());
            warnings.add("首次立即执行失败：" + triggerError);
        }
        int plannedCount = computation.tasks().size();
        int createdCount = created.size();
        boolean hasSubscriptions = request.followUpdatesEnabled()
                && computation.tasks().stream().anyMatch(PlannedSource::followUpdates);
        String status = createdCount < plannedCount ? "PARTIAL" : triggered ? "STARTED" : "SCHEDULED";
        String message = executionSummary(
                computation, hasSubscriptions, createdCount < plannedCount, triggered, triggerError
        );
        updateLocalRecord(localTaskId, status, triggered, createdCount, plannedCount, message);
        return new QuarkMultiSourceTaskResponse(
                localTaskId,
                status,
                mediaType,
                rootPath(mediaType),
                triggered,
                plannedCount,
                createdCount,
                sourceResults,
                warnings,
                message
        );
    }

    private String executionSummary(
            Computation computation,
            boolean hasSubscriptions,
            boolean partial,
            boolean triggered,
            String triggerError
    ) {
        int videoCount = 0;
        int manualCount = 0;
        int ignoredCount = 0;
        for (QuarkSourcePlanResponse source : computation.sources()) {
            for (QuarkRenamePreviewResponse file : source.files()) {
                if (!isPlayableVideo(file.sourceName())) {
                    continue;
                }
                if ("IGNORED".equals(file.status())) {
                    ignoredCount++;
                } else if (!"EXCLUDED".equals(file.status()) && !"UNRECOGNIZED".equals(file.status())) {
                    videoCount++;
                }
                if ("MANUAL".equals(file.status())) {
                    manualCount++;
                }
            }
        }
        String counts = "计划入库 " + videoCount + " 个视频"
                + (manualCount > 0 ? "，其中 " + manualCount + " 个已手动指定集数" : "")
                + (ignoredCount > 0 ? "，已忽略 " + ignoredCount + " 个文件" : "");
        if (partial) {
            return counts + "；部分文件未能开始入库，请查看失败来源";
        }
        if (!triggered) {
            return counts + "；首次执行未能启动：" + triggerError;
        }
        return counts + "，已开始执行" + (hasSubscriptions ? "；标记的更新文件夹将继续每日检查" : "");
    }

    private Computation compute(
            QuarkMultiSourceRequest request,
            String mediaType,
            QuarkShareSourceRegistry.PreviewSession session,
            QasShareTree freshTree
    ) {
        Map<String, QuarkSourceSelectionRequest> selections = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        for (QuarkSourceSelectionRequest selection : request.sources()) {
            if (!StringUtils.hasText(selection.sourceCandidateId())
                    || selections.put(selection.sourceCandidateId(), selection) != null) {
                errors.add("来源候选重复或无效");
            }
        }
        if (selections.size() != session.candidates().size()) {
            errors.add("请为每个来源设置季度或明确忽略");
        }
        List<PlannedSource> tasks = new ArrayList<>();
        List<QuarkSourcePlanResponse> sourceResponses = new ArrayList<>();
        for (QuarkShareSourceRegistry.SourceCandidate expected : session.candidates().values()) {
            QuarkSourceSelectionRequest selection = selections.get(expected.id());
            if (selection == null) {
                sourceResponses.add(sourceResponse(expected, null, null, "UNCONFIGURED", List.of("来源未设置季度或忽略"), List.of()));
                continue;
            }
            QuarkShareSourceRegistry.SourceCandidate candidate;
            try {
                candidate = QuarkShareSourceRegistry.locate(session, expected.id(), freshTree);
            } catch (IllegalArgumentException exception) {
                errors.add(exception.getMessage());
                sourceResponses.add(sourceResponse(expected, selection, null, "STALE", List.of(exception.getMessage()), List.of()));
                continue;
            }
            if (selection.ignored()) {
                sourceResponses.add(sourceResponse(candidate, selection, null, "IGNORED", List.of(), List.of()));
                continue;
            }
            Integer seasonNumber = selection.seasonNumber() != null
                    ? selection.seasonNumber()
                    : candidate.detectedSeason();
            if ("MIXED".equals(candidate.seasonStatus())) {
                errors.add(candidate.sourceName() + " 明确混有多个季度，不能在同一来源内自动拆分");
                sourceResponses.add(sourceResponse(candidate, selection, null, "MIXED", List.of("混合季度来源必须拆分后再提交"), List.of()));
                continue;
            }
            if (seasonNumber == null || seasonNumber < 1) {
                errors.add(candidate.sourceName() + " 尚未设置季度");
                sourceResponses.add(sourceResponse(candidate, selection, null, "UNRECOGNIZED", List.of("未识别季度，请手动设置或忽略"), List.of()));
                continue;
            }
            String savePath = joinPath(
                    joinPath(rootPath(mediaType), cleanPathSegment(request.title())),
                    String.format(Locale.ROOT, "Season %02d", seasonNumber)
            );
            FileDecisions fileDecisions;
            try {
                fileDecisions = resolveFileDecisions(candidate, selection.files());
            } catch (IllegalArgumentException exception) {
                String message = exception.getMessage();
                errors.add(candidate.sourceName() + "：" + message);
                sourceResponses.add(sourceResponse(candidate, selection, seasonNumber, "BLOCKED", List.of(message), List.of()));
                continue;
            }
            QuarkShareSourceRegistry.SourceCandidate automaticCandidate = withoutCorrectedFiles(candidate, fileDecisions);
            QasIngestPlan planned;
            try {
                planned = hasPlayableVideo(automaticCandidate.entries())
                        ? planSource(request, mediaType, automaticCandidate, seasonNumber, savePath)
                        : new QasIngestPlan(List.of(), List.of());
            } catch (QuarkIngestPlanningException exception) {
                String message = safeMessage(exception.getMessage());
                errors.add(candidate.sourceName() + "：" + message);
                sourceResponses.add(sourceResponse(candidate, selection, seasonNumber, "BLOCKED", List.of(message), List.of()));
                continue;
            }
            if (planned.tasks().size() > 1) {
                String message = "一个来源包含多组互斥命名规则，当前无法安全提交";
                errors.add(candidate.sourceName() + "：" + message);
                sourceResponses.add(sourceResponse(candidate, selection, seasonNumber, "BLOCKED", List.of(message), planned.warnings()));
                continue;
            }
            QasTaskPlan automaticTask = planned.tasks().isEmpty() ? null : planned.tasks().get(0);
            if (automaticTask != null
                    && (!StringUtils.hasText(automaticTask.pattern()) || !StringUtils.hasText(automaticTask.replace()))) {
                String message = "自动重命名无法覆盖全部视频，请为标红文件指定集数或忽略";
                errors.add(candidate.sourceName() + "：" + message);
                List<QuarkRenamePreviewResponse> files = diagnosticRenamePreview(
                        request.title(), seasonNumber, candidate, fileDecisions
                );
                sourceResponses.add(sourceResponse(candidate, selection, seasonNumber, "BLOCKED", List.of(message), planned.warnings(), files, automaticTask));
                continue;
            }
            List<QasTaskPlan> sourceTasks = new ArrayList<>();
            if (automaticTask != null) {
                sourceTasks.add(excludingFiles(automaticTask, fileDecisions.excludedNames()));
            }
            sourceTasks.addAll(manualTasks(request.title(), seasonNumber, savePath, candidate, fileDecisions));
            if (sourceTasks.isEmpty()) {
                sourceResponses.add(sourceResponse(candidate, selection, seasonNumber, "IGNORED", List.of(), planned.warnings()));
                continue;
            }
            List<QuarkRenamePreviewResponse> files = renamePreview(candidate, sourceTasks, fileDecisions);
            List<String> sourceErrors = files.stream()
                    .filter(file -> "CONFLICT".equals(file.status()) || "UNRECOGNIZED".equals(file.status()))
                    .map(file -> StringUtils.hasText(file.message()) ? file.message() : file.sourceName())
                    .distinct()
                    .toList();
            if (!sourceErrors.isEmpty()) {
                errors.addAll(sourceErrors);
            }
            for (int taskIndex = 0; taskIndex < sourceTasks.size(); taskIndex++) {
                tasks.add(new PlannedSource(
                        candidate,
                        taskIndex == 0 && automaticTask != null
                                && request.followUpdatesEnabled() && selection.followUpdates(),
                        sourceTasks.get(taskIndex),
                        seasonNumber
                ));
            }
            sourceResponses.add(sourceResponse(candidate, selection, seasonNumber,
                    sourceErrors.isEmpty() ? "READY" : "BLOCKED", sourceErrors, planned.warnings(), files, sourceTasks.get(0)));
        }

        applyTaskNames(tasks, request.title());
        Map<String, Set<String>> conflictingTargets = detectGlobalConflicts(tasks, errors);
        sourceResponses = updateSourceResponses(sourceResponses, tasks, conflictingTargets);
        List<QuarkSeasonCoverageResponse> seasonCoverages = buildSeasonCoverages(request, sourceResponses);
        boolean ready = errors.isEmpty() && !tasks.isEmpty();
        String planFingerprint = planFingerprint(tasks, sourceResponses, request.followUpdatesEnabled());
        String message = ready
                ? "改名预览完成，可以确认入库"
                : errors.isEmpty()
                ? "没有可执行来源，请至少映射一个未忽略来源"
                : String.join("；", errors);
        return new Computation(
                ready,
                tasks,
                sourceResponses,
                seasonCoverages,
                List.of(),
                message,
                planFingerprint
        );
    }

    private List<QuarkSeasonCoverageResponse> buildSeasonCoverages(
            QuarkMultiSourceRequest request,
            List<QuarkSourcePlanResponse> sources
    ) {
        Map<Integer, CoverageAccumulator> coverageBySeason = new TreeMap<>();
        for (QuarkSourcePlanResponse source : sources) {
            Integer season = source.selectedSeason();
            if (season == null || source.ignored()) {
                continue;
            }
            CoverageAccumulator coverage = coverageBySeason.computeIfAbsent(season, CoverageAccumulator::new);
            for (QuarkRenamePreviewResponse file : source.files()) {
                if (!isPlayableVideo(file.sourceName())) {
                    continue;
                }
                if ("IGNORED".equals(file.status())) {
                    coverage.ignoredVideoCount++;
                } else if ("UNRECOGNIZED".equals(file.status()) || "CONFLICT".equals(file.status())) {
                    coverage.unknownVideoCount++;
                } else if (!"EXCLUDED".equals(file.status())) {
                    coverage.videoCount++;
                    coverage.episodeNumbers.addAll(targetEpisodes(file.targetName()));
                }
            }
        }
        Map<Integer, TmdbEpisodeCoverage> tmdbBySeason = new HashMap<>();
        for (Integer season : coverageBySeason.keySet()) {
            tmdbBySeason.put(season, loadEpisodeCoverage(request.tmdbId(), season));
        }
        return coverageBySeason.values().stream()
                .map(coverage -> coverage.toResponse(tmdbBySeason.get(coverage.seasonNumber)))
                .toList();
    }

    private Set<Integer> targetEpisodes(String targetName) {
        Set<Integer> episodes = new HashSet<>();
        if (targetName == null) {
            return episodes;
        }
        java.util.regex.Matcher matcher = Pattern.compile("(?i)S\\d{2}E(\\d{1,3})(?:-E(\\d{1,3}))?").matcher(targetName);
        if (!matcher.find()) {
            return episodes;
        }
        int first = Integer.parseInt(matcher.group(1));
        int last = matcher.group(2) == null ? first : Integer.parseInt(matcher.group(2));
        for (int episode = Math.min(first, last); episode <= Math.max(first, last); episode++) {
            episodes.add(episode);
        }
        return episodes;
    }

    private TmdbEpisodeCoverage loadEpisodeCoverage(Integer tmdbId, int season) {
        if (tmdbId == null || tmdbId <= 0) {
            return TmdbEpisodeCoverage.unavailable("未绑定 TMDB，暂无法取得季度集数");
        }
        try {
            JsonNode details = tmdbClient.getTvSeasonDetails(tmdbId, season,
                    StringUtils.hasText(tmdbProperties.getDefaultLanguage())
                            ? tmdbProperties.getDefaultLanguage().trim() : "zh-CN");
            JsonNode episodes = details.path("episodes");
            if (!episodes.isArray()) {
                return TmdbEpisodeCoverage.unavailable("TMDB 季度详情缺少 episodes");
            }
            Set<Integer> all = new HashSet<>();
            Set<Integer> aired = new HashSet<>();
            Set<Integer> unknownDate = new HashSet<>();
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
            for (JsonNode episode : episodes) {
                int number = episode.path("episode_number").asInt(0);
                if (number <= 0) continue;
                all.add(number);
                String airDate = episode.path("air_date").asText("");
                if (!StringUtils.hasText(airDate)) {
                    unknownDate.add(number);
                    continue;
                }
                try {
                    if (!LocalDate.parse(airDate).isAfter(today)) aired.add(number);
                } catch (DateTimeParseException ignored) {
                    unknownDate.add(number);
                }
            }
            return new TmdbEpisodeCoverage(all, aired, unknownDate, null);
        } catch (TmdbClientException exception) {
            return TmdbEpisodeCoverage.unavailable("TMDB 季度详情获取失败：" + safeMessage(exception.getMessage()));
        }
    }

    private QasIngestPlan planSource(
            QuarkMultiSourceRequest request,
            String mediaType,
            QuarkShareSourceRegistry.SourceCandidate candidate,
            int seasonNumber,
            String savePath
    ) {
        QasShareTree sourceTree = new QasShareTree(candidate.sourceUrl(), candidate.entries());
        Map<LocalDate, List<Integer>> dates = Map.of();
        try {
            return "VARIETY".equals(mediaType)
                    ? planner.planVariety(request.title(), seasonNumber, savePath, sourceTree, dates)
                    : planner.planSeries(request.title(), seasonNumber, savePath, sourceTree, dates);
        } catch (QuarkIngestPlanningException exception) {
            if (exception.getReason() != QuarkIngestPlanningException.Reason.DATE_MAPPING_REQUIRED
                    || request.tmdbId() == null || request.tmdbId() <= 0) {
                throw exception;
            }
            Map<LocalDate, List<Integer>> airDates = loadAirDateEpisodes(request.tmdbId(), seasonNumber);
            return "VARIETY".equals(mediaType)
                    ? planner.planVariety(request.title(), seasonNumber, savePath, sourceTree, airDates)
                    : planner.planSeries(request.title(), seasonNumber, savePath, sourceTree, airDates);
        }
    }

    private List<QuarkRenamePreviewResponse> renamePreview(
            QuarkShareSourceRegistry.SourceCandidate candidate,
            QasTaskPlan task
    ) {
        return renamePreview(candidate, List.of(task), FileDecisions.empty());
    }

    private List<QuarkRenamePreviewResponse> renamePreview(
            QuarkShareSourceRegistry.SourceCandidate candidate,
            List<QasTaskPlan> tasks,
            FileDecisions decisions
    ) {
        Map<String, QasRenameSample> samples = new LinkedHashMap<>();
        tasks.stream().flatMap(task -> task.renameSamples().stream())
                .forEach(sample -> samples.put(sample.sourceName(), sample));
        List<QuarkRenamePreviewResponse> previews = new ArrayList<>();
        for (QasShareNode entry : candidate.entries()) {
            String fileId = fileId(candidate, entry);
            if (decisions.ignoredFileIds().contains(fileId)) {
                previews.add(new QuarkRenamePreviewResponse(
                        fileId, entry.name(), entry.name(), episodeNumber(entry.name()), "IGNORED", "已选择忽略，不会转存"
                ));
                continue;
            }
            QasRenameSample sample = samples.get(entry.name());
            if (sample != null) {
                boolean manual = decisions.manualEpisodes().containsKey(fileId);
                previews.add(new QuarkRenamePreviewResponse(
                        fileId,
                        sample.sourceName(),
                        sample.targetName(),
                        episodeNumber(sample.targetName()),
                        manual ? "MANUAL" : sample.sourceName().equals(sample.targetName()) ? "UNCHANGED" : "READY",
                        manual ? "已手动指定集数" : null
                ));
            }
        }
        Set<String> plannedNames = samples.keySet();
        candidate.entries().stream()
                .filter(entry -> !plannedNames.contains(entry.name()))
                .filter(entry -> !decisions.ignoredFileIds().contains(fileId(candidate, entry)))
                .map(entry -> {
                    boolean unsafeVideo = isPlayableVideo(entry.name()) || isSubtitle(entry.name());
                    return new QuarkRenamePreviewResponse(
                            fileId(candidate, entry),
                            entry.name(),
                            entry.name(),
                            episodeNumber(entry.name()),
                            unsafeVideo ? "UNRECOGNIZED" : "EXCLUDED",
                            unsafeVideo ? "文件无法安全对应到改名规则" : "非视频附件不参与改名"
                    );
                })
                .forEach(previews::add);
        return List.copyOf(previews);
    }

    private FileDecisions resolveFileDecisions(
            QuarkShareSourceRegistry.SourceCandidate candidate,
            List<QuarkFileSelectionRequest> requests
    ) {
        Map<String, QasShareNode> files = candidate.entries().stream().collect(
                java.util.stream.Collectors.toMap(
                        entry -> fileId(candidate, entry), entry -> entry, (left, right) -> left, LinkedHashMap::new
                )
        );
        Map<String, Integer> manualEpisodes = new LinkedHashMap<>();
        Set<String> ignoredFileIds = new HashSet<>();
        Set<String> excludedNames = new HashSet<>();
        Set<String> manualStems = new HashSet<>();
        Set<String> seen = new HashSet<>();
        for (QuarkFileSelectionRequest request : requests) {
            if (request == null || !StringUtils.hasText(request.fileId()) || !seen.add(request.fileId())) {
                throw new IllegalArgumentException("文件修正项重复或无效");
            }
            QasShareNode file = files.get(request.fileId());
            if (file == null || file.directory()) {
                throw new IllegalArgumentException("文件修正项已过期，请刷新分享目录");
            }
            if (request.ignored()) {
                if (request.episodeNumber() != null) {
                    throw new IllegalArgumentException(file.name() + " 不能同时指定集数和忽略");
                }
                ignoredFileIds.add(request.fileId());
                excludedNames.add(file.name());
                continue;
            }
            if (request.episodeNumber() == null || request.episodeNumber() < 1 || request.episodeNumber() > 999) {
                throw new IllegalArgumentException(file.name() + " 的手动集数必须在 1～999 之间");
            }
            if (!isPlayableVideo(file.name())) {
                throw new IllegalArgumentException("只能为视频文件手动指定集数：" + file.name());
            }
            manualEpisodes.put(request.fileId(), request.episodeNumber());
            manualStems.add(stemOf(file.name()));
        }
        for (QasShareNode file : candidate.entries()) {
            String fileStem = stemOf(file.name());
            if (manualStems.stream().anyMatch(stem -> fileStem.equals(stem) || fileStem.startsWith(stem + "."))) {
                excludedNames.add(file.name());
            }
        }
        return new FileDecisions(Map.copyOf(manualEpisodes), Set.copyOf(ignoredFileIds), Set.copyOf(excludedNames));
    }

    private QuarkShareSourceRegistry.SourceCandidate withoutCorrectedFiles(
            QuarkShareSourceRegistry.SourceCandidate candidate,
            FileDecisions decisions
    ) {
        List<QasShareNode> entries = candidate.entries().stream()
                .filter(entry -> !decisions.excludedNames().contains(entry.name()))
                .toList();
        return new QuarkShareSourceRegistry.SourceCandidate(
                candidate.id(), candidate.sourceName(), candidate.relativePath(), candidate.kind(), candidate.sourceUrl(),
                candidate.fidPath(), entries, candidate.detectedSeason(), candidate.seasonStatus()
        );
    }

    private QasTaskPlan excludingFiles(QasTaskPlan task, Set<String> excludedNames) {
        if (excludedNames.isEmpty()) {
            return task;
        }
        String exclusions = excludedNames.stream().map(this::regexEscape).sorted()
                .collect(java.util.stream.Collectors.joining("|"));
        String original = task.pattern();
        String prefix = "^(?!(?:" + exclusions + ")$)";
        String pattern = original.startsWith("^") ? prefix + original.substring(1) : prefix + original;
        return new QasTaskPlan(
                task.taskName(), task.sourceUrl(), task.savePath(), pattern, task.replace(), task.versionLabel(),
                task.renameRule(), task.matchedFileCount(), task.renameSamples()
        );
    }

    private List<QasTaskPlan> manualTasks(
            String title,
            int seasonNumber,
            String savePath,
            QuarkShareSourceRegistry.SourceCandidate candidate,
            FileDecisions decisions
    ) {
        String season = String.format(Locale.ROOT, "%02d", seasonNumber);
        List<QasTaskPlan> tasks = new ArrayList<>();
        for (Map.Entry<String, Integer> correction : decisions.manualEpisodes().entrySet()) {
            QasShareNode video = candidate.entries().stream()
                    .filter(entry -> fileId(candidate, entry).equals(correction.getKey()))
                    .findFirst()
                    .orElseThrow();
            String stem = stemOf(video.name());
            String episode = String.format(Locale.ROOT, "%02d", correction.getValue());
            Pattern matcher = Pattern.compile(
                    "^" + regexEscape(stem) + "((?:\\.[^.]+)*)\\.(" + MEDIA_EXTENSIONS + ")$",
                    Pattern.CASE_INSENSITIVE
            );
            String targetBase = title + " - S" + season + "E" + episode;
            List<QasRenameSample> samples = candidate.entries().stream().map(entry -> {
                java.util.regex.Matcher match = matcher.matcher(entry.name());
                return match.matches()
                        ? new QasRenameSample(entry.name(), targetBase + match.group(1) + "." + match.group(2))
                        : null;
            }).filter(java.util.Objects::nonNull).toList();
            tasks.add(new QasTaskPlan(
                    title + " S" + season + " [手动 E" + episode + "]",
                    candidate.sourceUrl(), savePath, matcher.pattern(), targetBase + "\\1.\\2", null,
                    "手动指定 E" + episode, samples.size(), samples
            ));
        }
        return List.copyOf(tasks);
    }

    private boolean hasPlayableVideo(List<QasShareNode> entries) {
        return entries.stream().anyMatch(entry -> !entry.directory() && isPlayableVideo(entry.name()));
    }

    private String fileId(QuarkShareSourceRegistry.SourceCandidate candidate, QasShareNode entry) {
        return UUID.nameUUIDFromBytes((candidate.id() + "\u0000" + entry.fid()).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String stemOf(String name) {
        int extension = name.lastIndexOf('.');
        return extension > 0 ? name.substring(0, extension) : name;
    }

    private String regexEscape(String value) {
        return value.replaceAll("([\\\\.\\[\\]{}()*+?^$|])", "\\\\$1");
    }

    private boolean isPlayableVideo(String name) {
        return StringUtils.hasText(name) && name.toLowerCase(Locale.ROOT).matches(
                ".*\\.(mkv|mp4|avi|mov|wmv|flv|ts|m2ts|webm|rmvb)$"
        );
    }

    private boolean isSubtitle(String name) {
        return StringUtils.hasText(name) && name.toLowerCase(Locale.ROOT).matches(
                ".*\\.(srt|ass|ssa|vtt|sub)$"
        );
    }

    private int episodeNumber(String targetName) {
        if (targetName == null) {
            return 0;
        }
        java.util.regex.Matcher matcher = Pattern.compile("(?i)S\\d{2}E(\\d{2,3})").matcher(targetName);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private List<QuarkRenamePreviewResponse> diagnosticRenamePreview(
            String title,
            int seasonNumber,
            QuarkShareSourceRegistry.SourceCandidate candidate,
            FileDecisions decisions
    ) {
        Map<Integer, List<QasShareNode>> videosByEpisode = new LinkedHashMap<>();
        for (QasShareNode entry : candidate.entries()) {
            String id = fileId(candidate, entry);
            if (!isPlayableVideo(entry.name()) || decisions.ignoredFileIds().contains(id)) {
                continue;
            }
            Integer episode = inferredEpisode(entry.name());
            if (episode != null) {
                videosByEpisode.computeIfAbsent(episode, ignored -> new ArrayList<>()).add(entry);
            }
        }
        String season = String.format(Locale.ROOT, "%02d", seasonNumber);
        List<QuarkRenamePreviewResponse> previews = new ArrayList<>();
        for (QasShareNode entry : candidate.entries()) {
            String id = fileId(candidate, entry);
            if (decisions.ignoredFileIds().contains(id)) {
                previews.add(new QuarkRenamePreviewResponse(
                        id, entry.name(), entry.name(), episodeNumber(entry.name()), "IGNORED", "已选择忽略，不会转存"
                ));
                continue;
            }
            if (!isPlayableVideo(entry.name())) {
                previews.add(new QuarkRenamePreviewResponse(
                        id, entry.name(), entry.name(), episodeNumber(entry.name()), "EXCLUDED", "非视频附件不参与诊断"
                ));
                continue;
            }
            Integer episode = inferredEpisode(entry.name());
            if (episode == null) {
                previews.add(new QuarkRenamePreviewResponse(
                        id, entry.name(), entry.name(), null, "UNRECOGNIZED",
                        "无法从文件名提取集数，请手动指定或忽略"
                ));
                continue;
            }
            String target = title + " - S" + season + "E" + String.format(Locale.ROOT, "%02d", episode)
                    + "." + extensionOf(entry.name());
            List<QasShareNode> duplicates = videosByEpisode.getOrDefault(episode, List.of());
            boolean conflict = duplicates.size() > 1;
            String conflictNames = duplicates.stream().map(QasShareNode::name)
                    .collect(java.util.stream.Collectors.joining("、"));
            previews.add(new QuarkRenamePreviewResponse(
                    id, entry.name(), target, episode, conflict ? "CONFLICT" : "READY",
                    conflict ? "与 " + conflictNames + " 解析为同一集，请修正其中一个文件" : "已识别集数，但其他文件阻止了统一规则"
            ));
        }
        return List.copyOf(previews);
    }

    private Integer inferredEpisode(String name) {
        for (Pattern pattern : List.of(STANDARD_EPISODE, NXNN_EPISODE, CHINESE_EPISODE, LEADING_EPISODE)) {
            java.util.regex.Matcher matcher = pattern.matcher(name);
            if (matcher.matches()) {
                int episode = Integer.parseInt(matcher.group(1));
                return episode > 0 ? episode : null;
            }
        }
        return null;
    }

    private String extensionOf(String name) {
        int extension = name.lastIndexOf('.');
        return extension >= 0 ? name.substring(extension + 1) : "mkv";
    }

    private Map<String, Set<String>> detectGlobalConflicts(
            List<PlannedSource> tasks,
            List<String> errors
    ) {
        Map<String, PlannedSource> targetOwners = new HashMap<>();
        Map<String, Set<String>> conflictingTargets = new HashMap<>();
        for (PlannedSource planned : tasks) {
            for (QuarkRenamePreviewResponse file : renamePreview(planned.candidate(), planned.task())) {
                if ("EXCLUDED".equals(file.status()) || "UNRECOGNIZED".equals(file.status())) {
                    continue;
                }
                String normalizedTarget = file.targetName().toLowerCase(Locale.ROOT);
                String key = planned.task().savePath().toLowerCase(Locale.ROOT) + "\u0000" + normalizedTarget;
                PlannedSource previous = targetOwners.putIfAbsent(key, planned);
                if (previous != null && previous != planned) {
                    String message = "同季目标文件名冲突：" + file.targetName();
                    errors.add(message);
                    conflictingTargets.computeIfAbsent(previous.candidate().id(), ignored -> new HashSet<>())
                            .add(normalizedTarget);
                    conflictingTargets.computeIfAbsent(planned.candidate().id(), ignored -> new HashSet<>())
                            .add(normalizedTarget);
                }
            }
        }
        return conflictingTargets;
    }

    private List<QuarkSourcePlanResponse> updateSourceResponses(
            List<QuarkSourcePlanResponse> responses,
            List<PlannedSource> tasks,
            Map<String, Set<String>> conflictingTargets
    ) {
        Map<String, PlannedSource> byCandidate = tasks.stream()
                .collect(java.util.stream.Collectors.toMap(
                        item -> item.candidate().id(), item -> item, (left, right) -> left, LinkedHashMap::new
                ));
        return responses.stream().map(response -> {
            PlannedSource planned = byCandidate.get(response.sourceCandidateId());
            if (planned == null) {
                return response;
            }
            Set<String> sourceConflicts = conflictingTargets.getOrDefault(
                    response.sourceCandidateId(), Set.of()
            );
            boolean conflict = !sourceConflicts.isEmpty();
            List<String> errors = new ArrayList<>(response.errors());
            if (conflict && errors.stream().noneMatch(message -> message.contains("目标文件名冲突"))) {
                errors.add("同季来源之间存在最终文件名冲突");
            }
            List<QuarkRenamePreviewResponse> files = response.files().stream()
                    .map(file -> sourceConflicts.contains(file.targetName().toLowerCase(Locale.ROOT))
                            ? new QuarkRenamePreviewResponse(
                                    file.fileId(), file.sourceName(), file.targetName(), file.episodeNumber(), "CONFLICT",
                                    "与同季其他来源的目标文件名重复"
                            )
                            : file)
                    .toList();
            return new QuarkSourcePlanResponse(
                    response.sourceCandidateId(), response.sourceName(), response.relativePath(), response.sourceKind(),
                    response.detectedSeason(), response.seasonStatus(), response.selectedSeason(), response.ignored(),
                    response.followUpdates(), planned.task().savePath(), planned.task().taskName(),
                    conflict ? "BLOCKED" : response.status(), files, errors, response.warnings()
            );
        }).toList();
    }

    private void applyTaskNames(List<PlannedSource> tasks, String title) {
        Map<Integer, List<PlannedSource>> bySeason = new LinkedHashMap<>();
        tasks.forEach(task -> bySeason.computeIfAbsent(task.seasonNumber(), ignored -> new ArrayList<>()).add(task));
        for (List<PlannedSource> sameSeason : bySeason.values()) {
            boolean multiple = sameSeason.size() > 1;
            Map<String, Integer> labelCounts = sameSeason.stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            item -> cleanTaskText(item.candidate().sourceName()).toLowerCase(Locale.ROOT),
                            LinkedHashMap::new,
                            java.util.stream.Collectors.summingInt(ignored -> 1)
                    ));
            for (PlannedSource planned : sameSeason) {
                String label = cleanTaskText(planned.candidate().sourceName());
                String suffix = labelCounts.getOrDefault(label.toLowerCase(Locale.ROOT), 0) > 1
                        ? label + " - " + cleanTaskText(planned.candidate().relativePath())
                        : label;
                String taskName = title + " S" + String.format(Locale.ROOT, "%02d", planned.seasonNumber())
                        + (multiple ? " [" + suffix + "]" : "");
                if (StringUtils.hasText(planned.task().renameRule())
                        && planned.task().renameRule().startsWith("手动指定")) {
                    taskName += " [" + planned.task().renameRule() + "]";
                }
                planned.setTask(withTaskName(planned.task(), taskName));
            }
        }
    }

    private QasTaskPlan withTaskName(QasTaskPlan task, String taskName) {
        return new QasTaskPlan(
                cleanTaskText(taskName), task.sourceUrl(), task.savePath(), task.pattern(), task.replace(),
                task.versionLabel(), task.renameRule(), task.matchedFileCount(), task.renameSamples()
        );
    }

    private String planFingerprint(
            List<PlannedSource> tasks,
            List<QuarkSourcePlanResponse> responses,
            boolean followUpdatesEnabled
    ) {
        StringBuilder value = new StringBuilder(Boolean.toString(followUpdatesEnabled));
        tasks.forEach(task -> {
            value.append('|').append(task.candidate().id()).append('|').append(task.seasonNumber())
                    .append('|').append(task.task().taskName()).append('|').append(task.task().pattern())
                    .append('|').append(task.task().replace()).append('|').append(task.followUpdates());
            renamePreview(task.candidate(), task.task()).forEach(file -> value.append('|')
                    .append(file.status()).append('|').append(file.sourceName()).append('>').append(file.targetName()));
        });
        responses.stream().filter(response -> !"READY".equals(response.status()) && !"IGNORED".equals(response.status()))
                .forEach(response -> value.append('|').append(response.sourceCandidateId()).append('|').append(response.status()));
        return Integer.toHexString(value.toString().hashCode()) + Integer.toHexString(value.toString().hashCode() * 31);
    }

    private QuarkSourcePlanResponse sourceResponse(
            QuarkShareSourceRegistry.SourceCandidate candidate,
            QuarkSourceSelectionRequest selection,
            Integer season,
            String status,
            List<String> errors,
            List<String> warnings
    ) {
        return sourceResponse(candidate, selection, season, status, errors, warnings, List.of(), null);
    }

    private QuarkSourcePlanResponse sourceResponse(
            QuarkShareSourceRegistry.SourceCandidate candidate,
            QuarkSourceSelectionRequest selection,
            Integer season,
            String status,
            List<String> errors,
            List<String> warnings,
            List<QuarkRenamePreviewResponse> files,
            QasTaskPlan task
    ) {
        return new QuarkSourcePlanResponse(
                candidate.id(), candidate.sourceName(), candidate.relativePath(), candidate.kind(),
                candidate.detectedSeason(), candidate.seasonStatus(), season,
                selection != null && selection.ignored(),
                selection != null && selection.followUpdates(),
                task == null ? null : task.savePath(),
                task == null ? null : task.taskName(),
                status,
                files,
                errors,
                warnings
        );
    }

    private QuarkSourceTreeNodeResponse treeNode(
            QasShareNode node,
            QuarkShareSourceRegistry.PreviewSession session,
            String parentPath
    ) {
        String path = parentPath.isBlank() ? node.name() : parentPath + "/" + node.name();
        QuarkShareSourceRegistry.SourceCandidate candidate = session.candidates().values().stream()
                .filter(item -> !item.fidPath().isEmpty())
                .filter(item -> item.fidPath().get(item.fidPath().size() - 1).equals(node.fid()))
                .filter(item -> item.relativePath().equals(path))
                .findFirst()
                .orElse(null);
        List<QuarkSourceTreeNodeResponse> children = node.children().stream()
                .map(child -> treeNode(child, session, path))
                .toList();
        return new QuarkSourceTreeNodeResponse(
                node.name(), node.directory(), node.size(),
                candidate == null ? null : candidate.id(),
                candidate == null ? null : candidate.kind(),
                path,
                candidate == null ? null : candidate.detectedSeason(),
                candidate == null ? null : candidate.seasonStatus(),
                children
        );
    }

    private void checkDuplicateTasks(List<PlannedSource> plannedTasks) {
        List<com.medianexus.orchestrator.integration.qas.QasExistingTask> existing;
        try {
            existing = qasClient.listTasks();
        } catch (QasClientException exception) {
            throw badRequest("无法安全检查 QAS 现有任务，已阻止提交：" + safeMessage(exception.getMessage()));
        }
        for (PlannedSource planned : plannedTasks) {
            boolean duplicate = existing.stream().anyMatch(task ->
                    planned.task().taskName().equals(task.taskName())
                            && planned.task().sourceUrl().equals(task.shareUrl()));
            if (duplicate) {
                throw badRequest("QAS 中已存在同名同来源任务：" + planned.task().taskName());
            }
        }
    }

    private void createLocalRecord(User user, String id, String mediaType, String title, Computation computation) {
        QuarkIngestTask task = new QuarkIngestTask();
        task.setId(id);
        task.setCreatedByUserId(user.getId());
        task.setMediaType(mediaType);
        task.setTitle(title.trim());
        task.setStatus("PLANNED");
        task.setStage("planning");
        task.setTaskNames(String.join(", ", computation.tasks().stream().map(item -> item.task().taskName()).toList()));
        task.setSavePath(rootPath(mediaType));
        task.setImmediateExecutionStarted(false);
        task.setCreatedTaskCount(0);
        task.setPlannedTaskCount(computation.tasks().size());
        task.setMessage("QAS 多来源入库计划已生成");
        LocalDateTime now = LocalDateTime.now();
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        taskMapper.insert(task);
        writeLog(id, "INFO", "planning", "分享树和逐文件改名预览完成", null);
        for (PlannedSource source : computation.tasks()) {
            QasTaskPlan planned = source.task();
            String rule = StringUtils.hasText(planned.renameRule()) ? planned.renameRule() : "自动识别";
            writeLog(id, "INFO", "planning", "已生成重命名计划",
                    "任务：" + planned.taskName() + "；规则：" + rule
                            + "；匹配文件：" + planned.matchedFileCount()
                            + "；调度：" + (source.followUpdates() ? "每日订阅" : "一次性"));
        }
        for (QuarkSourcePlanResponse source : computation.sources()) {
            List<QuarkRenamePreviewResponse> previews = source.files();
            previews.stream().limit(20).forEach(file -> writeLog(
                    id,
                    "UNRECOGNIZED".equals(file.status()) || "CONFLICT".equals(file.status()) ? "WARN" : "INFO",
                    "rename_preview",
                    "IGNORED".equals(file.status()) ? "文件已忽略" : "改名预览",
                    file.sourceName() + " → " + file.targetName()
                            + (StringUtils.hasText(file.message()) ? "（" + file.message() + "）" : "")
            ));
            if (previews.size() > 20) {
                writeLog(id, "INFO", "rename_preview", "改名预览已截断",
                        "仅展示前 20/" + previews.size() + " 个文件");
            }
        }
    }

    private void updateLocalRecord(String id, String status, boolean triggered, int created, int planned, String message) {
        QuarkIngestTask task = new QuarkIngestTask();
        task.setId(id);
        task.setStatus(status);
        task.setStage(triggered ? "submitted" : "scheduled");
        task.setImmediateExecutionStarted(triggered);
        task.setCreatedTaskCount(created);
        task.setPlannedTaskCount(planned);
        task.setMessage(message);
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
    }

    private QasExecutionObserver executionObserver(String taskId) {
        return new QasExecutionObserver() {
            @Override public void onOutput(String level, String message) {
                writeLog(taskId, level, "qas_running", message, null);
            }
            @Override public void onCompleted() {
                writeLog(taskId, "INFO", "execution_ended", "QAS 即时执行输出已结束", null);
            }
            @Override public void onInterrupted() {
                writeLog(taskId, "WARN", "execution_stream_interrupted", "QAS 即时执行输出意外中断", null);
            }
        };
    }

    private void writeLog(String taskId, String level, String stage, String message, String detail) {
        QuarkIngestTaskLog entry = new QuarkIngestTaskLog();
        entry.setTaskId(taskId);
        entry.setLevel(level);
        entry.setStage(stage);
        entry.setMessage(message == null ? "" : message.length() > 1024 ? message.substring(0, 1023) + "…" : message);
        entry.setDetail(detail);
        entry.setCreatedAt(LocalDateTime.now());
        taskLogMapper.insert(entry);
    }

    private QasShareTree inspect(String shareUrl) {
        try {
            return shareTreeService.inspectShare(shareUrl);
        } catch (QasShareInspectionException exception) {
            throw new BusinessException(ErrorCode.BAD_GATEWAY, "夸克分享检查失败：" + safeMessage(exception.getMessage()), HttpStatus.BAD_GATEWAY);
        } catch (QasClientException exception) {
            throw new BusinessException(ErrorCode.BAD_GATEWAY, "夸克分享检查失败：" + safeMessage(exception.getMessage()), HttpStatus.BAD_GATEWAY);
        }
    }

    private void ensureTreeUnchanged(QuarkShareSourceRegistry.PreviewSession session, QasShareTree freshTree) {
        if (!session.treeFingerprint().equals(QuarkShareSourceRegistry.fingerprint(freshTree))) {
            throw badRequest("分享目录或文件已变化，请刷新预览");
        }
    }

    private void validateSession(QuarkMultiSourceRequest request, String mediaType, QuarkShareSourceRegistry.PreviewSession session) {
        if (!session.shareUrl().equals(request.shareUrl()) || !session.mediaType().equals(mediaType)) {
            throw badRequest("预览候选与当前分享不匹配，请刷新预览");
        }
    }

    private void validateRequest(QuarkMultiSourceRequest request, String mediaType) {
        if (request == null || !StringUtils.hasText(request.shareUrl()) || !StringUtils.hasText(request.title())) {
            throw badRequest("分享链接和标题不能为空");
        }
        if (!StringUtils.hasText(cleanPathSegment(request.title()))) {
            throw badRequest("标题不能只包含目录非法字符");
        }
        validateShareUrl(request.shareUrl());
        if (!"SERIES".equals(mediaType) && !"VARIETY".equals(mediaType)) {
            throw badRequest("多来源季度规划仅支持电视剧或综艺");
        }
    }

    private void validateShareUrl(String value) {
        try {
            URI uri = new URI(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"pan.quark.cn".equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null || !SHARE_PATH.matcher(uri.getPath() == null ? "" : uri.getPath()).matches()) {
                throw badRequest("请输入合法的 pan.quark.cn 分享链接");
            }
        } catch (URISyntaxException exception) {
            throw badRequest("请输入合法的 pan.quark.cn 分享链接");
        }
    }

    private String rootPath(String mediaType) {
        return configuredRoot("VARIETY".equals(mediaType) ? qasProperties.getVarietyRootPath() : qasProperties.getTvRootPath());
    }

    private String configuredRoot(String value) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "QAS 保存路径尚未配置", HttpStatus.SERVICE_UNAVAILABLE);
        }
        return value.trim().replaceAll("/{2,}", "/").replaceAll("/+$", "");
    }

    private String joinPath(String parent, String child) {
        return (parent + "/" + child).replaceAll("/{2,}", "/");
    }

    private String cleanPathSegment(String value) {
        return cleanTaskText(value).replaceAll("\\s+", " ").trim();
    }

    private String cleanTaskText(String value) {
        String result = ILLEGAL_TASK_CHARACTER.matcher(value == null ? "" : value).replaceAll(" ");
        return result.replaceAll("\\s+", " ").trim();
    }

    private Map<LocalDate, List<Integer>> loadAirDateEpisodes(int tmdbId, int season) {
        JsonNode details;
        try {
            details = tmdbClient.getTvSeasonDetails(tmdbId, season,
                    StringUtils.hasText(tmdbProperties.getDefaultLanguage()) ? tmdbProperties.getDefaultLanguage().trim() : "zh-CN");
        } catch (TmdbClientException exception) {
            throw new BusinessException(ErrorCode.BAD_GATEWAY, "TMDB 季度详情获取失败：" + safeMessage(exception.getMessage()), HttpStatus.BAD_GATEWAY);
        }
        JsonNode episodes = details.path("episodes");
        if (!episodes.isArray()) {
            throw new BusinessException(ErrorCode.BAD_GATEWAY, "TMDB 季度详情缺少 episodes", HttpStatus.BAD_GATEWAY);
        }
        Map<LocalDate, List<Integer>> result = new LinkedHashMap<>();
        for (JsonNode episode : episodes) {
            int number = episode.path("episode_number").asInt(0);
            String airDate = episode.path("air_date").asText("");
            if (number <= 0 || !StringUtils.hasText(airDate)) {
                continue;
            }
            try {
                result.computeIfAbsent(LocalDate.parse(airDate), ignored -> new ArrayList<>()).add(number);
            } catch (DateTimeParseException ignored) {
                // Invalid upstream dates cannot participate in safe mapping.
            }
        }
        return Map.copyOf(result);
    }

    private BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, HttpStatus.BAD_REQUEST);
    }

    private String safeMessage(String message) {
        return StringUtils.hasText(message) ? message : "上游服务未返回错误原因";
    }

    private static final class PlannedSource {
        private final QuarkShareSourceRegistry.SourceCandidate candidate;
        private final boolean followUpdates;
        private QasTaskPlan task;
        private final int seasonNumber;

        private PlannedSource(
                QuarkShareSourceRegistry.SourceCandidate candidate,
                boolean followUpdates,
                QasTaskPlan task,
                int seasonNumber
        ) {
            this.candidate = candidate;
            this.followUpdates = followUpdates;
            this.task = task;
            this.seasonNumber = seasonNumber;
        }

        private QuarkShareSourceRegistry.SourceCandidate candidate() { return candidate; }
        private boolean followUpdates() { return followUpdates; }
        private QasTaskPlan task() { return task; }
        private int seasonNumber() { return seasonNumber; }
        private void setTask(QasTaskPlan replacement) { task = replacement; }
    }

    private record FileDecisions(
            Map<String, Integer> manualEpisodes,
            Set<String> ignoredFileIds,
            Set<String> excludedNames
    ) {
        private static FileDecisions empty() {
            return new FileDecisions(Map.of(), Set.of(), Set.of());
        }
    }

    private record Computation(
            boolean ready,
            List<PlannedSource> tasks,
            List<QuarkSourcePlanResponse> sources,
            List<QuarkSeasonCoverageResponse> seasonCoverages,
            List<String> warnings,
            String message,
            String planFingerprint
    ) {
    }

    private static final class CoverageAccumulator {
        private final int seasonNumber;
        private final Set<Integer> episodeNumbers = new HashSet<>();
        private int videoCount;
        private int unknownVideoCount;
        private int ignoredVideoCount;

        private CoverageAccumulator(int seasonNumber) {
            this.seasonNumber = seasonNumber;
        }

        private QuarkSeasonCoverageResponse toResponse(TmdbEpisodeCoverage tmdb) {
            List<Integer> recognized = episodeNumbers.stream().sorted().toList();
            if (tmdb == null || tmdb.errorMessage() != null) {
                return new QuarkSeasonCoverageResponse(
                        seasonNumber, videoCount, recognized.size(), null, null,
                        List.of(), List.of(), unknownVideoCount, ignoredVideoCount, List.of(),
                        "UNAVAILABLE", tmdb == null ? "暂无法取得 TMDB 数据" : tmdb.errorMessage()
                );
            }
            List<Integer> missing = tmdb.airedEpisodes().stream()
                    .filter(episode -> !episodeNumbers.contains(episode)).sorted().toList();
            List<Integer> extra = episodeNumbers.stream()
                    .filter(episode -> !tmdb.allEpisodes().contains(episode)).sorted().toList();
            String status = unknownVideoCount > 0
                    ? "NEEDS_REVIEW" : missing.isEmpty() ? "COMPLETE" : "MISSING";
            String message = unknownVideoCount > 0
                    ? "存在无法识别的视频，覆盖情况需要确认"
                    : missing.isEmpty() ? "已覆盖 TMDB 当前已播集数" : "存在缺集，仅提醒且不阻止入库";
            return new QuarkSeasonCoverageResponse(
                    seasonNumber, videoCount, recognized.size(), tmdb.allEpisodes().size(),
                    tmdb.airedEpisodes().size(), missing, extra, unknownVideoCount, ignoredVideoCount,
                    tmdb.unknownAirDates().stream().sorted().toList(), status, message
            );
        }
    }

    private record TmdbEpisodeCoverage(
            Set<Integer> allEpisodes,
            Set<Integer> airedEpisodes,
            Set<Integer> unknownAirDates,
            String errorMessage
    ) {
        private static TmdbEpisodeCoverage unavailable(String message) {
            return new TmdbEpisodeCoverage(Set.of(), Set.of(), Set.of(), message);
        }
    }

}
