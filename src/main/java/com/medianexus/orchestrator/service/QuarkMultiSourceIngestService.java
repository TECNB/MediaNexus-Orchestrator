package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.config.QasProperties;
import com.medianexus.orchestrator.config.TmdbProperties;
import com.medianexus.orchestrator.dto.quark.request.QuarkMultiSourceRequest;
import com.medianexus.orchestrator.dto.quark.request.QuarkSourceSelectionRequest;
import com.medianexus.orchestrator.dto.quark.response.QuarkMultiSourcePreviewResponse;
import com.medianexus.orchestrator.dto.quark.response.QuarkMultiSourceTaskResponse;
import com.medianexus.orchestrator.dto.quark.response.QuarkRenamePreviewResponse;
import com.medianexus.orchestrator.dto.quark.response.QuarkSourcePlanResponse;
import com.medianexus.orchestrator.dto.quark.response.QuarkSourceTaskResultResponse;
import com.medianexus.orchestrator.dto.quark.response.QuarkSourceTreeNodeResponse;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private static final List<Integer> ALL_WEEKDAYS = List.of(1, 2, 3, 4, 5, 6, 7);

    private final QasClient qasClient;
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
                session.candidates().isEmpty()
                        ? "分享中没有可规划的视频来源"
                        : "分享目录检查完成，请为每个来源设置季度或忽略"
        );
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
        String message = createdCount < plannedCount
                ? "已创建 " + createdCount + "/" + plannedCount + " 个 QAS 任务"
                    + (triggered ? "并立即执行成功创建的任务" : "，但首次立即执行失败：" + triggerError)
                : triggered
                ? (hasSubscriptions ? "已创建 QAS 任务并开始执行；已勾选来源按每日订阅运行" : "已创建一次性 QAS 任务并开始执行")
                : "QAS 任务已创建，但首次立即执行失败：" + triggerError;
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
            QasIngestPlan planned;
            try {
                planned = planSource(request, mediaType, candidate, seasonNumber, savePath);
            } catch (QuarkIngestPlanningException exception) {
                String message = safeMessage(exception.getMessage());
                errors.add(candidate.sourceName() + "：" + message);
                sourceResponses.add(sourceResponse(candidate, selection, seasonNumber, "BLOCKED", List.of(message), List.of()));
                continue;
            }
            if (planned.tasks().size() != 1) {
                String message = "一个来源产生了多个重命名规则，无法安全创建单一 QAS 任务";
                errors.add(candidate.sourceName() + "：" + message);
                sourceResponses.add(sourceResponse(candidate, selection, seasonNumber, "BLOCKED", List.of(message), planned.warnings()));
                continue;
            }
            QasTaskPlan task = planned.tasks().get(0);
            if (!StringUtils.hasText(task.pattern()) || !StringUtils.hasText(task.replace())) {
                String message = "多季度手动规划不允许回退为空重命名规则，请修正无法识别的文件后再提交";
                errors.add(candidate.sourceName() + "：" + message);
                sourceResponses.add(sourceResponse(candidate, selection, seasonNumber, "BLOCKED", List.of(message), planned.warnings()));
                continue;
            }
            List<QuarkRenamePreviewResponse> files = renamePreview(candidate, task);
            List<String> sourceErrors = files.stream()
                    .filter(file -> "CONFLICT".equals(file.status()) || "UNRECOGNIZED".equals(file.status()))
                    .map(file -> StringUtils.hasText(file.message()) ? file.message() : file.sourceName())
                    .distinct()
                    .toList();
            if (!sourceErrors.isEmpty()) {
                errors.addAll(sourceErrors);
            }
            tasks.add(new PlannedSource(
                    candidate,
                    request.followUpdatesEnabled() && selection.followUpdates(),
                    task,
                    seasonNumber
            ));
            sourceResponses.add(sourceResponse(candidate, selection, seasonNumber,
                    sourceErrors.isEmpty() ? "READY" : "BLOCKED", sourceErrors, planned.warnings(), files, task));
        }

        applyTaskNames(tasks, request.title());
        Map<String, Set<String>> conflictingTargets = detectGlobalConflicts(tasks, errors);
        sourceResponses = updateSourceResponses(sourceResponses, tasks, conflictingTargets);
        boolean ready = errors.isEmpty() && !tasks.isEmpty();
        String planFingerprint = planFingerprint(tasks, sourceResponses, request.followUpdatesEnabled());
        String message = ready
                ? "改名预览完成，可以提交 QAS 任务"
                : errors.isEmpty()
                ? "没有可执行来源，请至少映射一个未忽略来源"
                : String.join("；", errors);
        return new Computation(
                ready,
                tasks,
                sourceResponses,
                List.of(),
                message,
                planFingerprint
        );
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
        List<QuarkRenamePreviewResponse> previews = new ArrayList<>(task.renameSamples().stream()
                .map(sample -> new QuarkRenamePreviewResponse(
                        sample.sourceName(),
                        sample.targetName(),
                        episodeNumber(sample.targetName()),
                        sample.sourceName().equals(sample.targetName()) ? "UNCHANGED" : "READY",
                        null
                ))
                .toList());
        Set<String> plannedNames = task.renameSamples().stream()
                .map(QasRenameSample::sourceName)
                .collect(java.util.stream.Collectors.toSet());
        candidate.entries().stream()
                .filter(entry -> !plannedNames.contains(entry.name()))
                .map(entry -> {
                    boolean unsafeVideo = isPlayableVideo(entry.name()) || isSubtitle(entry.name());
                    return new QuarkRenamePreviewResponse(
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

    private Map<String, Set<String>> detectGlobalConflicts(
            List<PlannedSource> tasks,
            List<String> errors
    ) {
        Map<String, String> targetOwners = new HashMap<>();
        Map<String, Set<String>> conflictingTargets = new HashMap<>();
        for (PlannedSource planned : tasks) {
            for (QuarkRenamePreviewResponse file : renamePreview(planned.candidate(), planned.task())) {
                if ("EXCLUDED".equals(file.status()) || "UNRECOGNIZED".equals(file.status())) {
                    continue;
                }
                String normalizedTarget = file.targetName().toLowerCase(Locale.ROOT);
                String key = planned.task().savePath().toLowerCase(Locale.ROOT) + "\u0000" + normalizedTarget;
                String previous = targetOwners.putIfAbsent(key, planned.candidate().id());
                if (previous != null && !previous.equals(planned.candidate().id())) {
                    String message = "同季目标文件名冲突：" + file.targetName();
                    errors.add(message);
                    conflictingTargets.computeIfAbsent(previous, ignored -> new HashSet<>()).add(normalizedTarget);
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
                                    file.sourceName(), file.targetName(), file.episodeNumber(), "CONFLICT",
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
            List<QuarkRenamePreviewResponse> previews = renamePreview(source.candidate(), planned);
            previews.stream().limit(20).forEach(file -> writeLog(
                    id,
                    "UNRECOGNIZED".equals(file.status()) || "CONFLICT".equals(file.status()) ? "WARN" : "INFO",
                    "rename_preview",
                    "改名预览",
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
            return qasClient.inspectShare(shareUrl);
        } catch (QasShareInspectionException exception) {
            throw new BusinessException(ErrorCode.BAD_GATEWAY, "QAS 分享检查失败：" + safeMessage(exception.getMessage()), HttpStatus.BAD_GATEWAY);
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

    private record Computation(
            boolean ready,
            List<PlannedSource> tasks,
            List<QuarkSourcePlanResponse> sources,
            List<String> warnings,
            String message,
            String planFingerprint
    ) {
    }

}
