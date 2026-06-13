package com.medianexus.orchestrator.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.config.OpenListProperties;
import com.medianexus.orchestrator.dto.magnet.request.AnimeMagnetIngestTaskCreateRequest;
import com.medianexus.orchestrator.dto.magnet.response.AnimeMagnetIngestTaskListResponse;
import com.medianexus.orchestrator.dto.magnet.response.AnimeMagnetIngestTaskLogListResponse;
import com.medianexus.orchestrator.dto.magnet.response.AnimeMagnetIngestTaskLogResponse;
import com.medianexus.orchestrator.dto.magnet.response.AnimeMagnetIngestTaskResponse;
import com.medianexus.orchestrator.integration.anirss.AniRssClient;
import com.medianexus.orchestrator.integration.anirss.AniRssClientException;
import com.medianexus.orchestrator.integration.openlist.OpenListClient;
import com.medianexus.orchestrator.integration.openlist.OpenListClientException;
import com.medianexus.orchestrator.integration.openlist.OpenListFileInfo;
import com.medianexus.orchestrator.integration.openlist.OpenListOfflineTaskInfo;
import com.medianexus.orchestrator.mapper.AnimeMagnetIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.AnimeMagnetIngestTaskMapper;
import com.medianexus.orchestrator.model.AnimeMagnetIngestTask;
import com.medianexus.orchestrator.model.AnimeMagnetIngestTaskLog;
import com.medianexus.orchestrator.model.User;
import com.medianexus.orchestrator.model.UserActionType;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AnimeMagnetIngestTaskService {

    private static final Logger log = LoggerFactory.getLogger(AnimeMagnetIngestTaskService.class);
    private static final Pattern MAGNET_HASH_PATTERN = Pattern.compile("xt=urn:btih:([a-zA-Z0-9]+)", Pattern.CASE_INSENSITIVE);
    private static final List<String> ACTIVE_STATUSES = List.of("PENDING", "SUBMITTED", "DOWNLOADING", "ORGANIZING");
    private static final List<String> UNFINISHED_STATUSES = List.of("PENDING", "SUBMITTED", "DOWNLOADING", "ORGANIZING");
    private static final int DEFAULT_OFFSET = 0;
    private static final Duration SAVING_FILES_VISIBLE_GRACE = Duration.ofMinutes(5);
    private static final String ADMIN_ROLE = "ADMIN";
    private static final String TMDB_NAME_REQUIRED_MESSAGE = "未解析到 TMDB 标题，无法确定媒体根目录";

    private final AnimeMagnetIngestTaskMapper taskMapper;
    private final AnimeMagnetIngestTaskLogMapper taskLogMapper;
    private final OpenListClient openListClient;
    private final OpenListProperties openListProperties;
    private final AniRssClient aniRssClient;
    private final AnimeEpisodeRenameService renameService;
    private final AuthService authService;
    private final UserActionQuotaService userActionQuotaService;
    private final ExecutorService executorService;

    public AnimeMagnetIngestTaskService(
            AnimeMagnetIngestTaskMapper taskMapper,
            AnimeMagnetIngestTaskLogMapper taskLogMapper,
            OpenListClient openListClient,
            OpenListProperties openListProperties,
            AniRssClient aniRssClient,
            AnimeEpisodeRenameService renameService,
            AuthService authService,
            UserActionQuotaService userActionQuotaService
    ) {
        this.taskMapper = taskMapper;
        this.taskLogMapper = taskLogMapper;
        this.openListClient = openListClient;
        this.openListProperties = openListProperties;
        this.aniRssClient = aniRssClient;
        this.renameService = renameService;
        this.authService = authService;
        this.userActionQuotaService = userActionQuotaService;
        this.executorService = Executors.newSingleThreadExecutor(new WorkerThreadFactory());
    }

    /**
     * 服务重启后中断未完成任务。
     *
     * 当前任务执行器是单进程内存队列，重启后无法恢复已提交到 worker 的控制流；
     * 因此所有非终态任务必须显式标记为 INTERRUPTED，避免前端长期显示运行中。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void markUnfinishedTasksInterrupted() {
        LambdaUpdateWrapper<AnimeMagnetIngestTask> updateWrapper = new LambdaUpdateWrapper<AnimeMagnetIngestTask>()
                .in(AnimeMagnetIngestTask::getStatus, UNFINISHED_STATUSES)
                .set(AnimeMagnetIngestTask::getStatus, "INTERRUPTED")
                .set(AnimeMagnetIngestTask::getStage, "interrupted")
                .set(AnimeMagnetIngestTask::getErrorMessage, "服务重启，任务已中断")
                .set(AnimeMagnetIngestTask::getFinishedAt, LocalDateTime.now());
        int interruptedCount = taskMapper.update(updateWrapper);
        if (interruptedCount > 0) {
            log.info("Marked unfinished anime magnet ingest tasks interrupted count={}", interruptedCount);
        }
    }

    /**
     * 创建整季 magnet 导入任务并异步提交执行。
     *
     * 同一 btih hash 在 PENDING/SUBMITTED/DOWNLOADING/ORGANIZING 中只能存在一个活跃任务；
     * 命中时返回已有任务而不是重复提交 OpenList 离线下载。
     */
    public AnimeMagnetIngestTaskResponse createTask(AnimeMagnetIngestTaskCreateRequest request) {
        User user = authService.requireCurrentUser();
        validateCreateRequest(request);
        String magnet = request.magnet().trim();
        String magnetHash = extractMagnetHash(magnet);

        AnimeMagnetIngestTask activeTask = taskMapper.selectOne(new LambdaQueryWrapper<AnimeMagnetIngestTask>()
                .eq(AnimeMagnetIngestTask::getMagnetHash, magnetHash)
                .in(AnimeMagnetIngestTask::getStatus, ACTIVE_STATUSES)
                .last("LIMIT 1"));
        if (activeTask != null) {
            if (canAccessTask(user, activeTask)) {
                writeLog(activeTask.getId(), "INFO", activeTask.getStage(), "发现相同 magnet 正在处理，返回已有任务", null);
                log.info(
                        "Returned active anime magnet ingest task taskId={} userId={} magnetHash={}",
                        activeTask.getId(),
                        user.getId(),
                        magnetHash
                );
                return toResponse(activeTask);
            }
            log.warn(
                    "Rejected duplicate anime magnet ingest task userId={} magnetHash={} activeTaskId={}",
                    user.getId(),
                    magnetHash,
                    activeTask.getId()
            );
            throw new BusinessException(ErrorCode.BAD_REQUEST, "相同 magnet 正在处理中，请稍后再试");
        }

        String title = preferredTitle(request);
        Integer season = request.seasonNumber() == null ? 1 : request.seasonNumber();
        String themoviedbName = resolveThemoviedbName(request);
        userActionQuotaService.consumeDailyContentCreate(user, UserActionType.MAGNET_INGEST_CREATE);

        String savePath = renderAnimePath(title, themoviedbName, season);
        String taskId = UUID.randomUUID().toString();
        String tempPath = savePath;

        AnimeMagnetIngestTask task = new AnimeMagnetIngestTask();
        task.setId(taskId);
        task.setStatus("PENDING");
        task.setStage("created");
        task.setMagnet(magnet);
        task.setMagnetHash(magnetHash);
        task.setBgmId(request.bgmId().trim());
        task.setBgmUrl(trimToNull(request.bgmUrl()));
        task.setTitle(title);
        task.setNameCn(trimToNull(request.nameCn()));
        task.setName(trimToNull(request.name()));
        task.setSeasonNumber(season);
        task.setSavePath(savePath);
        task.setTempPath(tempPath);
        task.setCreatedByUserId(user.getId());
        task.setOrganizedCount(0);
        task.setSkippedCount(0);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(task.getCreatedAt());
        taskMapper.insert(task);

        writeLog(taskId, "INFO", "created", "已创建动漫整季磁力任务", "savePath=" + savePath);
        log.info(
                "Created anime magnet ingest task taskId={} userId={} bgmId={} magnetHash={} savePath={}",
                taskId,
                user.getId(),
                task.getBgmId(),
                magnetHash,
                savePath
        );
        if (!StringUtils.hasText(request.themoviedbName()) && StringUtils.hasText(themoviedbName)) {
            writeLog(taskId, "INFO", "created", "已通过 Ani-RSS 解析 TMDB 标题", themoviedbName);
        }

        executorService.submit(() -> runTask(taskId));
        return toResponse(getExistingTask(taskId));
    }

    /**
     * 返回最近创建的导入任务列表。
     *
     * 当前接口面向工作台概览，固定返回最近 20 条，后续需要分页时再扩大契约。
     */
    public AnimeMagnetIngestTaskListResponse listTasks() {
        User user = authService.requireCurrentUser();
        LambdaQueryWrapper<AnimeMagnetIngestTask> queryWrapper = new LambdaQueryWrapper<AnimeMagnetIngestTask>()
                .orderByDesc(AnimeMagnetIngestTask::getCreatedAt)
                .last("LIMIT 20");
        if (!isAdmin(user)) {
            queryWrapper.eq(AnimeMagnetIngestTask::getCreatedByUserId, user.getId());
        }

        List<AnimeMagnetIngestTaskResponse> items = taskMapper.selectList(queryWrapper)
                .stream()
                .map(this::toResponse)
                .toList();
        return new AnimeMagnetIngestTaskListResponse(items, items.size());
    }

    /**
     * 获取导入任务详情；任务不存在时返回 404 语义的业务错误。
     */
    public AnimeMagnetIngestTaskResponse getTask(String taskId) {
        User user = authService.requireCurrentUser();
        return toResponse(getAccessibleTask(taskId, user));
    }

    /**
     * 获取导入任务日志；先校验任务存在，避免无效 id 被解释成空日志。
     */
    public AnimeMagnetIngestTaskLogListResponse getTaskLogs(String taskId) {
        User user = authService.requireCurrentUser();
        getAccessibleTask(taskId, user);
        List<AnimeMagnetIngestTaskLogResponse> items = taskLogMapper.selectList(new LambdaQueryWrapper<AnimeMagnetIngestTaskLog>()
                        .eq(AnimeMagnetIngestTaskLog::getTaskId, taskId)
                        .orderByAsc(AnimeMagnetIngestTaskLog::getId))
                .stream()
                .map(this::toLogResponse)
                .toList();
        return new AnimeMagnetIngestTaskLogListResponse(items, items.size());
    }

    private void runTask(String taskId) {
        try {
            AnimeMagnetIngestTask task = getExistingTask(taskId);
            prepareDownloadRoot(task);

            markSubmitted(task);
            String openListTaskId = openListClient.addOfflineDownload(task.getTempPath(), task.getMagnet());
            markDownloading(taskId, openListTaskId);

            waitForOfflineTask(taskId, openListTaskId, task.getTempPath());

            markOrganizing(taskId, openListTaskId, task.getTempPath());
            OrganizeResult result = organizeFiles(getExistingTask(taskId));

            if (result.organizedCount() < 1) {
                markNoOrganizedFiles(taskId, openListTaskId, result);
                return;
            }

            markSucceeded(taskId, openListTaskId, result);
        } catch (Exception exception) {
            log.warn("Anime magnet ingest task failed id={}", taskId, exception);
            markFailed(taskId, safeMessage(exception));
        }
    }

    private void prepareDownloadRoot(AnimeMagnetIngestTask task) {
        writeLog(task.getId(), "INFO", "created", "正在准备 OpenList 保存目录", task.getSavePath());
        ensurePathReady(task.getId(), task.getSavePath());
        writeLog(task.getId(), "INFO", "created", "OpenList 保存目录准备完成", task.getSavePath());
    }

    private void markSubmitted(AnimeMagnetIngestTask task) {
        updateTask(task.getId(), "SUBMITTED", "submitted", null, null, null);
        writeLog(task.getId(), "INFO", "submitted", "正在提交 OpenList 离线下载", task.getTempPath());
    }

    private void markDownloading(String taskId, String openListTaskId) {
        updateTask(taskId, "DOWNLOADING", "downloading", openListTaskId, null, null);
        writeLog(taskId, "INFO", "downloading", "OpenList 离线任务已创建", openListTaskId);
    }

    private void markOrganizing(String taskId, String openListTaskId, String tempPath) {
        updateTask(taskId, "ORGANIZING", "organizing", openListTaskId, null, null);
        writeLog(taskId, "INFO", "organizing", "离线下载完成，开始整理文件", tempPath);
    }

    private void markNoOrganizedFiles(String taskId, String openListTaskId, OrganizeResult result) {
        updateTask(taskId, "FAILED", "failed", openListTaskId, result, "没有识别到可入库的视频文件");
        writeLog(taskId, "ERROR", "failed", "没有识别到可入库的视频文件", null);
    }

    private void markSucceeded(String taskId, String openListTaskId, OrganizeResult result) {
        updateTask(taskId, "SUCCEEDED", "succeeded", openListTaskId, result, null);
        writeLog(taskId, "INFO", "succeeded", "任务完成", "organized=" + result.organizedCount() + ", skipped=" + result.skippedCount());
        log.info(
                "Anime magnet ingest task succeeded taskId={} openListTaskId={} organized={} skipped={}",
                taskId,
                openListTaskId,
                result.organizedCount(),
                result.skippedCount()
        );
    }

    private void markFailed(String taskId, String errorMessage) {
        updateTask(taskId, "FAILED", "failed", null, null, errorMessage);
        writeLog(taskId, "ERROR", "failed", "任务失败", errorMessage);
    }

    /**
     * 等待 OpenList 离线任务进入可整理状态。
     *
     * OpenList 的 state=1 有时仍显示保存中但目标目录已经出现视频文件；超过宽限期后以
     * 文件可见性作为继续整理的信号。state>=5 也同理先检查文件，避免上游状态滞后导致
     * 已下载内容被误判失败。
     */
    private void waitForOfflineTask(String taskId, String openListTaskId, String tempPath) {
        Instant startedAt = Instant.now();
        int retryCount = 0;
        Duration timeout = openListProperties.getOfflineTimeout();
        Duration pollInterval = openListProperties.getPollInterval();

        while (Duration.between(startedAt, Instant.now()).compareTo(timeout) < 0) {
            OpenListOfflineTaskInfo taskInfo = openListClient.offlineTaskInfo(openListTaskId);
            Integer state = taskInfo.state();
            if (state != null && state == 2) {
                writeLog(taskId, "INFO", "downloading", "OpenList 离线下载完成", openListTaskId);
                safeDeleteOpenListTask(taskId, openListTaskId);
                return;
            }
            if (state != null && state == 1
                    && Duration.between(startedAt, Instant.now()).compareTo(SAVING_FILES_VISIBLE_GRACE) >= 0
                    && hasVideoFiles(tempPath)) {
                writeLog(taskId, "WARN", "downloading", "OpenList 仍显示保存中但临时目录已有视频文件，尝试继续整理", openListTaskId);
                return;
            }
            if (state != null && List.of(3, 4).contains(state)) {
                throw new OpenListClientException("OpenList 离线任务已取消");
            }
            if (state != null && state >= 5) {
                if (hasVideoFiles(tempPath)) {
                    writeLog(taskId, "WARN", "downloading", "OpenList 状态异常但发现视频文件，尝试继续整理", taskInfo.error());
                    safeDeleteOpenListTask(taskId, openListTaskId);
                    return;
                }
                if (retryCount >= Math.max(0, openListProperties.getRetryLimit())) {
                    throw new OpenListClientException("OpenList 离线任务失败: " + taskInfo.error());
                }
                retryCount++;
                writeLog(taskId, "WARN", "downloading", "OpenList 离线任务失败，正在重试", "retry=" + retryCount + ", error=" + taskInfo.error());
                openListClient.retryOfflineTask(openListTaskId);
            }
            sleep(pollInterval);
        }
        throw new OpenListClientException("OpenList 离线下载超时");
    }

    /**
     * 将临时目录中的可识别视频/字幕整理到 Season 目录。
     *
     * 目标文件名在一次整理计划内必须唯一；已存在或重复的文件会被跳过并删除，避免
     * 覆盖媒体库中已经整理好的剧集。
     */
    private OrganizeResult organizeFiles(AnimeMagnetIngestTask task) {
        writeLog(task.getId(), "INFO", "organizing", "正在扫描临时目录并生成整理计划", task.getTempPath());
        List<OpenListFileInfo> files = openListClient.findFiles(task.getTempPath());
        // 目标目录已有文件优先保留，重复导入或补集时不能覆盖媒体库中已整理的剧集。
        Set<String> targetNames = openListClient.listFiles(task.getSavePath()).stream()
                .map(OpenListFileInfo::name)
                .collect(HashSet::new, HashSet::add, HashSet::addAll);

        // OpenList 的重命名、移动和删除都依赖父目录参数，整理计划需要保留源目录上下文。
        Map<String, Map<String, String>> renameByDir = new HashMap<>();
        Map<String, List<String>> moveByDir = new HashMap<>();
        Map<String, List<String>> deleteByDir = new HashMap<>();
        int skipped = 0;
        int organized = 0;
        // 本次整理计划内的目标文件名必须唯一，避免同一批文件识别成同一集后互相覆盖。
        Set<String> plannedTargetNames = new HashSet<>();
        String savePath = openListClient.normalizePath(task.getSavePath());

        for (OpenListFileInfo file : files) {
            String filePath = openListClient.normalizePath(file.path());
            Optional<AnimeEpisodeRenameService.RenameResult> rename = renameService.rename(
                    file.name(),
                    task.getTitle(),
                    task.getSeasonNumber(),
                    DEFAULT_OFFSET
            );
            if (rename.isEmpty()) {
                skipped++;
                writeLog(task.getId(), "WARN", "organizing", "跳过无法识别的文件", file.path() + "/" + file.name());
                deleteByDir.computeIfAbsent(file.path(), ignored -> new ArrayList<>()).add(file.name());
                continue;
            }

            String targetName = rename.get().fileName();
            if (filePath.equals(savePath) && file.name().equals(targetName)) {
                plannedTargetNames.add(targetName);
                organized++;
                continue;
            }

            if (targetNames.contains(targetName) || plannedTargetNames.contains(targetName)) {
                skipped++;
                writeLog(task.getId(), "WARN", "organizing", "目标文件已存在或重复，删除当前文件", file.path() + "/" + file.name());
                deleteByDir.computeIfAbsent(file.path(), ignored -> new ArrayList<>()).add(file.name());
                continue;
            }

            if (!file.name().equals(targetName)) {
                renameByDir.computeIfAbsent(file.path(), ignored -> new HashMap<>()).put(file.name(), targetName);
            }
            if (!filePath.equals(savePath)) {
                moveByDir.computeIfAbsent(file.path(), ignored -> new ArrayList<>()).add(targetName);
            }
            plannedTargetNames.add(targetName);
            organized++;
        }

        int renameCount = countRenameOperations(renameByDir);
        int moveCount = countFileOperations(moveByDir);
        int deleteCount = countFileOperations(deleteByDir);
        writeLog(
                task.getId(),
                "INFO",
                "organizing",
                "整理计划已生成",
                "rename=" + renameCount + ", move=" + moveCount + ", delete=" + deleteCount
                        + ", organized=" + organized + ", skipped=" + skipped
        );

        for (Map.Entry<String, Map<String, String>> entry : renameByDir.entrySet()) {
            writeLog(
                    task.getId(),
                    "INFO",
                    "organizing",
                    "正在批量重命名文件",
                    sourceBatchDetail(entry.getKey(), entry.getValue().size())
            );
            openListClient.batchRename(entry.getKey(), entry.getValue());
            writeLog(
                    task.getId(),
                    "INFO",
                    "organizing",
                    "批量重命名完成",
                    sourceBatchDetail(entry.getKey(), entry.getValue().size())
            );
            for (Map.Entry<String, String> renameEntry : entry.getValue().entrySet()) {
                writeLog(task.getId(), "INFO", "organizing", "重命名文件", renameEntry.getKey() + " ==> " + renameEntry.getValue());
            }
        }
        for (Map.Entry<String, List<String>> entry : moveByDir.entrySet()) {
            writeLog(
                    task.getId(),
                    "INFO",
                    "organizing",
                    "正在批量移动文件到 Season 目录",
                    moveBatchDetail(entry.getKey(), task.getSavePath(), entry.getValue().size())
            );
            openListClient.move(entry.getKey(), task.getSavePath(), entry.getValue());
            writeLog(
                    task.getId(),
                    "INFO",
                    "organizing",
                    "批量移动完成",
                    moveBatchDetail(entry.getKey(), task.getSavePath(), entry.getValue().size())
            );
            for (String name : entry.getValue()) {
                writeLog(task.getId(), "INFO", "organizing", "移动文件到 Season 目录", name);
            }
        }
        for (Map.Entry<String, List<String>> entry : deleteByDir.entrySet()) {
            writeLog(
                    task.getId(),
                    "INFO",
                    "organizing",
                    "正在删除跳过文件",
                    sourceBatchDetail(entry.getKey(), entry.getValue().size())
            );
            openListClient.remove(entry.getKey(), entry.getValue());
            writeLog(
                    task.getId(),
                    "INFO",
                    "organizing",
                    "跳过文件删除完成",
                    sourceBatchDetail(entry.getKey(), entry.getValue().size())
            );
            for (String name : entry.getValue()) {
                writeLog(task.getId(), "INFO", "organizing", "删除跳过文件", entry.getKey() + "/" + name);
            }
        }
        writeLog(task.getId(), "INFO", "organizing", "正在清理空目录", savePath);
        cleanupEmptyDirectories(task.getId(), savePath, savePath);
        writeLog(task.getId(), "INFO", "organizing", "空目录清理完成", savePath);
        return new OrganizeResult(organized, skipped);
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

    private String moveBatchDetail(String sourceDir, String destinationDir, int count) {
        return "srcDir=" + sourceDir + ", dstDir=" + destinationDir + ", count=" + count;
    }

    private void cleanupEmptyDirectories(String taskId, String rootPath, String currentPath) {
        List<OpenListFileInfo> children;
        try {
            children = openListClient.listFiles(currentPath);
        } catch (RuntimeException exception) {
            writeLog(taskId, "WARN", "organizing", "扫描空目录失败，跳过清理", currentPath);
            return;
        }

        for (OpenListFileInfo child : children) {
            if (Boolean.TRUE.equals(child.isDir())) {
                cleanupEmptyDirectories(taskId, rootPath, openListClient.joinPath(currentPath, child.name()));
            }
        }

        if (openListClient.normalizePath(currentPath).equals(openListClient.normalizePath(rootPath))) {
            return;
        }

        try {
            if (openListClient.listFiles(currentPath).isEmpty()) {
                openListClient.remove(parentPath(currentPath), List.of(pathName(currentPath)));
                writeLog(taskId, "INFO", "organizing", "删除空目录", currentPath);
            }
        } catch (RuntimeException exception) {
            writeLog(taskId, "WARN", "organizing", "删除空目录失败", currentPath);
        }
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

    private boolean hasVideoFiles(String path) {
        return openListClient.findFiles(path).stream().anyMatch(file -> renameService.isVideo(file.name()));
    }

    private void safeDeleteOpenListTask(String taskId, String openListTaskId) {
        try {
            openListClient.deleteOfflineTask(openListTaskId);
        } catch (RuntimeException exception) {
            // 离线任务记录清理失败不影响文件整理结果，保留 WARN 日志供人工回收。
            writeLog(taskId, "WARN", "downloading", "删除 OpenList 离线任务记录失败，继续后续整理", null);
        }
    }

    private void ensurePathReady(String taskId, String path) {
        String normalized = openListClient.normalizePath(path);
        String current = "";
        boolean skippedStorageRoot = false;
        for (String part : normalized.split("/")) {
            if (!StringUtils.hasText(part)) {
                continue;
            }
            current = current + "/" + part;
            if (!skippedStorageRoot) {
                skippedStorageRoot = true;
                continue;
            }
            // OpenList 存储根由挂载配置提供，不能通过 mkdir 创建；只补齐根目录以下层级。
            writeLog(taskId, "INFO", "created", "正在确认 OpenList 目录层级", current);
            openListClient.mkdir(current);
        }
    }

    private AnimeMagnetIngestTask getExistingTask(String taskId) {
        AnimeMagnetIngestTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "任务不存在", HttpStatus.NOT_FOUND);
        }
        return task;
    }

    private AnimeMagnetIngestTask getAccessibleTask(String taskId, User user) {
        AnimeMagnetIngestTask task = getExistingTask(taskId);
        if (!canAccessTask(user, task)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "任务不存在", HttpStatus.NOT_FOUND);
        }
        return task;
    }

    private boolean canAccessTask(User user, AnimeMagnetIngestTask task) {
        if (isAdmin(user)) {
            return true;
        }
        return task.getCreatedByUserId() != null && task.getCreatedByUserId().equals(user.getId());
    }

    private boolean isAdmin(User user) {
        return user != null && ADMIN_ROLE.equalsIgnoreCase(user.getRole());
    }

    private void updateTask(
            String taskId,
            String status,
            String stage,
            String openListTaskId,
            OrganizeResult result,
            String errorMessage
    ) {
        LambdaUpdateWrapper<AnimeMagnetIngestTask> updateWrapper = new LambdaUpdateWrapper<AnimeMagnetIngestTask>()
                .eq(AnimeMagnetIngestTask::getId, taskId)
                .set(AnimeMagnetIngestTask::getStatus, status)
                .set(AnimeMagnetIngestTask::getStage, stage);
        if (openListTaskId != null) {
            updateWrapper.set(AnimeMagnetIngestTask::getOpenlistTaskId, openListTaskId);
        }
        if (result != null) {
            updateWrapper
                    .set(AnimeMagnetIngestTask::getOrganizedCount, result.organizedCount())
                    .set(AnimeMagnetIngestTask::getSkippedCount, result.skippedCount());
        }
        if (errorMessage != null) {
            updateWrapper.set(AnimeMagnetIngestTask::getErrorMessage, truncate(errorMessage, 1000));
        }
        if (List.of("SUCCEEDED", "PARTIAL_SUCCESS", "FAILED", "INTERRUPTED").contains(status)) {
            updateWrapper.set(AnimeMagnetIngestTask::getFinishedAt, LocalDateTime.now());
        }
        taskMapper.update(updateWrapper);
    }

    private void writeLog(String taskId, String level, String stage, String message, String detail) {
        AnimeMagnetIngestTaskLog taskLog = new AnimeMagnetIngestTaskLog();
        taskLog.setTaskId(taskId);
        taskLog.setLevel(level);
        taskLog.setStage(stage);
        taskLog.setMessage(message);
        taskLog.setDetail(detail);
        taskLogMapper.insert(taskLog);
    }

    private AnimeMagnetIngestTaskResponse toResponse(AnimeMagnetIngestTask task) {
        return new AnimeMagnetIngestTaskResponse(
                task.getId(),
                task.getCreatedByUserId(),
                task.getStatus(),
                task.getStage(),
                task.getBgmId(),
                task.getTitle(),
                task.getSeasonNumber(),
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

    private AnimeMagnetIngestTaskLogResponse toLogResponse(AnimeMagnetIngestTaskLog taskLog) {
        return new AnimeMagnetIngestTaskLogResponse(
                taskLog.getId(),
                taskLog.getTaskId(),
                taskLog.getLevel(),
                taskLog.getStage(),
                taskLog.getMessage(),
                taskLog.getDetail(),
                taskLog.getCreatedAt()
        );
    }

    private void validateCreateRequest(AnimeMagnetIngestTaskCreateRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请求不能为空");
        }
        if (!request.magnet().trim().toLowerCase(Locale.ROOT).startsWith("magnet:?")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请输入有效 magnet 链接");
        }
        extractMagnetHash(request.magnet());
        if (!StringUtils.hasText(preferredTitle(request))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "动漫标题不能为空");
        }
    }

    private String extractMagnetHash(String magnet) {
        Matcher matcher = MAGNET_HASH_PATTERN.matcher(magnet);
        if (!matcher.find()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "magnet 缺少 btih hash");
        }
        return matcher.group(1).toLowerCase(Locale.ROOT);
    }

    private String preferredTitle(AnimeMagnetIngestTaskCreateRequest request) {
        if (StringUtils.hasText(request.title())) {
            return request.title().trim();
        }
        if (StringUtils.hasText(request.nameCn())) {
            return request.nameCn().trim();
        }
        return StringUtils.hasText(request.name()) ? request.name().trim() : "";
    }

    private String resolveThemoviedbName(AnimeMagnetIngestTaskCreateRequest request) {
        if (StringUtils.hasText(request.themoviedbName())) {
            return request.themoviedbName().trim();
        }
        if (!requiresThemoviedbName(openListProperties.getAnimePathTemplate())) {
            return "";
        }

        try {
            JsonNode ani = aniRssClient.getAniBySubjectId(request.bgmId().trim());
            String themoviedbName = ani == null ? null : trimToNull(ani.path("themoviedbName").asText(""));
            if (StringUtils.hasText(themoviedbName)) {
                return themoviedbName;
            }
            log.warn("Ani-RSS did not resolve TMDB title for bgmId={}", request.bgmId());
        } catch (AniRssClientException exception) {
            log.warn("Ani-RSS TMDB title resolve failed for bgmId={}: {}",
                    request.bgmId(), exception.getMessage(), exception);
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    TMDB_NAME_REQUIRED_MESSAGE,
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }

        throw new BusinessException(
                ErrorCode.INTERNAL_ERROR,
                TMDB_NAME_REQUIRED_MESSAGE,
                HttpStatus.INTERNAL_SERVER_ERROR
        );
    }

    private boolean requiresThemoviedbName(String template) {
        return StringUtils.hasText(template)
                && (template.contains("{themoviedbName}") || template.contains("${themoviedbName}"));
    }

    private String renderAnimePath(String title, String themoviedbName, Integer season) {
        String template = openListProperties.getAnimePathTemplate();
        String path = replaceTemplateValue(template, "themoviedbName", sanitizePathSegment(themoviedbName));
        path = replaceTemplateValue(path, "title", sanitizePathSegment(title));
        path = replaceTemplateValue(path, "season", String.valueOf(season));
        path = replaceTemplateValue(path, "seasonFormat", String.format("%02d", season));
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

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String replaceTemplateValue(String template, String key, String value) {
        return template
                .replace("{" + key + "}", value)
                .replace("${" + key + "}", value);
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

    private record OrganizeResult(int organizedCount, int skippedCount) {
    }

    private static class WorkerThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable);
            thread.setName("anime-magnet-ingest-worker");
            thread.setDaemon(true);
            return thread;
        }
    }
}
