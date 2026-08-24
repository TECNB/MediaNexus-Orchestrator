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
import com.medianexus.orchestrator.integration.qas.QasShareInspectionException;
import com.medianexus.orchestrator.integration.qas.QasShareNode;
import com.medianexus.orchestrator.integration.qas.QasShareTree;
import com.medianexus.orchestrator.integration.qas.QasTaskCreateCommand;
import com.medianexus.orchestrator.integration.tmdb.TmdbClient;
import com.medianexus.orchestrator.integration.tmdb.TmdbClientException;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.Year;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class QuarkIngestService {

    private static final Logger log = LoggerFactory.getLogger(QuarkIngestService.class);
    private static final int FIRST_MOVIE_YEAR = 1888;
    private static final Pattern QUARK_SHARE_PATH = Pattern.compile(
            "^/s/[A-Za-z0-9]+(?:/[A-Fa-f0-9]{32})?/?$"
    );

    private final QasClient qasClient;
    private final QasProperties qasProperties;
    private final TmdbProperties tmdbProperties;
    private final MovieSeriesFileRenameService renameService;
    private final AuthService authService;
    private final QuarkIngestPlanner ingestPlanner;
    private final TmdbClient tmdbClient;

    public QuarkIngestService(
            QasClient qasClient,
            QasProperties qasProperties,
            TmdbProperties tmdbProperties,
            MovieSeriesFileRenameService renameService,
            AuthService authService,
            QuarkIngestPlanner ingestPlanner,
            TmdbClient tmdbClient
    ) {
        this.qasClient = qasClient;
        this.qasProperties = qasProperties;
        this.tmdbProperties = tmdbProperties;
        this.renameService = renameService;
        this.authService = authService;
        this.ingestPlanner = ingestPlanner;
        this.tmdbClient = tmdbClient;
    }

    public QuarkIngestTaskResponse ingestMovie(MovieQuarkIngestRequest request) {
        PreparedIngest prepared = prepareMovie(request, true);
        return createAndTrigger("MOVIE", prepared.plan());
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
        return createAndTrigger(mediaType, prepared.plan());
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
            QasShareTree tree = qasClient.inspectShare(shareUrl);
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
            shareTree = qasClient.inspectShare(shareUrl);
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
                        ? "分享结构检查完成，可以创建 QAS 任务"
                        : "分享结构检查完成，将创建 " + prepared.plan().tasks().size() + " 个 QAS 版本任务"
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
            throw badRequest("日期型多版本需要有效的 TMDB ID 才能映射集号");
        }
        Map<LocalDate, Integer> airDateEpisodes = loadAirDateEpisodes(request.tmdbId(), seasonNumber);
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
            Map<LocalDate, Integer> airDateEpisodes
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

    private Map<LocalDate, Integer> loadAirDateEpisodes(int tmdbId, int seasonNumber) {
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
        Map<LocalDate, Integer> result = new LinkedHashMap<>();
        Set<LocalDate> ambiguousDates = new HashSet<>();
        for (JsonNode episode : episodes) {
            int episodeNumber = episode.path("episode_number").asInt(0);
            String airDate = episode.path("air_date").asText("");
            if (episodeNumber <= 0 || !StringUtils.hasText(airDate)) {
                continue;
            }
            try {
                LocalDate date = LocalDate.parse(airDate);
                if (ambiguousDates.contains(date)) {
                    continue;
                }
                Integer previous = result.putIfAbsent(date, episodeNumber);
                if (previous != null && previous != episodeNumber) {
                    result.remove(date);
                    ambiguousDates.add(date);
                }
            } catch (DateTimeParseException ignored) {
                // Invalid dates cannot safely participate in an episode mapping.
            }
        }
        return Map.copyOf(result);
    }

    private QuarkIngestTaskResponse createAndTrigger(String mediaType, QasIngestPlan plan) {
        List<QasCreatedTask> createdTasks = new ArrayList<>();
        List<String> creationFailures = new ArrayList<>();
        QasClientException firstFailure = null;
        for (QasTaskPlan task : plan.tasks()) {
            try {
                createdTasks.add(qasClient.createTask(new QasTaskCreateCommand(
                        task.taskName(),
                        task.sourceUrl(),
                        task.savePath(),
                        task.pattern(),
                        task.replace()
                )));
            } catch (QasClientException exception) {
                if (firstFailure == null) {
                    firstFailure = exception;
                }
                creationFailures.add(task.taskName() + "：" + safeUpstreamMessage(exception));
            }
        }
        if (createdTasks.isEmpty()) {
            throw mapCreateFailure(firstFailure == null
                    ? new QasClientException(QasClientException.Reason.UPSTREAM, "QAS 未创建任何任务")
                    : firstFailure);
        }

        boolean triggered = false;
        String triggerFailure = null;
        try {
            qasClient.triggerTasksNow(createdTasks);
            triggered = true;
        } catch (QasClientException exception) {
            triggerFailure = safeUpstreamMessage(exception);
        }

        int plannedCount = plan.tasks().size();
        int createdCount = createdTasks.size();
        boolean partial = createdCount < plannedCount;
        String status = partial ? "PARTIAL" : triggered ? "STARTED" : "SCHEDULED";
        List<String> warnings = new ArrayList<>(plan.warnings());
        if (!creationFailures.isEmpty()) {
            warnings.addAll(creationFailures);
        }
        if (triggerFailure != null) {
            warnings.add("立即执行失败：" + triggerFailure + "；将由 QAS 定时任务继续处理");
        }
        String message = resultMessage(createdCount, plannedCount, partial, triggered, creationFailures, triggerFailure);
        String taskNames = String.join(", ", createdTasks.stream().map(QasCreatedTask::taskName).toList());
        String savePath = plan.tasks().get(0).savePath();
        log.info(
                "Created QAS ingest tasks mediaType={} createdCount={} plannedCount={} immediateStarted={}",
                mediaType,
                createdCount,
                plannedCount,
                triggered
        );
        return new QuarkIngestTaskResponse(
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

    private String resultMessage(
            int createdCount,
            int plannedCount,
            boolean partial,
            boolean triggered,
            List<String> failures,
            String triggerFailure
    ) {
        if (partial) {
            String base = "已创建 " + createdCount + "/" + plannedCount + " 个 QAS 版本任务";
            String detail = failures.isEmpty() ? "" : "；" + String.join("；", failures);
            return base + detail + (triggered ? "；已开始执行成功创建的任务" : "；等待 QAS 定时执行");
        }
        if (triggerFailure != null) {
            return "QAS 任务已创建，但立即执行失败：" + triggerFailure + "；将由定时任务继续处理";
        }
        return plannedCount == 1
                ? "QAS 任务已创建并开始执行"
                : "已创建 " + plannedCount + " 个 QAS 版本任务并开始执行";
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
