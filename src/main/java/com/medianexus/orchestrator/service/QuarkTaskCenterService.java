package com.medianexus.orchestrator.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.config.QasProperties;
import com.medianexus.orchestrator.dto.taskcenter.request.QuarkTaskCenterRetryRequest;
import com.medianexus.orchestrator.dto.taskcenter.request.QuarkTaskCenterSubscriptionRequest;
import com.medianexus.orchestrator.dto.taskcenter.response.OpenListIngestTaskCenterLogResponse;
import com.medianexus.orchestrator.dto.taskcenter.response.QuarkTaskCenterActionResponse;
import com.medianexus.orchestrator.dto.taskcenter.response.QuarkTaskCenterAttemptResponse;
import com.medianexus.orchestrator.dto.taskcenter.response.QuarkTaskCenterChildResponse;
import com.medianexus.orchestrator.dto.taskcenter.response.QuarkTaskCenterDetailResponse;
import com.medianexus.orchestrator.dto.taskcenter.response.QuarkTaskCenterFileResponse;
import com.medianexus.orchestrator.dto.taskcenter.response.QuarkTaskCenterItemResponse;
import com.medianexus.orchestrator.dto.taskcenter.response.QuarkTaskCenterListResponse;
import com.medianexus.orchestrator.dto.taskcenter.response.QuarkTaskCenterLogsResponse;
import com.medianexus.orchestrator.dto.taskcenter.response.QuarkTaskCenterProgressResponse;
import com.medianexus.orchestrator.integration.qas.QasClient;
import com.medianexus.orchestrator.integration.qas.QasClientException;
import com.medianexus.orchestrator.integration.qas.QasCreatedTask;
import com.medianexus.orchestrator.integration.qas.QasShareTree;
import com.medianexus.orchestrator.integration.qas.QasTaskCreateCommand;
import com.medianexus.orchestrator.integration.quark.QuarkDirectClient;
import com.medianexus.orchestrator.integration.smartstrm.SmartStrmWebhookClient;
import com.medianexus.orchestrator.mapper.QuarkIngestTaskAttemptMapper;
import com.medianexus.orchestrator.mapper.QuarkIngestTaskChildMapper;
import com.medianexus.orchestrator.mapper.QuarkIngestTaskFileMapper;
import com.medianexus.orchestrator.mapper.QuarkIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.QuarkIngestTaskMapper;
import com.medianexus.orchestrator.mapper.UserMapper;
import com.medianexus.orchestrator.model.QuarkIngestTask;
import com.medianexus.orchestrator.model.QuarkIngestTaskAttempt;
import com.medianexus.orchestrator.model.QuarkIngestTaskChild;
import com.medianexus.orchestrator.model.QuarkIngestTaskFile;
import com.medianexus.orchestrator.model.QuarkIngestTaskLog;
import com.medianexus.orchestrator.model.User;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Query and retry boundary for the user-facing Quark task-center tab. */
@Service
public class QuarkTaskCenterService {

    private static final String ADMIN_ROLE = "ADMIN";
    private static final String TASK_TYPE = "QUARK";
    private static final String ALL = "ALL";
    private static final String IN_PROGRESS = "IN_PROGRESS";
    private static final String NEEDS_ATTENTION = "NEEDS_ATTENTION";
    private static final String SUCCEEDED = "SUCCEEDED";
    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int DEFAULT_LOG_LIMIT = 100;
    private static final int MAX_LOG_LIMIT = 200;
    private static final Set<String> ACTIVE_CHILD_STATUSES = Set.of("PENDING", "QUEUED", "PROCESSING", "SUBMITTED");
    private static final Set<String> RETRYABLE_CHILD_STATUSES = Set.of("FAILED", "PARTIAL", "UNKNOWN", "INTERRUPTED");

    private final AuthService authService;
    private final UserMapper userMapper;
    private final QuarkIngestTaskMapper taskMapper;
    private final QuarkIngestTaskLogMapper logMapper;
    private final QuarkIngestTaskAttemptMapper attemptMapper;
    private final QuarkIngestTaskChildMapper childMapper;
    private final QuarkIngestTaskFileMapper fileMapper;
    private final QuarkTaskCenterRecorder recorder;
    private final QasClient qasClient;
    private final QuarkDirectClient directClient;
    private final SmartStrmWebhookClient smartStrmWebhookClient;
    private final ExecutorService retryExecutor = Executors.newFixedThreadPool(2);

    public QuarkTaskCenterService(
            AuthService authService,
            UserMapper userMapper,
            QuarkIngestTaskMapper taskMapper,
            QuarkIngestTaskLogMapper logMapper,
            QuarkIngestTaskAttemptMapper attemptMapper,
            QuarkIngestTaskChildMapper childMapper,
            QuarkIngestTaskFileMapper fileMapper,
            QuarkTaskCenterRecorder recorder,
            QasClient qasClient,
            QuarkDirectClient directClient,
            SmartStrmWebhookClient smartStrmWebhookClient,
            QasProperties ignoredQasProperties
    ) {
        this.authService = authService;
        this.userMapper = userMapper;
        this.taskMapper = taskMapper;
        this.logMapper = logMapper;
        this.attemptMapper = attemptMapper;
        this.childMapper = childMapper;
        this.fileMapper = fileMapper;
        this.recorder = recorder;
        this.qasClient = qasClient;
        this.directClient = directClient;
        this.smartStrmWebhookClient = smartStrmWebhookClient;
    }

    public QuarkTaskCenterListResponse listTasks(
            String view,
            String productType,
            String sourceType,
            String subscription,
            String keyword,
            Integer page,
            Integer pageSize
    ) {
        User user = authService.requireCurrentUser();
        String normalizedView = normalizeFilter(view, ALL);
        String normalizedProduct = normalizeFilter(productType, ALL);
        String normalizedSource = normalizeFilter(sourceType, ALL);
        String normalizedSubscription = normalizeFilter(subscription, ALL);
        String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        int normalizedPage = page == null ? DEFAULT_PAGE : Math.max(DEFAULT_PAGE, page);
        int normalizedPageSize = normalizePageSize(pageSize);

        LambdaQueryWrapper<QuarkIngestTask> query = new LambdaQueryWrapper<QuarkIngestTask>()
                .orderByDesc(QuarkIngestTask::getUpdatedAt)
                .orderByDesc(QuarkIngestTask::getCreatedAt);
        if (!isAdmin(user)) {
            query.eq(QuarkIngestTask::getCreatedByUserId, user.getId());
        }
        List<QuarkTaskCenterItemResponse> filteredItems = taskMapper.selectList(query).stream()
                .map(task -> toItem(task, user))
                .filter(item -> matches(item, ALL, normalizedProduct, normalizedSource,
                        normalizedSubscription, normalizedKeyword))
                .toList();
        List<QuarkTaskCenterItemResponse> visibleItems = filteredItems.stream()
                .filter(item -> matches(item, normalizedView, ALL, ALL, ALL, ""))
                .toList();
        int allCount = filteredItems.size();
        int inProgressCount = countByView(filteredItems, IN_PROGRESS);
        int needsAttentionCount = countByView(filteredItems, NEEDS_ATTENTION);
        int succeededCount = countByView(filteredItems, SUCCEEDED);
        int subscribedCount = (int) filteredItems.stream().filter(QuarkTaskCenterItemResponse::subscriptionEnabled).count();
        int from = Math.min((normalizedPage - 1) * normalizedPageSize, visibleItems.size());
        int to = Math.min(from + normalizedPageSize, visibleItems.size());
        int actualPage = visibleItems.isEmpty() ? 1 : normalizedPage;
        return new QuarkTaskCenterListResponse(
                visibleItems.subList(from, to),
                visibleItems.size(),
                actualPage,
                normalizedPageSize,
                allCount,
                inProgressCount,
                needsAttentionCount,
                succeededCount,
                subscribedCount
        );
    }

    public QuarkTaskCenterDetailResponse getTaskDetail(String taskId, Integer logLimit) {
        User user = authService.requireCurrentUser();
        QuarkIngestTask task = getAccessibleTask(taskId, user);
        List<QuarkIngestTaskChild> children = listChildren(taskId);
        AggregateSnapshot snapshot = snapshot(task, children);
        List<OpenListIngestTaskCenterLogResponse> logs = listLogs(taskId, null, null,
                normalizeLogLimit(logLimit));
        return toDetail(task, user, children, snapshot, logs,
                hasOlderLogs(taskId, logs), false);
    }

    public QuarkTaskCenterLogsResponse getTaskLogs(
            String taskId,
            Long beforeId,
            Long afterId,
            Integer limit
    ) {
        User user = authService.requireCurrentUser();
        getAccessibleTask(taskId, user);
        if (beforeId != null && afterId != null) {
            throw badRequest("before_id 和 after_id 不能同时传入");
        }
        int normalizedLimit = normalizeLogLimit(limit);
        List<OpenListIngestTaskCenterLogResponse> logs = listLogs(taskId, beforeId, afterId, normalizedLimit);
        return new QuarkTaskCenterLogsResponse(
                logs,
                hasOlderLogs(taskId, logs),
                hasNewerLogs(taskId, logs),
                logs.isEmpty() ? null : logs.get(0).id(),
                logs.isEmpty() ? null : logs.get(logs.size() - 1).id()
        );
    }

    public QuarkTaskCenterActionResponse retry(String taskId, QuarkTaskCenterRetryRequest request) {
        User user = authService.requireCurrentUser();
        QuarkIngestTask task = getAccessibleTask(taskId, user);
        List<QuarkIngestTaskChild> children = listChildren(taskId);
        if (children.isEmpty()) {
            throw badRequest("历史任务没有可重试的季度或版本明细，请重新提交分享链接");
        }
        List<String> requestedIds = request == null ? List.of() : request.childTaskIds();
        List<QuarkIngestTaskChild> targets = children.stream()
                .filter(child -> requestedIds.isEmpty() || requestedIds.contains(child.getId()))
                .filter(child -> RETRYABLE_CHILD_STATUSES.contains(child.getStatus()))
                .toList();
        if (targets.isEmpty()) {
            throw badRequest("当前没有可重试的未完成处理项");
        }
        String attemptId = recorder.createRetryAttempt(
                taskId,
                targets.stream().map(QuarkIngestTaskChild::getId).toList(),
                user.getId()
        );
        updateAggregate(taskId, "STARTED", "retrying", "正在重试 " + targets.size() + " 个未完成处理项");
        writeLog(taskId, "INFO", "retrying", "已提交重试", "attempt_id=" + attemptId);
        for (QuarkIngestTaskChild child : targets) {
            retryExecutor.submit(() -> executeRetryChild(task, child, attemptId));
        }
        return new QuarkTaskCenterActionResponse(
                taskId,
                "STARTED",
                "已提交 " + targets.size() + " 个未完成处理项的重试",
                "/tasks/quark/" + taskId
        );
    }

    public QuarkTaskCenterActionResponse updateSubscription(
            String taskId,
            String childId,
            QuarkTaskCenterSubscriptionRequest request
    ) {
        User user = authService.requireCurrentUser();
        getAccessibleTask(taskId, user);
        if (request == null || request.enabled() == null) {
            throw badRequest("自动更新开关不能为空");
        }
        QuarkIngestTaskChild child = childMapper.selectById(childId);
        if (child == null || !taskId.equals(child.getAggregateTaskId())) {
            throw badRequest("执行单元不存在");
        }
        recorder.updateSubscription(taskId, childId, request.enabled());
        writeLog(taskId, "INFO", "subscription", request.enabled() ? "已开启自动更新" : "已关闭自动更新",
                child.getTaskName());
        return new QuarkTaskCenterActionResponse(
                taskId,
                request.enabled() ? "SUBSCRIBED" : "ONE_TIME",
                request.enabled() ? "已开启自动更新" : "已关闭自动更新",
                "/tasks/quark/" + taskId
        );
    }

    private void executeRetryChild(QuarkIngestTask aggregate, QuarkIngestTaskChild child, String attemptId) {
        recorder.markChildStatus(aggregate.getId(), child.getId(), "PROCESSING", null);
        writeLog(aggregate.getId(), "INFO", "processing", "开始处理重试项", child.getTaskName());
        try {
            QasTaskPlan plan = new QasTaskPlan(
                    child.getTaskName(), child.getSourceUrl(), child.getSavePath(),
                    nullToEmpty(child.getPattern()), nullToEmpty(child.getReplaceRule()), child.getVersionLabel()
            );
            if (Boolean.TRUE.equals(child.getSubscriptionEnabled())) {
                QasCreatedTask created = qasClient.createTask(new QasTaskCreateCommand(
                        plan.taskName(), plan.sourceUrl(), plan.savePath(), plan.pattern(), plan.replace()
                ));
                qasClient.triggerTaskNow(created);
                recorder.markChildStatus(aggregate.getId(), child.getId(), "SUBMITTED", null);
                writeLog(aggregate.getId(), "INFO", "submitted", "已提交自动更新处理", child.getTaskName());
            } else {
                directClient.transfer(plan, new QasShareTree(plan.sourceUrl(), List.of()),
                        message -> writeLog(aggregate.getId(), "INFO", "processing", message, child.getTaskName()));
                smartStrmWebhookClient.trigger(child.getSavePath(), aggregate.getMediaType());
                recorder.markChildStatus(aggregate.getId(), child.getId(), "SUCCEEDED", null);
                writeLog(aggregate.getId(), "INFO", "succeeded", "本次处理完成", child.getTaskName());
            }
            updateAggregateAfterChildren(aggregate.getId());
            updateAttempt(attemptId, "SUCCEEDED", "重试处理已提交");
        } catch (RuntimeException exception) {
            String message = safeMessage(exception.getMessage());
            recorder.markChildStatus(aggregate.getId(), child.getId(), "FAILED", message);
            writeLog(aggregate.getId(), "ERROR", "failed", "处理失败", child.getTaskName() + "：" + message);
            updateAggregateAfterChildren(aggregate.getId());
            updateAttempt(attemptId, "PARTIAL", message);
        }
    }

    private QuarkTaskCenterItemResponse toItem(QuarkIngestTask task, User currentUser) {
        List<QuarkIngestTaskChild> children = listChildren(task.getId());
        AggregateSnapshot snapshot = snapshot(task, children);
        String sourceType = sourceType(task, children);
        String creator = null;
        if (isAdmin(currentUser) && task.getCreatedByUserId() != null) {
            User owner = userMapper.selectById(task.getCreatedByUserId());
            creator = owner == null ? null : owner.getUsername();
        }
        String progress = progressSummary(snapshot);
        return new QuarkTaskCenterItemResponse(
                TASK_TYPE,
                task.getId(),
                normalizeProductType(task.getMediaType()),
                task.getCreatedByUserId(),
                creator,
                task.getTitle(),
                snapshot.status(),
                task.getStage(),
                sourceType,
                progress,
                Math.max(1, attemptCount(task.getId())),
                snapshot.plannedUnits(),
                snapshot.completedUnits(),
                snapshot.totalFiles(),
                snapshot.processedFiles(),
                snapshot.failedFiles(),
                snapshot.subscriptionEnabled(),
                "/tasks/quark/" + task.getId(),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }

    private QuarkTaskCenterDetailResponse toDetail(
            QuarkIngestTask task,
            User currentUser,
            List<QuarkIngestTaskChild> children,
            AggregateSnapshot snapshot,
            List<OpenListIngestTaskCenterLogResponse> logs,
            boolean hasOlder,
            boolean hasNewer
    ) {
        List<QuarkTaskCenterChildResponse> childResponses = children.stream()
                .sorted(Comparator.comparing(QuarkIngestTaskChild::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(this::toChildResponse)
                .toList();
        List<QuarkTaskCenterAttemptResponse> attempts = attemptMapper.selectList(
                        new LambdaQueryWrapper<QuarkIngestTaskAttempt>()
                                .eq(QuarkIngestTaskAttempt::getAggregateTaskId, task.getId())
                                .orderByAsc(QuarkIngestTaskAttempt::getAttemptNo))
                .stream()
                .map(attempt -> new QuarkTaskCenterAttemptResponse(
                        attempt.getId(), attempt.getAttemptNo(), attempt.getTriggerType(), attempt.getStatus(),
                        attempt.getMessage(), attempt.getStartedAt(), attempt.getEndedAt(), attempt.getCreatedByUserId()))
                .toList();
        User owner = task.getCreatedByUserId() == null ? null : userMapper.selectById(task.getCreatedByUserId());
        return new QuarkTaskCenterDetailResponse(
                TASK_TYPE,
                task.getId(),
                normalizeProductType(task.getMediaType()),
                task.getCreatedByUserId(),
                isAdmin(currentUser) && owner != null ? owner.getUsername() : null,
                task.getTitle(),
                snapshot.status(),
                task.getStage(),
                sourceType(task, children),
                children.stream().map(QuarkIngestTaskChild::getSourceUrl).filter(StringUtils::hasText).distinct().toList(),
                progressSummary(snapshot),
                new QuarkTaskCenterProgressResponse(
                        snapshot.plannedUnits(), snapshot.completedUnits(), snapshot.totalFiles(), snapshot.processedFiles(),
                        snapshot.renamedFiles(), snapshot.ignoredFiles(), snapshot.failedFiles(), snapshot.unknownFiles()),
                needsError(snapshot, task.getMessage()),
                childResponses,
                attempts,
                logs,
                hasOlder,
                hasNewer,
                isActive(snapshot.status()),
                snapshot.subscriptionEnabled(),
                isTerminal(snapshot.status()) ? task.getUpdatedAt() : null,
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }

    private QuarkTaskCenterChildResponse toChildResponse(QuarkIngestTaskChild child) {
        List<QuarkTaskCenterFileResponse> files = fileMapper.selectList(
                        new LambdaQueryWrapper<QuarkIngestTaskFile>()
                                .eq(QuarkIngestTaskFile::getChildTaskId, child.getId())
                                .orderByAsc(QuarkIngestTaskFile::getId))
                .stream()
                .map(file -> new QuarkTaskCenterFileResponse(
                        file.getId(), file.getSourceName(), file.getTargetName(), file.getStatus(),
                        file.getFailureReason(), file.getCreatedAt(), file.getUpdatedAt()))
                .toList();
        return new QuarkTaskCenterChildResponse(
                child.getId(), child.getTaskName(), child.getSourceUrl(), child.getSavePath(), child.getVersionLabel(),
                child.getStatus(), userFacingLogText(child.getFailureReason()), value(child.getRetryCount()),
                Boolean.TRUE.equals(child.getSubscriptionEnabled()), value(child.getPlannedFileCount()),
                value(child.getProcessedFileCount()), value(child.getRenamedFileCount()), value(child.getIgnoredFileCount()),
                value(child.getFailedFileCount()), value(child.getUnknownFileCount()), files,
                child.getCreatedAt(), child.getUpdatedAt());
    }

    private List<QuarkIngestTaskChild> listChildren(String taskId) {
        return childMapper.selectList(new LambdaQueryWrapper<QuarkIngestTaskChild>()
                .eq(QuarkIngestTaskChild::getAggregateTaskId, taskId)
                .orderByAsc(QuarkIngestTaskChild::getCreatedAt));
    }

    private List<OpenListIngestTaskCenterLogResponse> listLogs(
            String taskId,
            Long beforeId,
            Long afterId,
            int limit
    ) {
        LambdaQueryWrapper<QuarkIngestTaskLog> query = new LambdaQueryWrapper<QuarkIngestTaskLog>()
                .eq(QuarkIngestTaskLog::getTaskId, taskId)
                .orderByAsc(QuarkIngestTaskLog::getId)
                .last("LIMIT " + limit);
        if (beforeId != null) {
            query.lt(QuarkIngestTaskLog::getId, beforeId).orderByDesc(QuarkIngestTaskLog::getId);
        } else if (afterId != null) {
            query.gt(QuarkIngestTaskLog::getId, afterId);
        }
        List<QuarkIngestTaskLog> entries = logMapper.selectList(query);
        if (beforeId != null) {
            entries = entries.stream().sorted(Comparator.comparing(QuarkIngestTaskLog::getId)).toList();
        }
        return entries.stream()
                .map(this::toUserFacingLog)
                .toList();
    }

    private OpenListIngestTaskCenterLogResponse toUserFacingLog(QuarkIngestTaskLog entry) {
        return new OpenListIngestTaskCenterLogResponse(
                entry.getId(),
                entry.getTaskId(),
                entry.getLevel(),
                userFacingLogStage(entry.getStage()),
                userFacingLogText(entry.getMessage()),
                userFacingLogText(entry.getDetail()),
                entry.getCreatedAt());
    }

    private boolean hasOlderLogs(String taskId, List<OpenListIngestTaskCenterLogResponse> logs) {
        if (logs.isEmpty()) return false;
        return logMapper.selectCount(new LambdaQueryWrapper<QuarkIngestTaskLog>()
                .eq(QuarkIngestTaskLog::getTaskId, taskId)
                .lt(QuarkIngestTaskLog::getId, logs.get(0).id())) > 0;
    }

    private boolean hasNewerLogs(String taskId, List<OpenListIngestTaskCenterLogResponse> logs) {
        if (logs.isEmpty()) return false;
        return logMapper.selectCount(new LambdaQueryWrapper<QuarkIngestTaskLog>()
                .eq(QuarkIngestTaskLog::getTaskId, taskId)
                .gt(QuarkIngestTaskLog::getId, logs.get(logs.size() - 1).id())) > 0;
    }

    private void updateAggregateAfterChildren(String taskId) {
        QuarkIngestTask aggregate = taskMapper.selectById(taskId);
        if (aggregate == null) return;
        AggregateSnapshot snapshot = snapshot(aggregate, listChildren(taskId));
        String stage = isActive(snapshot.status()) ? "processing" :
                isTerminal(snapshot.status()) ? "completed" : "failed";
        updateAggregate(taskId, snapshot.status(), stage, progressSummary(snapshot));
    }

    private void updateAggregate(String taskId, String status, String stage, String message) {
        QuarkIngestTask update = new QuarkIngestTask();
        update.setId(taskId);
        update.setStatus(status);
        update.setStage(stage);
        update.setMessage(message);
        update.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(update);
    }

    private void updateAttempt(String attemptId, String status, String message) {
        QuarkIngestTaskAttempt update = new QuarkIngestTaskAttempt();
        update.setId(attemptId);
        update.setStatus(status);
        update.setMessage(message);
        update.setEndedAt(LocalDateTime.now());
        attemptMapper.updateById(update);
    }

    private void writeLog(String taskId, String level, String stage, String message, String detail) {
        QuarkIngestTaskLog entry = new QuarkIngestTaskLog();
        entry.setTaskId(taskId);
        entry.setLevel(level);
        entry.setStage(stage);
        String safeMessage = userFacingLogText(message);
        String safeDetail = userFacingLogText(detail);
        entry.setMessage(safeMessage == null ? "" : safeMessage.length() > 1024 ? safeMessage.substring(0, 1023) + "…" : safeMessage);
        entry.setDetail(safeDetail == null ? null : safeDetail.length() > 4000 ? safeDetail.substring(0, 3999) + "…" : safeDetail);
        entry.setCreatedAt(LocalDateTime.now());
        logMapper.insert(entry);
    }

    private QuarkIngestTask getAccessibleTask(String taskId, User user) {
        QuarkIngestTask task = taskMapper.selectById(taskId);
        if (task == null || (!isAdmin(user) && !user.getId().equals(task.getCreatedByUserId()))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "任务不存在", HttpStatus.NOT_FOUND);
        }
        return task;
    }

    private AggregateSnapshot snapshot(QuarkIngestTask task, List<QuarkIngestTaskChild> children) {
        if (children.isEmpty()) {
            String status = normalizeStatus(task.getStatus());
            return new AggregateSnapshot(
                    status,
                    0,
                    0,
                    value(task.getPlannedTaskCount()),
                    value(task.getCreatedTaskCount()),
                    0,
                    0,
                    0,
                    status.equals("FAILED") ? value(task.getPlannedTaskCount()) : 0,
                    0,
                    false
            );
        }
        int completed = 0;
        int totalFiles = 0;
        int processedFiles = 0;
        int renamedFiles = 0;
        int ignoredFiles = 0;
        int failedFiles = 0;
        int unknownFiles = 0;
        boolean subscribed = false;
        boolean active = false;
        boolean failure = false;
        for (QuarkIngestTaskChild child : children) {
            String status = child.getStatus() == null ? "PENDING" : child.getStatus();
            if ("SUCCEEDED".equals(status)) completed++;
            if (ACTIVE_CHILD_STATUSES.contains(status)) active = true;
            if (RETRYABLE_CHILD_STATUSES.contains(status)) failure = true;
            totalFiles += value(child.getPlannedFileCount());
            processedFiles += value(child.getProcessedFileCount());
            renamedFiles += value(child.getRenamedFileCount());
            ignoredFiles += value(child.getIgnoredFileCount());
            failedFiles += value(child.getFailedFileCount());
            unknownFiles += value(child.getUnknownFileCount());
            subscribed |= Boolean.TRUE.equals(child.getSubscriptionEnabled());
        }
        String status;
        if (active) status = "IN_PROGRESS";
        else if (failure && completed > 0) status = "PARTIAL_SUCCESS";
        else if (failure) status = "FAILED";
        else if (completed == children.size()) status = "SUCCEEDED";
        else status = normalizeStatus(task.getStatus());
        return new AggregateSnapshot(status, children.size(), completed, children.size(), processedFiles,
                renamedFiles, ignoredFiles, failedFiles, unknownFiles, totalFiles, subscribed);
    }

    private boolean matches(
            QuarkTaskCenterItemResponse item,
            String view,
            String productType,
            String sourceType,
            String subscription,
            String keyword
    ) {
        if (!ALL.equals(view) && !viewMatches(item.status(), view)) return false;
        if (!ALL.equals(productType) && !productType.equals(item.productType())) return false;
        if (!ALL.equals(sourceType) && !sourceType.equals(item.sourceType())) return false;
        if ("SUBSCRIBED".equals(subscription) && !item.subscriptionEnabled()) return false;
        if ("ONE_TIME".equals(subscription) && item.subscriptionEnabled()) return false;
        return keyword.isEmpty() || item.title().toLowerCase(Locale.ROOT).contains(keyword)
                || item.sourceType().toLowerCase(Locale.ROOT).contains(keyword);
    }

    private int filteredTotal(List<QuarkTaskCenterItemResponse> items, String view) {
        return ALL.equals(view) ? items.size() : (int) items.stream().filter(item -> viewMatches(item.status(), view)).count();
    }

    private int countByView(List<QuarkTaskCenterItemResponse> items, String view) {
        return (int) items.stream().filter(item -> viewMatches(item.status(), view)).count();
    }

    private boolean viewMatches(String status, String view) {
        if (IN_PROGRESS.equals(view)) return isActive(status);
        if (NEEDS_ATTENTION.equals(view)) return "FAILED".equals(status) || "PARTIAL_SUCCESS".equals(status)
                || "UNKNOWN".equals(status) || "INTERRUPTED".equals(status);
        if (SUCCEEDED.equals(view)) return "SUCCEEDED".equals(status);
        return true;
    }

    private String sourceType(QuarkIngestTask task, List<QuarkIngestTaskChild> children) {
        if (StringUtils.hasText(task.getSourceType())) return task.getSourceType();
        return "MANUAL_QUARK";
    }

    private String normalizeProductType(String mediaType) {
        return "VARIETY".equalsIgnoreCase(mediaType) ? "VARIETY" :
                "MOVIE".equalsIgnoreCase(mediaType) ? "MOVIE" : "SERIES";
    }

    private String normalizeStatus(String status) {
        if (status == null) return "IN_PROGRESS";
        if ("COMPLETED".equalsIgnoreCase(status) || "SUCCEEDED".equalsIgnoreCase(status)) return "SUCCEEDED";
        if ("PARTIAL".equalsIgnoreCase(status)) return "PARTIAL_SUCCESS";
        if ("FAILED".equalsIgnoreCase(status)) return "FAILED";
        if ("INTERRUPTED".equalsIgnoreCase(status)) return "INTERRUPTED";
        return "IN_PROGRESS";
    }

    private String progressSummary(AggregateSnapshot snapshot) {
        if (snapshot.plannedUnits() > 0) {
            return "已完成 " + snapshot.completedUnits() + "/" + snapshot.plannedUnits() + " 个季度/版本"
                    + (snapshot.totalFiles() > 0 ? "；文件 " + snapshot.processedFiles() + "/" + snapshot.totalFiles() : "");
        }
        return "已提交处理任务";
    }

    private String needsError(AggregateSnapshot snapshot, String fallback) {
        if ("FAILED".equals(snapshot.status()) || "PARTIAL_SUCCESS".equals(snapshot.status())) return userFacingLogText(fallback);
        return null;
    }

    private int attemptCount(String taskId) {
        return attemptMapper.selectCount(new LambdaQueryWrapper<QuarkIngestTaskAttempt>()
                .eq(QuarkIngestTaskAttempt::getAggregateTaskId, taskId)).intValue();
    }

    private boolean isActive(String status) { return "IN_PROGRESS".equals(status); }
    private boolean isTerminal(String status) { return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "PARTIAL_SUCCESS".equals(status); }
    private boolean isAdmin(User user) { return user != null && ADMIN_ROLE.equalsIgnoreCase(user.getRole()); }
    private int value(Integer number) { return number == null ? 0 : number; }
    private String nullToEmpty(String value) { return value == null ? "" : value; }
    private String safeMessage(String message) { return StringUtils.hasText(message) ? message : "上游服务未返回错误原因"; }

    private String userFacingLogText(String value) {
        return value == null ? null : value.replaceAll("(?i)QAS", "入库服务");
    }

    private String userFacingLogStage(String stage) {
        return "qas_running".equalsIgnoreCase(stage) ? "processing" : userFacingLogText(stage);
    }

    private String normalizeFilter(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : fallback;
    }

    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null) return DEFAULT_PAGE_SIZE;
        if (pageSize == 10 || pageSize == 20 || pageSize == 50) return pageSize;
        throw badRequest("每页条数只能是 10、20 或 50");
    }

    private int normalizeLogLimit(Integer limit) {
        int normalized = limit == null ? DEFAULT_LOG_LIMIT : limit;
        if (normalized < 0 || normalized > MAX_LOG_LIMIT) throw badRequest("日志条数需在 0 到 200 之间");
        return normalized;
    }

    private BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, HttpStatus.BAD_REQUEST);
    }

    @PreDestroy
    void shutdownRetryExecutor() { retryExecutor.shutdownNow(); }

    private record AggregateSnapshot(
            String status,
            int plannedUnits,
            int completedUnits,
            int totalUnits,
            int processedFiles,
            int renamedFiles,
            int ignoredFiles,
            int failedFiles,
            int unknownFiles,
            int totalFiles,
            boolean subscriptionEnabled
    ) { }
}
