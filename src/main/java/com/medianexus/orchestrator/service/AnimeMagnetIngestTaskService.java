package com.medianexus.orchestrator.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.config.OpenListProperties;
import com.medianexus.orchestrator.dto.magnet.request.AnimeMagnetIngestTaskCreateRequest;
import com.medianexus.orchestrator.dto.magnet.response.AnimeMagnetIngestTaskListResponse;
import com.medianexus.orchestrator.dto.magnet.response.AnimeMagnetIngestTaskLogListResponse;
import com.medianexus.orchestrator.dto.magnet.response.AnimeMagnetIngestTaskLogResponse;
import com.medianexus.orchestrator.dto.magnet.response.AnimeMagnetIngestTaskResponse;
import com.medianexus.orchestrator.integration.openlist.OpenListClient;
import com.medianexus.orchestrator.integration.openlist.OpenListClientException;
import com.medianexus.orchestrator.integration.openlist.OpenListFileInfo;
import com.medianexus.orchestrator.integration.openlist.OpenListOfflineTaskInfo;
import com.medianexus.orchestrator.mapper.AnimeMagnetIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.AnimeMagnetIngestTaskMapper;
import com.medianexus.orchestrator.model.AnimeMagnetIngestTask;
import com.medianexus.orchestrator.model.AnimeMagnetIngestTaskLog;
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

    private final AnimeMagnetIngestTaskMapper taskMapper;
    private final AnimeMagnetIngestTaskLogMapper taskLogMapper;
    private final OpenListClient openListClient;
    private final OpenListProperties openListProperties;
    private final AnimeEpisodeRenameService renameService;
    private final ExecutorService executorService;

    public AnimeMagnetIngestTaskService(
            AnimeMagnetIngestTaskMapper taskMapper,
            AnimeMagnetIngestTaskLogMapper taskLogMapper,
            OpenListClient openListClient,
            OpenListProperties openListProperties,
            AnimeEpisodeRenameService renameService
    ) {
        this.taskMapper = taskMapper;
        this.taskLogMapper = taskLogMapper;
        this.openListClient = openListClient;
        this.openListProperties = openListProperties;
        this.renameService = renameService;
        this.executorService = Executors.newSingleThreadExecutor(new WorkerThreadFactory());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void markUnfinishedTasksInterrupted() {
        LambdaUpdateWrapper<AnimeMagnetIngestTask> updateWrapper = new LambdaUpdateWrapper<AnimeMagnetIngestTask>()
                .in(AnimeMagnetIngestTask::getStatus, UNFINISHED_STATUSES)
                .set(AnimeMagnetIngestTask::getStatus, "INTERRUPTED")
                .set(AnimeMagnetIngestTask::getStage, "interrupted")
                .set(AnimeMagnetIngestTask::getErrorMessage, "服务重启，任务已中断")
                .set(AnimeMagnetIngestTask::getFinishedAt, LocalDateTime.now());
        taskMapper.update(updateWrapper);
    }

    public AnimeMagnetIngestTaskResponse createTask(AnimeMagnetIngestTaskCreateRequest request) {
        validateCreateRequest(request);
        String magnet = request.magnet().trim();
        String magnetHash = extractMagnetHash(magnet);

        AnimeMagnetIngestTask activeTask = taskMapper.selectOne(new LambdaQueryWrapper<AnimeMagnetIngestTask>()
                .eq(AnimeMagnetIngestTask::getMagnetHash, magnetHash)
                .in(AnimeMagnetIngestTask::getStatus, ACTIVE_STATUSES)
                .last("LIMIT 1"));
        if (activeTask != null) {
            writeLog(activeTask.getId(), "INFO", activeTask.getStage(), "发现相同 magnet 正在处理，返回已有任务", null);
            return toResponse(activeTask);
        }

        String title = preferredTitle(request);
        Integer season = request.seasonNumber() == null ? 1 : request.seasonNumber();
        String savePath = renderAnimePath(title, request.themoviedbName(), season);
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
        task.setOrganizedCount(0);
        task.setSkippedCount(0);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(task.getCreatedAt());
        taskMapper.insert(task);

        writeLog(taskId, "INFO", "created", "已创建动漫整季磁力任务", "savePath=" + savePath);
        if (!StringUtils.hasText(request.themoviedbName())) {
            writeLog(taskId, "WARN", "created", "未解析到 TMDB 标题，使用 Bangumi 标题渲染路径", title);
        }

        executorService.submit(() -> runTask(taskId));
        return toResponse(getExistingTask(taskId));
    }

    public AnimeMagnetIngestTaskListResponse listTasks() {
        List<AnimeMagnetIngestTaskResponse> items = taskMapper.selectList(new LambdaQueryWrapper<AnimeMagnetIngestTask>()
                        .orderByDesc(AnimeMagnetIngestTask::getCreatedAt)
                        .last("LIMIT 20"))
                .stream()
                .map(this::toResponse)
                .toList();
        return new AnimeMagnetIngestTaskListResponse(items, items.size());
    }

    public AnimeMagnetIngestTaskResponse getTask(String taskId) {
        return toResponse(getExistingTask(taskId));
    }

    public AnimeMagnetIngestTaskLogListResponse getTaskLogs(String taskId) {
        getExistingTask(taskId);
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
            ensurePathReady(task.getSavePath());
            ensurePathReady(task.getTempPath());

            updateTask(taskId, "SUBMITTED", "submitted", null, null, null);
            writeLog(taskId, "INFO", "submitted", "正在提交 OpenList 离线下载", task.getTempPath());
            String openListTaskId = openListClient.addOfflineDownload(task.getTempPath(), task.getMagnet());
            updateTask(taskId, "DOWNLOADING", "downloading", openListTaskId, null, null);
            writeLog(taskId, "INFO", "downloading", "OpenList 离线任务已创建", openListTaskId);

            waitForOfflineTask(taskId, openListTaskId, task.getTempPath());

            updateTask(taskId, "ORGANIZING", "organizing", openListTaskId, null, null);
            writeLog(taskId, "INFO", "organizing", "离线下载完成，开始整理文件", task.getTempPath());
            OrganizeResult result = organizeFiles(getExistingTask(taskId));

            if (result.organizedCount() < 1) {
                updateTask(taskId, "FAILED", "failed", openListTaskId, result, "没有识别到可入库的视频文件");
                writeLog(taskId, "ERROR", "failed", "没有识别到可入库的视频文件", null);
                return;
            }

            if (result.skippedCount() > 0) {
                updateTask(taskId, "PARTIAL_SUCCESS", "partial_success", openListTaskId, result, null);
                writeLog(taskId, "WARN", "partial_success", "任务部分完成，已删除跳过文件", "skipped=" + result.skippedCount());
                return;
            }

            updateTask(taskId, "SUCCEEDED", "succeeded", openListTaskId, result, null);
            writeLog(taskId, "INFO", "succeeded", "任务完成", "organized=" + result.organizedCount());
        } catch (Exception exception) {
            log.warn("Anime magnet ingest task failed id={}", taskId, exception);
            updateTask(taskId, "FAILED", "failed", null, null, safeMessage(exception));
            writeLog(taskId, "ERROR", "failed", "任务失败", safeMessage(exception));
        }
    }

    private void waitForOfflineTask(String taskId, String openListTaskId, String tempPath) {
        Instant startedAt = Instant.now();
        int retryCount = 0;
        Duration timeout = offlineTimeout();
        Duration pollInterval = pollInterval();

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

    private OrganizeResult organizeFiles(AnimeMagnetIngestTask task) {
        List<OpenListFileInfo> files = openListClient.findFiles(task.getTempPath());
        Set<String> targetNames = openListClient.listFiles(task.getSavePath()).stream()
                .map(OpenListFileInfo::name)
                .collect(HashSet::new, HashSet::add, HashSet::addAll);

        Map<String, Map<String, String>> renameByDir = new HashMap<>();
        Map<String, List<String>> moveByDir = new HashMap<>();
        Map<String, List<String>> deleteByDir = new HashMap<>();
        int skipped = 0;
        int organized = 0;
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
                writeLog(task.getId(), "INFO", "organizing", "重命名文件", file.name() + " ==> " + targetName);
            }
            if (!filePath.equals(savePath)) {
                moveByDir.computeIfAbsent(file.path(), ignored -> new ArrayList<>()).add(targetName);
            }
            plannedTargetNames.add(targetName);
            organized++;
        }

        for (Map.Entry<String, Map<String, String>> entry : renameByDir.entrySet()) {
            for (Map.Entry<String, String> renameEntry : entry.getValue().entrySet()) {
                openListClient.batchRename(entry.getKey(), Map.of(renameEntry.getKey(), renameEntry.getValue()));
            }
        }
        for (Map.Entry<String, List<String>> entry : moveByDir.entrySet()) {
            for (String name : entry.getValue()) {
                openListClient.move(entry.getKey(), task.getSavePath(), List.of(name));
                writeLog(task.getId(), "INFO", "organizing", "移动文件到 Season 目录", name);
            }
        }
        for (Map.Entry<String, List<String>> entry : deleteByDir.entrySet()) {
            for (String name : entry.getValue()) {
                openListClient.remove(entry.getKey(), List.of(name));
                writeLog(task.getId(), "INFO", "organizing", "删除跳过文件", entry.getKey() + "/" + name);
            }
        }
        cleanupEmptyDirectories(task.getId(), savePath, savePath);
        return new OrganizeResult(organized, skipped);
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
            writeLog(taskId, "WARN", "downloading", "删除 OpenList 离线任务记录失败，继续后续整理", null);
        }
    }

    private void ensurePathReady(String path) {
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
        if (!StringUtils.hasText(request.magnet()) || !request.magnet().trim().toLowerCase(Locale.ROOT).startsWith("magnet:?")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请输入有效 magnet 链接");
        }
        extractMagnetHash(request.magnet());
        if (!StringUtils.hasText(request.bgmId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Bangumi 条目不能为空");
        }
        if (!StringUtils.hasText(preferredTitle(request))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "动漫标题不能为空");
        }
        if (request.seasonNumber() != null && request.seasonNumber() < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "季数无效");
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

    private String renderAnimePath(String title, String themoviedbName, Integer season) {
        String template = StringUtils.hasText(openListProperties.getAnimePathTemplate())
                ? openListProperties.getAnimePathTemplate()
                : "/pikpak/Media/Anime/${themoviedbName}/Season ${season}";
        String mediaName = StringUtils.hasText(themoviedbName) ? themoviedbName.trim() : title;
        return openListClient.normalizePath(template
                .replace("${themoviedbName}", sanitizePathSegment(mediaName))
                .replace("${title}", sanitizePathSegment(title))
                .replace("${season}", String.valueOf(season))
                .replace("${seasonFormat}", String.format("%02d", season)));
    }

    private String sanitizePathSegment(String value) {
        return value.replaceAll("[\\\\/:*?\"<>|]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
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

    private Duration pollInterval() {
        Duration interval = openListProperties.getPollInterval();
        if (interval == null || interval.isZero() || interval.isNegative()) {
            return Duration.ofSeconds(30);
        }
        return interval;
    }

    private Duration offlineTimeout() {
        Duration timeout = openListProperties.getOfflineTimeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return Duration.ofMinutes(360);
        }
        return timeout;
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
