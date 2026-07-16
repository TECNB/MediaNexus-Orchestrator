package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.config.OpenListProperties;
import com.medianexus.orchestrator.dto.magnet.request.AdultMagnetIngestTaskCreateRequest;
import com.medianexus.orchestrator.dto.magnet.response.AdultMagnetIngestTaskListResponse;
import com.medianexus.orchestrator.dto.magnet.response.AdultMagnetIngestTaskLogListResponse;
import com.medianexus.orchestrator.dto.magnet.response.AdultMagnetIngestTaskLogResponse;
import com.medianexus.orchestrator.dto.magnet.response.AdultMagnetIngestTaskResponse;
import com.medianexus.orchestrator.integration.openlist.OpenListClient;
import com.medianexus.orchestrator.integration.openlist.OpenListClientException;
import com.medianexus.orchestrator.integration.openlist.OpenListDirectoryPrepareException;
import com.medianexus.orchestrator.integration.openlist.OpenListFileInfo;
import com.medianexus.orchestrator.integration.openlist.OpenListLibraryOrganizer;
import com.medianexus.orchestrator.integration.openlist.OpenListOfflineTaskInfo;
import com.medianexus.orchestrator.mapper.AdultMagnetIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.AdultMagnetIngestTaskMapper;
import com.medianexus.orchestrator.model.AdultMagnetIngestTask;
import com.medianexus.orchestrator.model.AdultMagnetIngestTaskLog;
import com.medianexus.orchestrator.model.User;
import com.medianexus.orchestrator.service.organization.LibraryOrganizer;
import com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AdultMagnetIngestService {

    private static final Logger log = LoggerFactory.getLogger(AdultMagnetIngestService.class);
    private static final Pattern MAGNET_HASH_PATTERN =
            Pattern.compile("xt=urn:btih:([a-zA-Z0-9]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ED2K_FILE_HASH_PATTERN =
            Pattern.compile("^ed2k://\\|file\\|[^|]+\\|\\d+\\|([a-fA-F0-9]{32})\\|", Pattern.CASE_INSENSITIVE);
    private static final Pattern DUPLICATE_NAME_SUFFIX_PATTERN = Pattern.compile("\\(\\d+\\)$");
    private static final DateTimeFormatter DATE_FOLDER_FORMATTER = DateTimeFormatter.ofPattern("M.d");
    private static final List<String> UNFINISHED_STATUSES = List.of("PENDING", "SUBMITTED", "DOWNLOADING", "ORGANIZING");
    private static final List<String> TERMINAL_STATUSES = List.of("SUCCEEDED", "PARTIAL_SUCCESS", "FAILED", "INTERRUPTED");
    private static final long MIN_VIDEO_BYTES = 100L * 1024L * 1024L;
    private static final int MAX_MAGNETS_PER_BATCH = 50;
    private static final int ORGANIZE_PARALLELISM = 3;

    private final AdultMagnetIngestTaskMapper taskMapper;
    private final AdultMagnetIngestTaskLogMapper taskLogMapper;
    private final OpenListClient openListClient;
    private final OpenListProperties openListProperties;
    private final MovieSeriesFileRenameService renameService;
    private final LibraryOrganizer adultLibraryOrganizer;
    private final AuthService authService;
    private final AutoSymlinkRefreshService autoSymlinkRefreshService;
    private final ObjectMapper objectMapper;
    private final ExecutorService executorService;
    private final ExecutorService organizeExecutorService;
    private final Object countLock = new Object();
    private final Map<String, Object> promotionLocks = new ConcurrentHashMap<>();
    private volatile boolean tablesReady;

    @Autowired
    public AdultMagnetIngestService(
            AdultMagnetIngestTaskMapper taskMapper,
            AdultMagnetIngestTaskLogMapper taskLogMapper,
            OpenListClient openListClient,
            OpenListProperties openListProperties,
            MovieSeriesFileRenameService renameService,
            @Qualifier("adultLibraryOrganizer") LibraryOrganizer adultLibraryOrganizer,
            AuthService authService,
            AutoSymlinkRefreshService autoSymlinkRefreshService,
            ObjectMapper objectMapper
    ) {
        this.taskMapper = taskMapper;
        this.taskLogMapper = taskLogMapper;
        this.openListClient = openListClient;
        this.openListProperties = openListProperties;
        this.renameService = renameService;
        this.adultLibraryOrganizer = adultLibraryOrganizer;
        this.authService = authService;
        this.autoSymlinkRefreshService = autoSymlinkRefreshService;
        this.objectMapper = objectMapper;
        this.executorService = Executors.newSingleThreadExecutor(new AdultWorkerThreadFactory());
        this.organizeExecutorService = Executors.newFixedThreadPool(ORGANIZE_PARALLELISM, new AdultOrganizeThreadFactory());
    }

    AdultMagnetIngestService(
            AdultMagnetIngestTaskMapper taskMapper,
            AdultMagnetIngestTaskLogMapper taskLogMapper,
            OpenListClient openListClient,
            OpenListProperties openListProperties,
            MovieSeriesFileRenameService renameService,
            LibraryOrganizer adultLibraryOrganizer,
            AuthService authService,
            AutoSymlinkRefreshService autoSymlinkRefreshService,
            ObjectMapper objectMapper,
            ExecutorService executorService,
            ExecutorService organizeExecutorService
    ) {
        this.taskMapper = taskMapper;
        this.taskLogMapper = taskLogMapper;
        this.openListClient = openListClient;
        this.openListProperties = openListProperties;
        this.renameService = renameService;
        this.adultLibraryOrganizer = adultLibraryOrganizer;
        this.authService = authService;
        this.autoSymlinkRefreshService = autoSymlinkRefreshService;
        this.objectMapper = objectMapper;
        this.executorService = executorService;
        this.organizeExecutorService = organizeExecutorService;
    }

    AdultMagnetIngestService(
            AdultMagnetIngestTaskMapper taskMapper,
            AdultMagnetIngestTaskLogMapper taskLogMapper,
            OpenListClient openListClient,
            OpenListProperties openListProperties,
            MovieSeriesFileRenameService renameService,
            AuthService authService,
            AutoSymlinkRefreshService autoSymlinkRefreshService,
            ObjectMapper objectMapper
    ) {
        this(
                taskMapper,
                taskLogMapper,
                openListClient,
                openListProperties,
                renameService,
                new OpenListLibraryOrganizer(openListClient),
                authService,
                autoSymlinkRefreshService,
                objectMapper
        );
    }

    AdultMagnetIngestService(
            AdultMagnetIngestTaskMapper taskMapper,
            AdultMagnetIngestTaskLogMapper taskLogMapper,
            OpenListClient openListClient,
            OpenListProperties openListProperties,
            MovieSeriesFileRenameService renameService,
            AuthService authService,
            AutoSymlinkRefreshService autoSymlinkRefreshService,
            ObjectMapper objectMapper,
            ExecutorService executorService,
            ExecutorService organizeExecutorService
    ) {
        this(
                taskMapper,
                taskLogMapper,
                openListClient,
                openListProperties,
                renameService,
                new OpenListLibraryOrganizer(openListClient),
                authService,
                autoSymlinkRefreshService,
                objectMapper,
                executorService,
                organizeExecutorService
        );
    }

    @EventListener(ApplicationReadyEvent.class)
    public void markUnfinishedTasksInterrupted() {
        ensureTablesReady();
        int interruptedCount = taskMapper.update(new LambdaUpdateWrapper<AdultMagnetIngestTask>()
                .in(AdultMagnetIngestTask::getStatus, UNFINISHED_STATUSES)
                .set(AdultMagnetIngestTask::getStatus, "INTERRUPTED")
                .set(AdultMagnetIngestTask::getStage, "interrupted")
                .set(AdultMagnetIngestTask::getErrorMessage, "服务重启，Adult 任务已中断")
                .set(AdultMagnetIngestTask::getFinishedAt, LocalDateTime.now()));
        if (interruptedCount > 0) {
            log.info("Marked unfinished adult magnet ingest tasks interrupted count={}", interruptedCount);
        }
    }

    public AdultMagnetIngestTaskResponse createTask(AdultMagnetIngestTaskCreateRequest request) {
        return createTask(request, null);
    }

    public AdultMagnetIngestTaskResponse createRetryTask(
            String category,
            List<String> downloadLinks,
            TaskRetryReference retryReference
    ) {
        if (retryReference == null) {
            throw badRequest("重试来源不能为空");
        }
        return createTask(new AdultMagnetIngestTaskCreateRequest(category, downloadLinks), retryReference);
    }

    private AdultMagnetIngestTaskResponse createTask(
            AdultMagnetIngestTaskCreateRequest request,
            TaskRetryReference retryReference
    ) {
        ensureTablesReady();
        User admin = authService.requireAdminUser();
        String taskId = UUID.randomUUID().toString();
        AdultTaskPlan plan = buildTaskPlan(request, taskId);
        if (plan.items().isEmpty()) {
            throw badRequest("没有可提交的 Adult 下载链接");
        }

        LocalDateTime now = LocalDateTime.now();
        AdultMagnetIngestTask task = new AdultMagnetIngestTask();
        task.setId(taskId);
        task.setCreatedByUserId(admin.getId());
        task.setCategory(plan.category());
        task.setStatus("PENDING");
        task.setStage("created");
        task.setDateFolder(plan.dateFolder());
        task.setTargetPath(plan.targetPath());
        task.setMagnetHashes(String.join("\n", plan.items().stream().map(AdultMagnetItem::magnetHash).toList()));
        task.setDownloadLinksJson(serializeDownloadLinks(plan.items().stream().map(AdultMagnetItem::magnet).toList()));
        task.setOpenlistTaskIds("");
        task.setAttemptGroupId(retryReference == null ? taskId : retryReference.attemptGroupId());
        task.setRetryOfTaskType(retryReference == null ? null : retryReference.taskType());
        task.setRetryOfTaskId(retryReference == null ? null : retryReference.taskId());
        task.setMagnetCount(plan.items().size());
        task.setSubmittedCount(0);
        task.setSucceededCount(0);
        task.setFailedCount(0);
        task.setDuplicateCount(plan.duplicateCount());
        task.setKeptCount(0);
        task.setDeletedCount(0);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        taskMapper.insert(task);

        try {
            writeLog(taskId, "INFO", "created", "已创建 Adult 批量磁力任务", "targetPath=" + plan.targetPath());
            if (plan.duplicateCount() > 0) {
                writeLog(taskId, "WARN", "created", "已忽略本批次内重复的 Adult 下载链接", "count=" + plan.duplicateCount());
            }
            executorService.submit(() -> runTask(taskId, plan.items(), plan.rootPath()));
        } catch (RuntimeException exception) {
            removeUnscheduledTask(taskId);
            throw serviceUnavailable("Adult 批量任务调度失败，请稍后重试");
        }
        return toResponse(getExistingTask(taskId));
    }

    private String serializeDownloadLinks(List<String> downloadLinks) {
        try {
            return objectMapper.writeValueAsString(downloadLinks);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Adult download links could not be serialized", exception);
        }
    }

    private void removeUnscheduledTask(String taskId) {
        try {
            taskLogMapper.delete(new LambdaQueryWrapper<AdultMagnetIngestTaskLog>()
                    .eq(AdultMagnetIngestTaskLog::getTaskId, taskId));
        } finally {
            taskMapper.deleteById(taskId);
        }
    }

    public AdultMagnetIngestTaskListResponse listTasks() {
        ensureTablesReady();
        authService.requireAdminUser();
        List<AdultMagnetIngestTaskResponse> items = taskMapper.selectList(new LambdaQueryWrapper<AdultMagnetIngestTask>()
                        .orderByDesc(AdultMagnetIngestTask::getCreatedAt)
                        .last("LIMIT 20"))
                .stream()
                .map(this::toResponse)
                .toList();
        return new AdultMagnetIngestTaskListResponse(items, items.size());
    }

    public AdultMagnetIngestTaskResponse getTask(String taskId) {
        ensureTablesReady();
        authService.requireAdminUser();
        return toResponse(getExistingTask(taskId));
    }

    public AdultMagnetIngestTaskLogListResponse getTaskLogs(String taskId) {
        ensureTablesReady();
        authService.requireAdminUser();
        getExistingTask(taskId);
        List<AdultMagnetIngestTaskLogResponse> items = taskLogMapper.selectList(new LambdaQueryWrapper<AdultMagnetIngestTaskLog>()
                        .eq(AdultMagnetIngestTaskLog::getTaskId, taskId)
                        .orderByAsc(AdultMagnetIngestTaskLog::getId))
                .stream()
                .map(this::toLogResponse)
                .toList();
        return new AdultMagnetIngestTaskLogListResponse(items, items.size());
    }

    private void ensureTablesReady() {
        if (tablesReady) {
            return;
        }
        synchronized (this) {
            if (tablesReady) {
                return;
            }
            taskMapper.createTableIfNotExists();
            taskLogMapper.createTableIfNotExists();
            tablesReady = true;
        }
    }

    private void runTask(String taskId, List<AdultMagnetItem> items, String rootPath) {
        try {
            AdultMagnetIngestTask task = getExistingTask(taskId);
            prepareTargetDirectories(task, items, rootPath);
            submitItems(task, items);
            if (items.stream().noneMatch(AdultMagnetItem::submitted)) {
                markFinished(taskId, "FAILED", "failed", "所有 Adult 下载链接提交失败");
                return;
            }

            waitAndOrganizeItems(taskId, items);
            AdultMagnetIngestTask finishedTask = getExistingTask(taskId);
            int succeededCount = safeInt(finishedTask.getSucceededCount());
            int failedCount = safeInt(finishedTask.getFailedCount());
            int keptCount = safeInt(finishedTask.getKeptCount());
            if (succeededCount > 0 && failedCount == 0 && keptCount > 0) {
                refreshAutoSymlink(taskId, "succeeded");
                markFinished(taskId, "SUCCEEDED", "succeeded", null);
            } else if (keptCount > 0) {
                markFinished(taskId, "PARTIAL_SUCCESS", "partial_success", "部分 Adult 下载链接未成功完成");
            } else {
                markFinished(taskId, "FAILED", "failed", "没有保留到 100MB 以上的视频文件");
            }
        } catch (Exception exception) {
            log.warn("Adult magnet ingest task failed id={}", taskId, exception);
            markFinished(taskId, "FAILED", "failed", safeMessage(exception));
        }
    }

    private void prepareTargetDirectories(AdultMagnetIngestTask task, List<AdultMagnetItem> items, String rootPath) {
        writeLog(task.getId(), "INFO", "created", "正在准备 Adult 保存目录: " + task.getTargetPath(), task.getTargetPath());
        try {
            openListClient.ensureDirectoryReady(task.getTargetPath(), rootPath);
            for (AdultMagnetItem item : items) {
                writeLog(
                        task.getId(),
                        "INFO",
                        "created",
                        "正在准备 Adult 临时目录: " + item.tempPath(),
                        "index=" + item.index() + ", hash=" + item.magnetHash()
                );
            }
            openListClient.ensureChildDirectoriesReady(
                    task.getTargetPath(),
                    items.stream().map(item -> pathName(item.tempPath())).toList()
            );
        } catch (OpenListDirectoryPrepareException exception) {
            throw mapDirectoryPrepareException(exception);
        }
        writeLog(task.getId(), "INFO", "created", "Adult 保存目录准备完成: " + task.getTargetPath(), task.getTargetPath());
    }

    private void submitItems(AdultMagnetIngestTask task, List<AdultMagnetItem> items) {
        updateStatus(task.getId(), "SUBMITTED", "submitted", null);
        int submittedCount = 0;
        int failedCount = 0;
        List<String> openListTaskIds = new ArrayList<>();
        for (AdultMagnetItem item : items) {
            String offlineTool = offlineToolForAdultLink(item.magnet());
            try {
                String openListTaskId = openListClient.addOfflineDownload(item.tempPath(), item.magnet(), offlineTool);
                item.markSubmitted(openListTaskId);
                submittedCount++;
                openListTaskIds.add(item.magnetHash() + "=" + openListTaskId);
                writeLog(
                        task.getId(),
                        "INFO",
                        "submitted",
                        "Adult 下载链接已提交 OpenList",
                        "index=" + item.index() + ", hash=" + item.magnetHash()
                                + ", openListTaskId=" + openListTaskId
                                + ", tool=" + offlineTool
                                + ", submittedAt=" + item.submittedAt()
                                + ", tempPath=" + item.tempPath()
                );
            } catch (RuntimeException exception) {
                item.markFailed();
                failedCount++;
                writeLog(
                        task.getId(),
                        "ERROR",
                        "submitted",
                        "Adult 下载链接提交失败",
                        "index=" + item.index() + ", hash=" + item.magnetHash()
                                + ", tool=" + offlineTool
                                + ", error=" + safeMessage(exception)
                );
            }
        }
        updateCounts(task.getId(), submittedCount, 0, failedCount, 0, 0, String.join("\n", openListTaskIds));
        if (submittedCount > 0) {
            updateStatus(task.getId(), "DOWNLOADING", "downloading", null);
        }
    }

    private void waitAndOrganizeItems(String taskId, List<AdultMagnetItem> items) {
        Duration timeout = openListProperties.getAdultOfflineTimeout();
        Duration pollInterval = openListProperties.getPollInterval();
        List<Future<?>> preparationFutures = new ArrayList<>();

        while (items.stream().anyMatch(AdultMagnetItem::pending)) {
            for (AdultMagnetItem item : items) {
                if (!item.pending()) {
                    continue;
                }

                OpenListOfflineTaskInfo taskInfo = openListClient.offlineTaskInfo(item.openListTaskId());
                Integer state = taskInfo.state();
                if (state != null && state == 2) {
                    writeLog(taskId, "INFO", "downloading", "Adult 下载链接下载完成", itemDetail(item, taskInfo));
                    safeDeleteOpenListTask(taskId, item.openListTaskId());
                    submitPreparation(taskId, item, preparationFutures);
                    continue;
                }
                if (state != null && List.of(3, 4).contains(state)) {
                    item.markFailed();
                    incrementFailed(taskId);
                    writeLog(taskId, "ERROR", "downloading", "Adult 下载链接离线任务已取消", itemDetail(item, taskInfo));
                    continue;
                }
                if (state != null && state >= 5) {
                    if (hasQualifiedVideoFiles(item.tempPath())) {
                        writeLog(
                                taskId,
                                "WARN",
                                "downloading",
                                "Adult 下载链接状态异常但临时目录发现合格视频，继续整理",
                                itemDetail(item, taskInfo)
                        );
                        safeDeleteOpenListTask(taskId, item.openListTaskId());
                        submitPreparation(taskId, item, preparationFutures);
                        continue;
                    }
                    item.markFailed();
                    incrementFailed(taskId);
                    writeLog(taskId, "ERROR", "downloading", "Adult 下载链接离线任务失败", itemDetail(item, taskInfo));
                    continue;
                }
                if (isTimedOut(item, timeout)) {
                    if (hasQualifiedVideoFiles(item.tempPath())) {
                        writeLog(
                                taskId,
                                "WARN",
                                "downloading",
                                "Adult 下载链接已达到超时阈值，但临时目录发现合格视频，继续整理",
                                itemDetail(item, taskInfo) + ", " + timeoutDetail(item, timeout)
                        );
                        safeDeleteOpenListTask(taskId, item.openListTaskId());
                        submitPreparation(taskId, item, preparationFutures);
                        continue;
                    }
                    item.markFailed();
                    incrementFailed(taskId);
                    writeLog(
                            taskId,
                            "WARN",
                            "downloading",
                            "Adult 下载链接下载超时，按失败处理",
                            itemDetail(item, taskInfo) + ", " + timeoutDetail(item, timeout)
                    );
                    continue;
                }
                writeLog(taskId, "INFO", "downloading", offlineProgressMessage(taskInfo.progress()), itemDetail(item, taskInfo));
            }

            if (items.stream().anyMatch(AdultMagnetItem::pending)) {
                sleep(pollInterval);
            }
        }
        waitForPreparationFutures(taskId, preparationFutures);
        promotePreparedItems(taskId, items);
    }

    private void submitPreparation(String taskId, AdultMagnetItem item, List<Future<?>> preparationFutures) {
        item.markOrganizing();
        preparationFutures.add(organizeExecutorService.submit(() -> prepareCompletedItemSafely(taskId, item)));
    }

    private void waitForPreparationFutures(String taskId, List<Future<?>> preparationFutures) {
        for (Future<?> future : preparationFutures) {
            try {
                future.get();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new OpenListClientException("Adult preparation task interrupted", exception);
            } catch (ExecutionException exception) {
                writeLog(taskId, "ERROR", "organizing", "Adult 清理线程异常", safeMessage(exception));
                throw new OpenListClientException("Adult preparation task failed", exception);
            }
        }
    }

    private void prepareCompletedItemSafely(String taskId, AdultMagnetItem item) {
        try {
            prepareCompletedItem(taskId, item);
        } catch (RuntimeException exception) {
            item.markFailed();
            incrementFailed(taskId);
            writeLog(
                    taskId,
                    "ERROR",
                    "organizing",
                    "Adult 临时目录清理失败",
                    "index=" + item.index() + ", hash=" + item.magnetHash()
                            + ", tempPath=" + item.tempPath()
                            + ", error=" + safeMessage(exception)
            );
        }
    }

    private void prepareCompletedItem(String taskId, AdultMagnetItem item) {
        writeLog(taskId, "INFO", "organizing", "开始清理 Adult 临时目录", item.tempPath());
        AdultPreparedItem preparedItem = prepareTempDirectory(taskId, item);
        item.markPrepared(preparedItem);
        writeLog(
                taskId,
                "INFO",
                "organizing",
                "Adult 临时目录清理完成，等待批量发布",
                "index=" + item.index() + ", kept=" + preparedItem.keptCount()
                        + ", deleted=" + preparedItem.deletedCount()
                        + ", removedDuplicateDirectories=" + preparedItem.removedDuplicateDirectoryCount()
        );
    }

    private AdultPreparedItem prepareTempDirectory(String taskId, AdultMagnetItem item) {
        List<OpenListFileInfo> files = openListClient.findFiles(item.tempPath(), true);
        List<OpenListFileInfo> qualifiedFiles = new ArrayList<>();
        Map<String, List<String>> deleteNamesByPath = new LinkedHashMap<>();
        for (OpenListFileInfo file : files) {
            if (isQualifiedVideo(file)) {
                qualifiedFiles.add(file);
                continue;
            }
            deleteNamesByPath.computeIfAbsent(file.path(), ignored -> new ArrayList<>()).add(file.name());
        }

        int deletedCount = deleteNamesByPath.values().stream().mapToInt(List::size).sum();
        LibraryOrganizationPlan cleanupPlan = LibraryOrganizationPlan.fromGroupedOperations(
                item.tempPath(),
                Map.of(),
                Map.of(),
                deleteNamesByPath,
                Set.of(),
                Set.of()
        );
        adultLibraryOrganizer.organize(cleanupPlan, (message, detail) ->
                writeLog(taskId, "INFO", "organizing", message, detail));
        if (deletedCount > 0) {
            writeLog(taskId, "INFO", "organizing", "不合格文件删除完成", "count=" + deletedCount);
        }

        AdultDuplicateCleanupResult duplicateCleanupResult = planDuplicateTopLevelContent(item, qualifiedFiles);
        return new AdultPreparedItem(
                duplicateCleanupResult.keptQualifiedVideoCount(),
                deletedCount,
                duplicateCleanupResult.removedDirectoryCount(),
                duplicateCleanupResult.duplicateTopLevelNames()
        );
    }

    private void promotePreparedItems(String taskId, List<AdultMagnetItem> items) {
        List<AdultMagnetItem> preparedItems = items.stream()
                .filter(AdultMagnetItem::preparedForPromotion)
                .sorted(Comparator.comparingInt(AdultMagnetItem::index))
                .toList();
        if (preparedItems.isEmpty()) {
            return;
        }

        String targetPath = preparedItems.get(0).targetPath();
        String normalizedTargetPath = openListClient.normalizePath(targetPath);
        synchronized (promotionLocks.computeIfAbsent(normalizedTargetPath, ignored -> new Object())) {
            updateStatus(taskId, "ORGANIZING", "organizing", null);
            writeLog(
                    taskId,
                    "INFO",
                    "organizing",
                    "开始批量发布 Adult 临时目录",
                    "items=" + preparedItems.size() + ", targetPath=" + targetPath
            );
            AdultPromotionBatch batch;
            try {
                batch = planPromotionBatch(taskId, targetPath, preparedItems);
                if (!batch.plan().moves().isEmpty() || !batch.plan().deletions().isEmpty()) {
                    adultLibraryOrganizer.organize(batch.plan(), (message, detail) ->
                            writeLog(taskId, "INFO", "organizing", message, detail));
                }
            } catch (RuntimeException exception) {
                failPromotionBatch(taskId, preparedItems, exception);
                return;
            }
            completePromotedItems(taskId, batch.decisions());
        }
    }

    private AdultPromotionBatch planPromotionBatch(
            String taskId,
            String targetPath,
            List<AdultMagnetItem> preparedItems
    ) {
        Set<String> reservedNames = targetNames(targetPath);
        Set<String> expectedTargetNames = new HashSet<>();
        Map<String, List<String>> moveByDirectory = new LinkedHashMap<>();
        Map<String, List<String>> deleteByDirectory = new LinkedHashMap<>();
        List<AdultPromotionDecision> decisions = new ArrayList<>();

        for (AdultMagnetItem item : preparedItems) {
            AdultPreparedItem preparedItem = item.preparedItem();
            List<String> duplicateNames = preparedItem.duplicateTopLevelNames();
            List<OpenListFileInfo> currentChildren = openListClient.listFiles(item.tempPath(), true);
            List<String> promoteNames = new ArrayList<>();
            int skippedDuplicateCount = 0;
            for (OpenListFileInfo child : currentChildren) {
                if (duplicateNames.contains(child.name())) {
                    continue;
                }
                if (!reservedNames.add(child.name())) {
                    skippedDuplicateCount++;
                    writeLog(
                            taskId,
                            "WARN",
                            "organizing",
                            "目标同名内容已存在，跳过提升并保留源内容",
                            "index=" + item.index() + ", name=" + child.name()
                    );
                    continue;
                }
                promoteNames.add(child.name());
                expectedTargetNames.add(child.name());
            }
            if (!promoteNames.isEmpty()) {
                moveByDirectory.put(item.tempPath(), promoteNames);
            }
            if (!duplicateNames.isEmpty()) {
                deleteByDirectory.put(item.tempPath(), duplicateNames);
            }
            if (currentChildren.isEmpty()) {
                deleteByDirectory
                        .computeIfAbsent(targetPath, ignored -> new ArrayList<>())
                        .add(pathName(item.tempPath()));
            }
            decisions.add(new AdultPromotionDecision(item, promoteNames, skippedDuplicateCount));
        }

        LibraryOrganizationPlan plan = LibraryOrganizationPlan.fromGroupedOperations(
                targetPath,
                Map.of(),
                moveByDirectory,
                deleteByDirectory,
                expectedTargetNames,
                Set.of()
        );
        return new AdultPromotionBatch(plan, decisions);
    }

    private void completePromotedItems(String taskId, List<AdultPromotionDecision> decisions) {
        for (AdultPromotionDecision decision : decisions) {
            AdultMagnetItem item = decision.item();
            AdultPreparedItem preparedItem = item.preparedItem();
            AdultOrganizeResult result = new AdultOrganizeResult(
                    preparedItem.keptCount(),
                    preparedItem.deletedCount(),
                    decision.promoteNames().size(),
                    decision.skippedDuplicateCount(),
                    preparedItem.removedDuplicateDirectoryCount()
            );
            item.markSucceeded();
            incrementSucceeded(taskId, result);
            for (String duplicateName : preparedItem.duplicateTopLevelNames()) {
                writeLog(taskId, "WARN", "organizing", "删除重复下载目录", duplicateName);
            }
            writeLog(
                    taskId,
                    "INFO",
                    "organizing",
                    "Adult 临时目录批量发布完成",
                    "index=" + item.index() + ", kept=" + result.keptCount() + ", deleted=" + result.deletedCount()
                            + ", promoted=" + result.promotedCount()
                            + ", skippedDuplicates=" + result.skippedDuplicateCount()
                            + ", removedDuplicateDirectories=" + result.removedDuplicateDirectoryCount()
            );
        }
    }

    private void failPromotionBatch(String taskId, List<AdultMagnetItem> preparedItems, RuntimeException exception) {
        for (AdultMagnetItem item : preparedItems) {
            item.markFailed();
            incrementFailed(taskId);
            writeLog(
                    taskId,
                    "ERROR",
                    "organizing",
                    "Adult 批量发布失败，未自动重放",
                    "index=" + item.index() + ", hash=" + item.magnetHash()
                            + ", tempPath=" + item.tempPath()
                            + ", error=" + safeMessage(exception)
            );
        }
    }

    private AdultDuplicateCleanupResult planDuplicateTopLevelContent(
            AdultMagnetItem item,
            List<OpenListFileInfo> qualifiedFiles
    ) {
        List<OpenListFileInfo> children = new ArrayList<>(openListClient.listFiles(item.tempPath(), true));
        children.sort(Comparator
                .comparingInt((OpenListFileInfo child) -> duplicateNameRank(child.name()))
                .thenComparing(OpenListFileInfo::name));

        Map<String, List<OpenListFileInfo>> qualifiedFilesByTopLevelName = qualifiedFilesByTopLevelName(item.tempPath(), qualifiedFiles);
        Map<String, String> keptNameBySignature = new LinkedHashMap<>();
        int removedCount = 0;
        int keptQualifiedVideoCount = 0;
        List<String> duplicateTopLevelNames = new ArrayList<>();
        for (OpenListFileInfo child : children) {
            List<OpenListFileInfo> childQualifiedFiles = qualifiedFilesByTopLevelName.getOrDefault(child.name(), List.of());
            String signature = qualifiedVideoSignature(childQualifiedFiles);
            if (!StringUtils.hasText(signature)) {
                continue;
            }
            String keptName = keptNameBySignature.putIfAbsent(signature, child.name());
            if (keptName == null) {
                keptQualifiedVideoCount += childQualifiedFiles.size();
                continue;
            }
            duplicateTopLevelNames.add(child.name());
            removedCount++;
        }
        return new AdultDuplicateCleanupResult(removedCount, keptQualifiedVideoCount, duplicateTopLevelNames);
    }

    private Map<String, List<OpenListFileInfo>> qualifiedFilesByTopLevelName(
            String tempPath,
            List<OpenListFileInfo> qualifiedFiles
    ) {
        Map<String, List<OpenListFileInfo>> filesByTopLevelName = new LinkedHashMap<>();
        for (OpenListFileInfo file : qualifiedFiles) {
            filesByTopLevelName
                    .computeIfAbsent(topLevelName(tempPath, file), ignored -> new ArrayList<>())
                    .add(file);
        }
        return filesByTopLevelName;
    }

    private String topLevelName(String tempPath, OpenListFileInfo file) {
        String normalizedTempPath = openListClient.normalizePath(tempPath);
        String normalizedParentPath = openListClient.normalizePath(file.path());
        if (normalizedParentPath.equals(normalizedTempPath)) {
            return file.name();
        }
        String tempPrefix = normalizedTempPath.endsWith("/") ? normalizedTempPath : normalizedTempPath + "/";
        if (!normalizedParentPath.startsWith(tempPrefix)) {
            return file.name();
        }
        String relativeParentPath = normalizedParentPath.substring(tempPrefix.length());
        int slashIndex = relativeParentPath.indexOf('/');
        return slashIndex < 0 ? relativeParentPath : relativeParentPath.substring(0, slashIndex);
    }

    private String qualifiedVideoSignature(List<OpenListFileInfo> files) {
        List<String> qualifiedVideos = files.stream()
                .filter(this::isQualifiedVideo)
                .map(file -> file.name() + "#" + file.size())
                .sorted()
                .toList();
        return String.join("|", qualifiedVideos);
    }

    private int duplicateNameRank(String name) {
        return DUPLICATE_NAME_SUFFIX_PATTERN.matcher(name).find() ? 1 : 0;
    }

    private AdultTaskPlan buildTaskPlan(AdultMagnetIngestTaskCreateRequest request, String taskId) {
        if (request == null) {
            throw badRequest("请求不能为空");
        }
        String category = normalizeCategory(request.category());
        String rootPath = configuredRootPath(openListProperties.getAdultRootPath(), "OpenList Adult 基础路径尚未配置");
        String dateFolder = LocalDate.now().format(DATE_FOLDER_FORMATTER);
        String targetPath = openListClient.joinPath(openListClient.joinPath(rootPath, categoryFolder(category)), dateFolder);
        List<String> rawMagnets = request.magnets() == null
                ? List.of()
                : request.magnets().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        if (rawMagnets.size() > MAX_MAGNETS_PER_BATCH) {
            throw badRequest("单批最多提交 50 条下载链接");
        }

        LinkedHashMap<String, String> magnetByHash = new LinkedHashMap<>();
        int duplicateCount = 0;
        for (String rawMagnet : rawMagnets) {
            String magnet = normalizeAdultDownloadLink(rawMagnet);
            String hash = extractAdultDownloadKey(magnet);
            if (magnetByHash.containsKey(hash)) {
                duplicateCount++;
                continue;
            }
            magnetByHash.put(hash, magnet);
        }

        List<AdultMagnetItem> items = new ArrayList<>();
        int index = 1;
        String taskShortId = taskId.replace("-", "").substring(0, 8);
        for (Map.Entry<String, String> entry : magnetByHash.entrySet()) {
            String shortHash = shortDownloadKey(entry.getKey());
            String tempName = "adult-task-" + taskShortId + "-" + String.format(Locale.ROOT, "%02d", index) + "-" + shortHash;
            items.add(new AdultMagnetItem(index, entry.getValue(), entry.getKey(), targetPath, openListClient.joinPath(targetPath, tempName)));
            index++;
        }
        return new AdultTaskPlan(category, rootPath, dateFolder, targetPath, items, duplicateCount);
    }

    private String normalizeCategory(String category) {
        String normalized = category == null ? "" : category.trim().toUpperCase(Locale.ROOT);
        if (!List.of("JAV", "OTHER").contains(normalized)) {
            throw badRequest("Adult 分类无效");
        }
        return normalized;
    }

    private String categoryFolder(String category) {
        return "JAV".equals(category) ? "JAV" : "Other";
    }

    private String normalizeAdultDownloadLink(String link) {
        String normalized = link == null ? "" : link.trim();
        String lowerCaseLink = normalized.toLowerCase(Locale.ROOT);
        if (!lowerCaseLink.startsWith("magnet:?") && !lowerCaseLink.startsWith("ed2k://")) {
            throw badRequest("Adult 下载链接需以 magnet:? 或 ed2k:// 开头");
        }
        return normalized;
    }

    private String offlineToolForAdultLink(String link) {
        return isEd2kDownloadLink(link)
                ? openListProperties.getEd2kOfflineTool()
                : openListProperties.getOfflineTool();
    }

    private String extractAdultDownloadKey(String link) {
        if (isEd2kDownloadLink(link)) {
            Matcher matcher = ED2K_FILE_HASH_PATTERN.matcher(link);
            if (matcher.find()) {
                return "ed2k:" + matcher.group(1).toLowerCase(Locale.ROOT);
            }
            return "ed2k:" + sha256Hex(link.toLowerCase(Locale.ROOT));
        }

        Matcher matcher = MAGNET_HASH_PATTERN.matcher(link);
        if (!matcher.find()) {
            throw badRequest("magnet 缺少 btih hash");
        }
        return matcher.group(1).toLowerCase(Locale.ROOT);
    }

    private boolean isEd2kDownloadLink(String link) {
        return link != null && link.toLowerCase(Locale.ROOT).startsWith("ed2k://");
    }

    private String shortDownloadKey(String key) {
        String compact = key.replaceAll("[^a-zA-Z0-9]", "");
        if (!StringUtils.hasText(compact)) {
            return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        }
        return compact.substring(0, Math.min(8, compact.length()));
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte item : hash) {
                builder.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private boolean isQualifiedVideo(OpenListFileInfo file) {
        return renameService.isVideo(file.name()) && file.size() != null && file.size() >= MIN_VIDEO_BYTES;
    }

    private boolean hasQualifiedVideoFiles(String path) {
        try {
            return openListClient.findFiles(path, true).stream().anyMatch(this::isQualifiedVideo);
        } catch (RuntimeException exception) {
            log.warn("Failed to inspect Adult temp path before timeout fallback path={}", path, exception);
            return false;
        }
    }

    private boolean isTimedOut(AdultMagnetItem item, Duration timeout) {
        return Duration.between(item.submittedAt(), Instant.now()).compareTo(timeout) >= 0;
    }

    private Set<String> targetNames(String savePath) {
        return openListClient.listFiles(savePath, true).stream()
                .map(OpenListFileInfo::name)
                .collect(HashSet::new, HashSet::add, HashSet::addAll);
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
        removeDirectoryIfEmpty(taskId, currentPath);
    }

    private void removeDirectoryIfEmpty(String taskId, String path) {
        try {
            if (openListClient.listFiles(path).isEmpty()) {
                openListClient.remove(parentPath(path), List.of(pathName(path)));
                writeLog(taskId, "INFO", "organizing", "删除空目录", path);
            }
        } catch (RuntimeException exception) {
            writeLog(taskId, "WARN", "organizing", "删除空目录失败", path);
        }
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

    private BusinessException mapDirectoryPrepareException(OpenListDirectoryPrepareException exception) {
        if (exception.getReason() == OpenListDirectoryPrepareException.Reason.ROOT_NOT_FOUND) {
            return serviceUnavailable("OpenList Adult 基础路径不存在");
        }
        return badRequest("OpenList Adult 目标路径无效");
    }

    private void safeDeleteOpenListTask(String taskId, String openListTaskId) {
        try {
            openListClient.deleteOfflineTask(openListTaskId);
        } catch (RuntimeException exception) {
            writeLog(taskId, "WARN", "downloading", "删除 OpenList 离线任务记录失败，继续后续整理", openListTaskId);
        }
    }

    private void updateStatus(String taskId, String status, String stage, String errorMessage) {
        LambdaUpdateWrapper<AdultMagnetIngestTask> updateWrapper = new LambdaUpdateWrapper<AdultMagnetIngestTask>()
                .eq(AdultMagnetIngestTask::getId, taskId)
                .set(AdultMagnetIngestTask::getStatus, status)
                .set(AdultMagnetIngestTask::getStage, stage);
        if (errorMessage != null) {
            updateWrapper.set(AdultMagnetIngestTask::getErrorMessage, truncate(errorMessage, 1000));
        }
        taskMapper.update(updateWrapper);
    }

    private void updateCounts(
            String taskId,
            int submittedCount,
            int succeededCount,
            int failedCount,
            int keptCount,
            int deletedCount,
            String openListTaskIds
    ) {
        LambdaUpdateWrapper<AdultMagnetIngestTask> updateWrapper = new LambdaUpdateWrapper<AdultMagnetIngestTask>()
                .eq(AdultMagnetIngestTask::getId, taskId)
                .set(AdultMagnetIngestTask::getSubmittedCount, submittedCount)
                .set(AdultMagnetIngestTask::getSucceededCount, succeededCount)
                .set(AdultMagnetIngestTask::getFailedCount, failedCount)
                .set(AdultMagnetIngestTask::getKeptCount, keptCount)
                .set(AdultMagnetIngestTask::getDeletedCount, deletedCount);
        if (openListTaskIds != null) {
            updateWrapper.set(AdultMagnetIngestTask::getOpenlistTaskIds, openListTaskIds);
        }
        taskMapper.update(updateWrapper);
    }

    private void incrementFailed(String taskId) {
        synchronized (countLock) {
            AdultMagnetIngestTask task = getExistingTask(taskId);
            updateCounts(
                    taskId,
                    safeInt(task.getSubmittedCount()),
                    safeInt(task.getSucceededCount()),
                    safeInt(task.getFailedCount()) + 1,
                    safeInt(task.getKeptCount()),
                    safeInt(task.getDeletedCount()),
                    null
            );
        }
    }

    private void incrementSucceeded(String taskId, AdultOrganizeResult result) {
        synchronized (countLock) {
            AdultMagnetIngestTask task = getExistingTask(taskId);
            updateCounts(
                    taskId,
                    safeInt(task.getSubmittedCount()),
                    safeInt(task.getSucceededCount()) + 1,
                    safeInt(task.getFailedCount()),
                    safeInt(task.getKeptCount()) + result.keptCount(),
                    safeInt(task.getDeletedCount()) + result.deletedCount(),
                    null
            );
        }
    }

    private void markFinished(String taskId, String status, String stage, String errorMessage) {
        writeLog(
                taskId,
                TERMINAL_STATUSES.contains(status) && "FAILED".equals(status) ? "ERROR" : "INFO",
                stage,
                "Adult 批量任务结束",
                "status=" + status + (errorMessage == null ? "" : ", message=" + errorMessage)
        );
        LambdaUpdateWrapper<AdultMagnetIngestTask> updateWrapper = new LambdaUpdateWrapper<AdultMagnetIngestTask>()
                .eq(AdultMagnetIngestTask::getId, taskId)
                .set(AdultMagnetIngestTask::getStatus, status)
                .set(AdultMagnetIngestTask::getStage, stage)
                .set(AdultMagnetIngestTask::getFinishedAt, LocalDateTime.now());
        if (errorMessage != null) {
            updateWrapper.set(AdultMagnetIngestTask::getErrorMessage, truncate(errorMessage, 1000));
        }
        taskMapper.update(updateWrapper);
    }

    private void refreshAutoSymlink(String taskId, String stage) {
        try {
            writeLog(taskId, "INFO", stage, "正在触发 AutoSymlink 刷新", null);
            AutoSymlinkRefreshService.RefreshOutcome outcome = autoSymlinkRefreshService.refreshAdult();
            writeAutoSymlinkOutcome(taskId, stage, outcome);
        } catch (Exception exception) {
            log.warn("Adult AutoSymlink refresh logging failed taskId={}", taskId, exception);
            try {
                writeLog(taskId, "WARN", stage, "AutoSymlink 刷新任务提交失败，已跳过", null);
            } catch (Exception logException) {
                log.warn("Adult AutoSymlink refresh task log write failed taskId={}", taskId, logException);
            }
        }
    }

    private void writeAutoSymlinkOutcome(
            String taskId,
            String stage,
            AutoSymlinkRefreshService.RefreshOutcome outcome
    ) {
        String level = outcome.status() == AutoSymlinkRefreshService.Status.SUBMITTED ? "INFO" : "WARN";
        writeLog(taskId, level, stage, outcome.message(), outcome.detail());
    }

    private void writeLog(String taskId, String level, String stage, String message, String detail) {
        AdultMagnetIngestTaskLog taskLog = new AdultMagnetIngestTaskLog();
        taskLog.setTaskId(taskId);
        taskLog.setLevel(level);
        taskLog.setStage(stage);
        taskLog.setMessage(truncate(message, 1000));
        taskLog.setDetail(detail);
        taskLogMapper.insert(taskLog);
    }

    private AdultMagnetIngestTask getExistingTask(String taskId) {
        AdultMagnetIngestTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "任务不存在", HttpStatus.NOT_FOUND);
        }
        return task;
    }

    private AdultMagnetIngestTaskResponse toResponse(AdultMagnetIngestTask task) {
        return new AdultMagnetIngestTaskResponse(
                task.getId(),
                task.getCreatedByUserId(),
                task.getCategory(),
                task.getStatus(),
                task.getStage(),
                task.getDateFolder(),
                task.getTargetPath(),
                safeInt(task.getMagnetCount()),
                safeInt(task.getSubmittedCount()),
                safeInt(task.getSucceededCount()),
                safeInt(task.getFailedCount()),
                safeInt(task.getDuplicateCount()),
                safeInt(task.getKeptCount()),
                safeInt(task.getDeletedCount()),
                task.getErrorMessage(),
                task.getCreatedAt(),
                task.getUpdatedAt(),
                task.getFinishedAt()
        );
    }

    private AdultMagnetIngestTaskLogResponse toLogResponse(AdultMagnetIngestTaskLog taskLog) {
        return new AdultMagnetIngestTaskLogResponse(
                taskLog.getId(),
                taskLog.getTaskId(),
                taskLog.getLevel(),
                taskLog.getStage(),
                taskLog.getMessage(),
                taskLog.getDetail(),
                taskLog.getCreatedAt()
        );
    }

    private String offlineProgressMessage(Integer progress) {
        if (progress == null) {
            return "Adult 下载链接离线下载进行中";
        }
        return "Adult 下载链接离线下载进度 " + progress + "%";
    }

    private String itemDetail(AdultMagnetItem item, OpenListOfflineTaskInfo taskInfo) {
        List<String> details = new ArrayList<>();
        details.add("index=" + item.index());
        details.add("hash=" + item.magnetHash());
        details.add("openListTaskId=" + item.openListTaskId());
        details.add("tempPath=" + item.tempPath());
        if (taskInfo.progress() != null) {
            details.add("progress=" + taskInfo.progress());
        }
        if (taskInfo.state() != null) {
            details.add("state=" + taskInfo.state());
        }
        if (StringUtils.hasText(taskInfo.status())) {
            details.add("status=" + taskInfo.status().trim());
        }
        if (StringUtils.hasText(taskInfo.error())) {
            details.add("error=" + taskInfo.error().trim());
        }
        return String.join(", ", details);
    }

    private String timeoutDetail(AdultMagnetItem item, Duration timeout) {
        return "elapsed=" + Duration.between(item.submittedAt(), Instant.now())
                + ", timeout=" + timeout
                + ", submittedAt=" + item.submittedAt();
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

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return StringUtils.hasText(message) ? message : exception.getClass().getSimpleName();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }

    private BusinessException serviceUnavailable(String message) {
        return new BusinessException(ErrorCode.INTERNAL_ERROR, message, HttpStatus.SERVICE_UNAVAILABLE);
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new OpenListClientException("Adult magnet task interrupted");
        }
    }

    private record AdultTaskPlan(
            String category,
            String rootPath,
            String dateFolder,
            String targetPath,
            List<AdultMagnetItem> items,
            int duplicateCount
    ) {
    }

    private record AdultOrganizeResult(
            int keptCount,
            int deletedCount,
            int promotedCount,
            int skippedDuplicateCount,
            int removedDuplicateDirectoryCount
    ) {
    }

    private record AdultPreparedItem(
            int keptCount,
            int deletedCount,
            int removedDuplicateDirectoryCount,
            List<String> duplicateTopLevelNames
    ) {
        private AdultPreparedItem {
            duplicateTopLevelNames = List.copyOf(duplicateTopLevelNames);
        }
    }

    private record AdultPromotionDecision(
            AdultMagnetItem item,
            List<String> promoteNames,
            int skippedDuplicateCount
    ) {
        private AdultPromotionDecision {
            promoteNames = List.copyOf(promoteNames);
        }
    }

    private record AdultPromotionBatch(
            LibraryOrganizationPlan plan,
            List<AdultPromotionDecision> decisions
    ) {
        private AdultPromotionBatch {
            decisions = List.copyOf(decisions);
        }
    }

    private record AdultDuplicateCleanupResult(
            int removedDirectoryCount,
            int keptQualifiedVideoCount,
            List<String> duplicateTopLevelNames
    ) {
    }

    private static class AdultMagnetItem {
        private final int index;
        private final String magnet;
        private final String magnetHash;
        private final String targetPath;
        private final String tempPath;
        private String openListTaskId;
        private Instant submittedAt;
        private boolean submitted;
        private boolean organizing;
        private boolean succeeded;
        private boolean failed;
        private AdultPreparedItem preparedItem;

        AdultMagnetItem(int index, String magnet, String magnetHash, String targetPath, String tempPath) {
            this.index = index;
            this.magnet = magnet;
            this.magnetHash = magnetHash;
            this.targetPath = targetPath;
            this.tempPath = tempPath;
        }

        int index() {
            return index;
        }

        String magnet() {
            return magnet;
        }

        String magnetHash() {
            return magnetHash;
        }

        String targetPath() {
            return targetPath;
        }

        String tempPath() {
            return tempPath;
        }

        String openListTaskId() {
            return openListTaskId;
        }

        Instant submittedAt() {
            return submittedAt;
        }

        boolean submitted() {
            return submitted;
        }

        boolean pending() {
            return submitted && !organizing && !succeeded && !failed;
        }

        boolean preparedForPromotion() {
            return preparedItem != null && !succeeded && !failed;
        }

        AdultPreparedItem preparedItem() {
            return preparedItem;
        }

        void markSubmitted(String openListTaskId) {
            this.openListTaskId = openListTaskId;
            this.submittedAt = Instant.now();
            this.submitted = true;
        }

        void markOrganizing() {
            this.organizing = true;
        }

        void markPrepared(AdultPreparedItem preparedItem) {
            this.preparedItem = preparedItem;
        }

        void markSucceeded() {
            this.succeeded = true;
        }

        void markFailed() {
            this.failed = true;
        }
    }

    private static class AdultWorkerThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable);
            thread.setName("adult-magnet-ingest-worker");
            thread.setDaemon(true);
            return thread;
        }
    }

    private static class AdultOrganizeThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable);
            thread.setName("adult-magnet-organize-worker");
            thread.setDaemon(true);
            return thread;
        }
    }
}
