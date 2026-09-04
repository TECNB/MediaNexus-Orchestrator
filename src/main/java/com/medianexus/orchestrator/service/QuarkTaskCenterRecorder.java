package com.medianexus.orchestrator.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.mapper.QuarkIngestTaskAttemptMapper;
import com.medianexus.orchestrator.mapper.QuarkIngestTaskChildMapper;
import com.medianexus.orchestrator.mapper.QuarkIngestTaskFileMapper;
import com.medianexus.orchestrator.model.QuarkIngestTaskAttempt;
import com.medianexus.orchestrator.model.QuarkIngestTaskChild;
import com.medianexus.orchestrator.model.QuarkIngestTaskFile;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Persists the durable Quark task-center projection alongside legacy records. */
@Service
public class QuarkTaskCenterRecorder {

    private final QuarkIngestTaskAttemptMapper attemptMapper;
    private final QuarkIngestTaskChildMapper childMapper;
    private final QuarkIngestTaskFileMapper fileMapper;
    private final ObjectMapper objectMapper;

    public QuarkTaskCenterRecorder(
            QuarkIngestTaskAttemptMapper attemptMapper,
            QuarkIngestTaskChildMapper childMapper,
            QuarkIngestTaskFileMapper fileMapper,
            ObjectMapper objectMapper
    ) {
        this.attemptMapper = attemptMapper;
        this.childMapper = childMapper;
        this.fileMapper = fileMapper;
        this.objectMapper = objectMapper;
    }

    public List<String> recordInitialPlan(
            String aggregateTaskId,
            String mediaType,
            String sourceType,
            List<QasTaskPlan> plans,
            Set<String> subscriptionTaskNames,
            Long createdByUserId
    ) {
        if (!StringUtils.hasText(aggregateTaskId) || plans == null || plans.isEmpty()) {
            return List.of();
        }
        LocalDateTime now = LocalDateTime.now();
        List<String> childIds = new ArrayList<>();
        for (QasTaskPlan plan : plans) {
            String childId = UUID.randomUUID().toString();
            childIds.add(childId);
            QuarkIngestTaskChild child = new QuarkIngestTaskChild();
            child.setId(childId);
            child.setAggregateTaskId(aggregateTaskId);
            child.setTaskName(plan.taskName());
            child.setSourceUrl(plan.sourceUrl());
            child.setSavePath(plan.savePath());
            child.setPattern(plan.pattern());
            child.setReplaceRule(plan.replace());
            child.setVersionLabel(plan.versionLabel());
            child.setStatus("PENDING");
            child.setSeasonNumber(parseSeasonNumber(plan.taskName()));
            child.setRetryCount(0);
            child.setSubscriptionEnabled(subscriptionTaskNames != null
                    && subscriptionTaskNames.contains(plan.taskName()));
            child.setPlannedFileCount(Math.max(plan.matchedFileCount(), plan.renameSamples().size()));
            child.setProcessedFileCount(0);
            child.setRenamedFileCount(0);
            child.setIgnoredFileCount(0);
            child.setFailedFileCount(0);
            child.setUnknownFileCount(0);
            child.setCreatedAt(now);
            child.setUpdatedAt(now);
            childMapper.insert(child);
            for (QasRenameSample sample : plan.renameSamples()) {
                QuarkIngestTaskFile file = new QuarkIngestTaskFile();
                file.setChildTaskId(childId);
                file.setSourceName(sample.sourceName());
                file.setTargetName(sample.targetName());
                file.setStatus("PENDING");
                file.setCreatedAt(now);
                file.setUpdatedAt(now);
                fileMapper.insert(file);
            }
        }

        QuarkIngestTaskAttempt attempt = new QuarkIngestTaskAttempt();
        attempt.setId(UUID.randomUUID().toString());
        attempt.setAggregateTaskId(aggregateTaskId);
        attempt.setAttemptNo(1);
        attempt.setTriggerType("INITIAL");
        attempt.setTargetChildTaskIds(writeChildIds(childIds));
        attempt.setStatus("CREATED");
        attempt.setStartedAt(now);
        attempt.setMessage("Quark 入库任务已创建");
        attempt.setCreatedByUserId(createdByUserId);
        attemptMapper.insert(attempt);
        return List.copyOf(childIds);
    }

    public String createRetryAttempt(
            String aggregateTaskId,
            List<String> childIds,
            Long createdByUserId
    ) {
        List<String> targets = childIds == null ? List.of() : childIds.stream().distinct().toList();
        int latestAttempt = attemptMapper.selectCount(new LambdaQueryWrapper<QuarkIngestTaskAttempt>()
                .eq(QuarkIngestTaskAttempt::getAggregateTaskId, aggregateTaskId)).intValue();
        QuarkIngestTaskAttempt attempt = new QuarkIngestTaskAttempt();
        attempt.setId(UUID.randomUUID().toString());
        attempt.setAggregateTaskId(aggregateTaskId);
        attempt.setAttemptNo(latestAttempt + 1);
        attempt.setTriggerType("RETRY");
        attempt.setTargetChildTaskIds(writeChildIds(targets));
        attempt.setStatus("RUNNING");
        attempt.setStartedAt(LocalDateTime.now());
        attempt.setMessage("正在重试未完成的 Quark 处理项");
        attempt.setCreatedByUserId(createdByUserId);
        attemptMapper.insert(attempt);
        for (String childId : targets) {
            QuarkIngestTaskChild update = new QuarkIngestTaskChild();
            update.setId(childId);
            update.setStatus("PENDING");
            update.setFailureReason(null);
            update.setRetryCount(1);
            update.setUpdatedAt(LocalDateTime.now());
            childMapper.updateById(update);
        }
        return attempt.getId();
    }

    public void markChildStatus(
            String aggregateTaskId,
            String childId,
            String status,
            String failureReason
    ) {
        if (!StringUtils.hasText(aggregateTaskId) || !StringUtils.hasText(childId)) {
            return;
        }
        QuarkIngestTaskChild child = childMapper.selectById(childId);
        if (child == null || !aggregateTaskId.equals(child.getAggregateTaskId())) {
            return;
        }
        QuarkIngestTaskChild update = new QuarkIngestTaskChild();
        update.setId(child.getId());
        update.setStatus(status);
        update.setFailureReason(trimToNull(failureReason));
        update.setUpdatedAt(LocalDateTime.now());
        if ("SUCCEEDED".equals(status)) {
            update.setProcessedFileCount(child.getPlannedFileCount());
            update.setRenamedFileCount(child.getPlannedFileCount());
            update.setFailedFileCount(0);
            update.setUnknownFileCount(0);
        } else if ("FAILED".equals(status)) {
            update.setFailedFileCount(child.getPlannedFileCount());
        } else if ("UNKNOWN".equals(status)) {
            update.setUnknownFileCount(child.getPlannedFileCount());
        }
        childMapper.updateById(update);
    }

    public void markAggregateChildren(
            String aggregateTaskId,
            String status,
            String failureReason
    ) {
        List<QuarkIngestTaskChild> children = childMapper.selectList(new LambdaQueryWrapper<QuarkIngestTaskChild>()
                .eq(QuarkIngestTaskChild::getAggregateTaskId, aggregateTaskId));
        for (QuarkIngestTaskChild child : children) {
            markChildStatus(aggregateTaskId, child.getId(), status, failureReason);
        }
    }

    public void updateSubscription(String aggregateTaskId, String childId, boolean enabled) {
        QuarkIngestTaskChild update = new QuarkIngestTaskChild();
        update.setId(childId);
        update.setSubscriptionEnabled(enabled);
        update.setUpdatedAt(LocalDateTime.now());
        childMapper.updateById(update);
    }

    private String writeChildIds(List<String> childIds) {
        try {
            return objectMapper.writeValueAsString(childIds == null ? List.of() : childIds);
        } catch (Exception ignored) {
            return "[]";
        }
    }

    private Integer parseSeasonNumber(String taskName) {
        if (taskName == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?i)\\bS(\\d{1,3})\\b")
                .matcher(taskName);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    private String trimToNull(String value) {
        String normalized = value == null ? null : value.trim();
        return StringUtils.hasText(normalized) ? normalized : null;
    }
}
