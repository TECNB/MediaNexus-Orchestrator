package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.dto.emby.response.AdultOtherAutomationRunResponse;
import com.medianexus.orchestrator.dto.emby.response.AdultOtherCollectionSyncRunResponse;
import com.medianexus.orchestrator.mapper.AdultOtherAutomationRunMapper;
import com.medianexus.orchestrator.model.AdultOtherAutomationRun;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

@Service
public class AdultOtherAutomationRunRecorder {

    private final AdultOtherAutomationRunMapper mapper;
    private volatile boolean tableReady;

    public AdultOtherAutomationRunRecorder(AdultOtherAutomationRunMapper mapper) {
        this.mapper = mapper;
    }

    public String start(String triggerType, int eventCount) {
        ensureTable();
        AdultOtherAutomationRun run = new AdultOtherAutomationRun();
        run.setId(UUID.randomUUID().toString());
        run.setTriggerType(triggerType);
        run.setStatus("RUNNING");
        run.setStage("SCOPING");
        run.setEventCount(eventCount);
        initializeCounts(run);
        run.setStartedAt(LocalDateTime.now());
        mapper.insert(run);
        return run.getId();
    }

    public void recordScope(String runId, int targetCount, int readyCount) {
        update(runId, run -> {
            run.setStage("RECONCILING");
            run.setTargetItemCount(targetCount);
            run.setNaturalPrimaryReadyCount(readyCount);
        });
    }

    public void recordWaiting(String runId) {
        update(runId, run -> run.setStage("WAITING_PRIMARY"));
    }

    public void recordNaturalPrimary(String runId, int readyCount, int refreshCount) {
        update(runId, run -> {
            run.setStage(refreshCount > 0 ? "REFRESHING_PRIMARY" : "VERIFYING_PRIMARY");
            run.setNaturalPrimaryReadyCount(readyCount);
            run.setTargetedRefreshCount(refreshCount);
        });
    }

    public void recordPrimaryResult(String runId, int readyCount, int missingCount) {
        update(runId, run -> {
            run.setStage("FINAL_RECONCILING");
            run.setFinalPrimaryReadyCount(readyCount);
            run.setFinalPrimaryMissingCount(missingCount);
        });
    }

    public void completeNewItems(
            String runId,
            int createdCollectionCount,
            int updatedCollectionCount,
            int affectedCollectionCount,
            int collectionImageReadyCount
    ) {
        update(runId, run -> {
            run.setStatus("SUCCEEDED");
            run.setStage("COMPLETED");
            run.setAffectedCollectionCount(affectedCollectionCount);
            run.setCreatedCollectionCount(createdCollectionCount);
            run.setUpdatedCollectionCount(updatedCollectionCount);
            run.setCollectionImageReadyCount(collectionImageReadyCount);
            run.setMessage("新入库自动化完成");
            run.setFinishedAt(LocalDateTime.now());
        });
    }

    public void completeIgnored(String runId, String message) {
        update(runId, run -> {
            run.setStatus("SUCCEEDED");
            run.setStage("IGNORED");
            run.setMessage(message);
            run.setFinishedAt(LocalDateTime.now());
        });
    }

    public void recordDeletionStarted(String runId) {
        update(runId, run -> run.setStage("CLEANING_COLLECTIONS"));
    }

    public void completeDeletion(String runId, AdultOtherCollectionSyncRunResponse cleanup) {
        update(runId, run -> {
            run.setStatus("SUCCEEDED");
            run.setStage("COMPLETED");
            run.setDeletedCollectionCount(cleanup.deletedCollectionCount());
            run.setMessage("删除事件清理完成，复查 " + cleanup.reviewCollectionCount() + " 个合集");
            run.setFinishedAt(LocalDateTime.now());
        });
    }

    public void fail(String runId, RuntimeException exception) {
        update(runId, run -> {
            run.setStatus("FAILED");
            run.setStage("FAILED");
            run.setMessage(limit(exception.getMessage()));
            run.setFinishedAt(LocalDateTime.now());
        });
    }

    public List<AdultOtherAutomationRunResponse> recent(int limit) {
        ensureTable();
        return mapper.selectRecent(Math.max(1, Math.min(limit, 50))).stream()
                .map(this::toResponse)
                .toList();
    }

    private void update(String runId, Consumer<AdultOtherAutomationRun> change) {
        AdultOtherAutomationRun run = mapper.selectById(runId);
        if (run == null) {
            return;
        }
        change.accept(run);
        mapper.updateById(run);
    }

    private synchronized void ensureTable() {
        if (tableReady) {
            return;
        }
        mapper.createTableIfNotExists();
        tableReady = true;
    }

    private void initializeCounts(AdultOtherAutomationRun run) {
        run.setTargetItemCount(0);
        run.setNaturalPrimaryReadyCount(0);
        run.setTargetedRefreshCount(0);
        run.setFinalPrimaryReadyCount(0);
        run.setFinalPrimaryMissingCount(0);
        run.setAffectedCollectionCount(0);
        run.setCreatedCollectionCount(0);
        run.setUpdatedCollectionCount(0);
        run.setCollectionImageReadyCount(0);
        run.setDeletedCollectionCount(0);
    }

    private AdultOtherAutomationRunResponse toResponse(AdultOtherAutomationRun run) {
        return new AdultOtherAutomationRunResponse(
                run.getId(), run.getTriggerType(), run.getStatus(), run.getStage(), run.getEventCount(),
                run.getTargetItemCount(), run.getNaturalPrimaryReadyCount(), run.getTargetedRefreshCount(),
                run.getFinalPrimaryReadyCount(), run.getFinalPrimaryMissingCount(),
                run.getAffectedCollectionCount(), run.getCreatedCollectionCount(), run.getUpdatedCollectionCount(),
                run.getCollectionImageReadyCount(), run.getDeletedCollectionCount(), run.getMessage(),
                run.getStartedAt(), run.getFinishedAt()
        );
    }

    private String limit(String message) {
        if (message == null || message.length() <= 1024) {
            return message;
        }
        return message.substring(0, 1024);
    }
}
