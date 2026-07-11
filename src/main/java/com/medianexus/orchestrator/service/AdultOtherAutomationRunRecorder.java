package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.dto.emby.response.AdultOtherAutomationRunResponse;
import com.medianexus.orchestrator.dto.emby.response.AdultOtherAutomationCollectionResponse;
import com.medianexus.orchestrator.dto.emby.response.AdultOtherAutomationItemResponse;
import com.medianexus.orchestrator.dto.emby.response.AdultOtherCollectionSyncRunResponse;
import com.medianexus.orchestrator.integration.emby.EmbyItemState;
import com.medianexus.orchestrator.mapper.AdultOtherAutomationRunCollectionMapper;
import com.medianexus.orchestrator.mapper.AdultOtherAutomationRunItemMapper;
import com.medianexus.orchestrator.mapper.AdultOtherAutomationRunMapper;
import com.medianexus.orchestrator.model.AdultOtherAutomationRun;
import com.medianexus.orchestrator.model.AdultOtherAutomationRunCollection;
import com.medianexus.orchestrator.model.AdultOtherAutomationRunItem;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AdultOtherAutomationRunRecorder {

    private final AdultOtherAutomationRunMapper mapper;
    private final AdultOtherAutomationRunItemMapper itemMapper;
    private final AdultOtherAutomationRunCollectionMapper collectionMapper;
    private volatile boolean tableReady;

    public AdultOtherAutomationRunRecorder(
            AdultOtherAutomationRunMapper mapper,
            AdultOtherAutomationRunItemMapper itemMapper,
            AdultOtherAutomationRunCollectionMapper collectionMapper
    ) {
        this.mapper = mapper;
        this.itemMapper = itemMapper;
        this.collectionMapper = collectionMapper;
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

    public void recordScopedItems(
            String runId,
            Set<String> requestedIds,
            List<EmbyItemState> fetchedStates,
            Set<String> scopedItemIds,
            Map<String, String> collectionNamesByItemId
    ) {
        ensureTable();
        Map<String, EmbyItemState> statesById = fetchedStates.stream()
                .filter(state -> StringUtils.hasText(state.id()))
                .collect(Collectors.toMap(
                        EmbyItemState::id,
                        state -> state,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        for (String itemId : requestedIds) {
            EmbyItemState state = statesById.get(itemId);
            AdultOtherAutomationRunItem item = new AdultOtherAutomationRunItem();
            item.setId(UUID.randomUUID().toString());
            item.setRunId(runId);
            item.setEmbyItemId(limit(itemId, 64));
            item.setRefreshRequested(false);
            if (state == null) {
                item.setPrimaryBefore(false);
                item.setPrimaryAfter(false);
                item.setStatus("UNRESOLVED");
                item.setMessage("事件中的媒体未能从 Emby 查询到");
            } else {
                item.setItemName(limit(state.name(), 512));
                item.setItemPath(limit(state.path(), 2048));
                item.setCollectionName(limit(collectionNamesByItemId.get(itemId), 512));
                item.setPrimaryBefore(state.hasPrimaryImage());
                item.setPrimaryAfter(state.hasPrimaryImage());
                if (!scopedItemIds.contains(itemId)) {
                    item.setStatus("SKIPPED");
                    item.setMessage("不属于 Adult - Other 电影范围");
                } else {
                    item.setStatus(state.hasPrimaryImage() ? "NATURAL_READY" : "WAITING_PRIMARY");
                }
            }
            itemMapper.upsert(item);
        }
    }

    public void recordItemResults(
            String runId,
            List<EmbyItemState> settledStates,
            Set<String> refreshedItemIds,
            List<EmbyItemState> finalStates
    ) {
        Map<String, EmbyItemState> finalStatesById = finalStates.stream()
                .filter(state -> StringUtils.hasText(state.id()))
                .collect(Collectors.toMap(EmbyItemState::id, state -> state, (left, right) -> left));
        for (EmbyItemState settledState : settledStates) {
            if (!StringUtils.hasText(settledState.id())) {
                continue;
            }
            boolean refreshRequested = refreshedItemIds.contains(settledState.id());
            EmbyItemState finalState = finalStatesById.get(settledState.id());
            boolean primaryAfter = finalState != null && finalState.hasPrimaryImage();
            String status;
            String message = null;
            if (settledState.hasPrimaryImage()) {
                status = "NATURAL_READY";
            } else if (primaryAfter) {
                status = "REFRESHED";
            } else {
                status = "MISSING";
                message = "定向刷新后仍未生成 Primary 封面";
            }
            itemMapper.updateResult(
                    runId,
                    settledState.id(),
                    refreshRequested,
                    primaryAfter,
                    status,
                    message
            );
        }
    }

    public void recordCollections(String runId, List<AdultOtherAutomationCollectionResponse> collections) {
        ensureTable();
        for (AdultOtherAutomationCollectionResponse detail : collections) {
            AdultOtherAutomationRunCollection collection = new AdultOtherAutomationRunCollection();
            collection.setId(UUID.randomUUID().toString());
            collection.setRunId(runId);
            collection.setEmbyCollectionId(limit(detail.embyCollectionId(), 64));
            collection.setCollectionName(limit(detail.collectionName(), 512));
            collection.setAction(limit(detail.action(), 32));
            collection.setAddedItemCount(detail.addedItemCount());
            collection.setImageReady(detail.imageReady());
            collection.setStatus(limit(detail.status(), 32));
            collection.setMessage(limit(detail.message()));
            collectionMapper.insert(collection);
        }
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
                .map(run -> toResponse(run, List.of(), List.of()))
                .toList();
    }

    public AdultOtherAutomationRunResponse details(String runId) {
        ensureTable();
        AdultOtherAutomationRun run = mapper.selectById(runId);
        if (run == null) {
            return null;
        }
        List<String> runIds = List.of(runId);
        List<AdultOtherAutomationItemResponse> items = itemMapper.selectByRunIds(runIds).stream()
                .map(this::toItemResponse)
                .toList();
        List<AdultOtherAutomationCollectionResponse> collections =
                collectionMapper.selectByRunIds(runIds).stream()
                        .map(this::toCollectionResponse)
                        .toList();
        return toResponse(run, items, collections);
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
        itemMapper.createTableIfNotExists();
        collectionMapper.createTableIfNotExists();
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

    private AdultOtherAutomationRunResponse toResponse(
            AdultOtherAutomationRun run,
            List<AdultOtherAutomationItemResponse> items,
            List<AdultOtherAutomationCollectionResponse> collections
    ) {
        return new AdultOtherAutomationRunResponse(
                run.getId(), run.getTriggerType(), run.getStatus(), run.getStage(), run.getEventCount(),
                run.getTargetItemCount(), run.getNaturalPrimaryReadyCount(), run.getTargetedRefreshCount(),
                run.getFinalPrimaryReadyCount(), run.getFinalPrimaryMissingCount(),
                run.getAffectedCollectionCount(), run.getCreatedCollectionCount(), run.getUpdatedCollectionCount(),
                run.getCollectionImageReadyCount(), run.getDeletedCollectionCount(), run.getMessage(),
                run.getStartedAt(), run.getFinishedAt(), items, collections
        );
    }

    private AdultOtherAutomationItemResponse toItemResponse(AdultOtherAutomationRunItem item) {
        return new AdultOtherAutomationItemResponse(
                item.getEmbyItemId(), item.getItemName(), item.getItemPath(), item.getCollectionName(),
                item.getPrimaryBefore(), item.getRefreshRequested(), item.getPrimaryAfter(),
                item.getStatus(), item.getMessage()
        );
    }

    private AdultOtherAutomationCollectionResponse toCollectionResponse(
            AdultOtherAutomationRunCollection collection
    ) {
        return new AdultOtherAutomationCollectionResponse(
                collection.getEmbyCollectionId(), collection.getCollectionName(), collection.getAction(),
                collection.getAddedItemCount(), collection.getImageReady(), collection.getStatus(),
                collection.getMessage()
        );
    }

    private String limit(String message) {
        if (message == null || message.length() <= 1024) {
            return message;
        }
        return message.substring(0, 1024);
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
