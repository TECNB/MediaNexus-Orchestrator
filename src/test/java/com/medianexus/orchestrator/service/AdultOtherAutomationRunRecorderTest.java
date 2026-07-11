package com.medianexus.orchestrator.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medianexus.orchestrator.dto.emby.response.AdultOtherAutomationCollectionResponse;
import com.medianexus.orchestrator.dto.emby.response.AdultOtherAutomationRunResponse;
import com.medianexus.orchestrator.mapper.AdultOtherAutomationRunMapper;
import com.medianexus.orchestrator.mapper.AdultOtherAutomationRunCollectionMapper;
import com.medianexus.orchestrator.mapper.AdultOtherAutomationRunItemMapper;
import com.medianexus.orchestrator.model.AdultOtherAutomationRun;
import com.medianexus.orchestrator.model.AdultOtherAutomationRunCollection;
import com.medianexus.orchestrator.model.AdultOtherAutomationRunItem;
import com.medianexus.orchestrator.integration.emby.EmbyItemState;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AdultOtherAutomationRunRecorderTest {

    @Test
    void persistsNewItemProgressAndCompletion() {
        AdultOtherAutomationRunMapper mapper = mock(AdultOtherAutomationRunMapper.class);
        AdultOtherAutomationRunItemMapper itemMapper = mock(AdultOtherAutomationRunItemMapper.class);
        AdultOtherAutomationRunCollectionMapper collectionMapper =
                mock(AdultOtherAutomationRunCollectionMapper.class);
        AdultOtherAutomationRunRecorder recorder = new AdultOtherAutomationRunRecorder(
                mapper,
                itemMapper,
                collectionMapper
        );
        ArgumentCaptor<AdultOtherAutomationRun> inserted = ArgumentCaptor.forClass(AdultOtherAutomationRun.class);

        String runId = recorder.start("NEW_ITEMS", 4);
        verify(mapper).insert(inserted.capture());
        AdultOtherAutomationRun run = inserted.getValue();
        when(mapper.selectById(runId)).thenReturn(run);

        recorder.recordScope(runId, 4, 1);
        recorder.recordWaiting(runId);
        recorder.recordNaturalPrimary(runId, 3, 1);
        recorder.recordPrimaryResult(runId, 4, 0);
        recorder.completeNewItems(runId, 2, 1, 3, 3);

        assertEquals("SUCCEEDED", run.getStatus());
        assertEquals("COMPLETED", run.getStage());
        assertEquals(4, run.getTargetItemCount());
        assertEquals(3, run.getNaturalPrimaryReadyCount());
        assertEquals(1, run.getTargetedRefreshCount());
        assertEquals(4, run.getFinalPrimaryReadyCount());
        assertEquals(2, run.getCreatedCollectionCount());
        assertEquals(3, run.getCollectionImageReadyCount());
        assertNotNull(run.getFinishedAt());
        verify(mapper, times(5)).updateById(any(AdultOtherAutomationRun.class));
        verify(mapper).createTableIfNotExists();
        verify(itemMapper).createTableIfNotExists();
        verify(collectionMapper).createTableIfNotExists();
    }

    @Test
    void recordsItemOutcomesAndCollectionDetails() {
        AdultOtherAutomationRunMapper runMapper = mock(AdultOtherAutomationRunMapper.class);
        AdultOtherAutomationRunItemMapper itemMapper = mock(AdultOtherAutomationRunItemMapper.class);
        AdultOtherAutomationRunCollectionMapper collectionMapper =
                mock(AdultOtherAutomationRunCollectionMapper.class);
        AdultOtherAutomationRunRecorder recorder = new AdultOtherAutomationRunRecorder(
                runMapper,
                itemMapper,
                collectionMapper
        );
        EmbyItemState missing = state("item-1", "Missing", false);
        EmbyItemState natural = state("item-2", "Natural", true);

        recorder.recordScopedItems(
                "run-1",
                Set.of("item-1", "item-2", "item-3"),
                List.of(missing, natural),
                Set.of("item-1", "item-2"),
                Map.of("item-1", "Creator", "item-2", "Creator")
        );
        recorder.recordItemResults(
                "run-1",
                List.of(missing, natural),
                Set.of("item-1"),
                List.of(state("item-1", "Missing", true), natural)
        );
        recorder.recordCollections("run-1", List.of(new AdultOtherAutomationCollectionResponse(
                "collection-1", "Creator", "CREATE", 2, true,
                "IMAGE_READY", null
        )));

        ArgumentCaptor<AdultOtherAutomationRunItem> scopedItems =
                ArgumentCaptor.forClass(AdultOtherAutomationRunItem.class);
        verify(itemMapper, times(3)).upsert(scopedItems.capture());
        assertEquals(
                Map.of(
                        "item-1", "WAITING_PRIMARY",
                        "item-2", "NATURAL_READY",
                        "item-3", "UNRESOLVED"
                ),
                scopedItems.getAllValues().stream().collect(java.util.stream.Collectors.toMap(
                        AdultOtherAutomationRunItem::getEmbyItemId,
                        AdultOtherAutomationRunItem::getStatus
                ))
        );
        verify(itemMapper).updateResult("run-1", "item-1", true, true, "REFRESHED", null);
        verify(itemMapper).updateResult("run-1", "item-2", false, true, "NATURAL_READY", null);

        ArgumentCaptor<AdultOtherAutomationRunCollection> collection =
                ArgumentCaptor.forClass(AdultOtherAutomationRunCollection.class);
        verify(collectionMapper).insert(collection.capture());
        assertEquals("Creator", collection.getValue().getCollectionName());
        assertEquals("IMAGE_READY", collection.getValue().getStatus());
    }

    @Test
    void returnsItemAndCollectionDetailsWithRecentRuns() {
        AdultOtherAutomationRunMapper runMapper = mock(AdultOtherAutomationRunMapper.class);
        AdultOtherAutomationRunItemMapper itemMapper = mock(AdultOtherAutomationRunItemMapper.class);
        AdultOtherAutomationRunCollectionMapper collectionMapper =
                mock(AdultOtherAutomationRunCollectionMapper.class);
        AdultOtherAutomationRunRecorder recorder = new AdultOtherAutomationRunRecorder(
                runMapper,
                itemMapper,
                collectionMapper
        );
        AdultOtherAutomationRun run = new AdultOtherAutomationRun();
        run.setId("run-1");
        run.setTriggerType("NEW_ITEMS");
        run.setStatus("SUCCEEDED");
        run.setStage("COMPLETED");
        AdultOtherAutomationRunItem item = new AdultOtherAutomationRunItem();
        item.setRunId("run-1");
        item.setEmbyItemId("item-1");
        item.setItemName("Example");
        item.setStatus("REFRESHED");
        AdultOtherAutomationRunCollection collection = new AdultOtherAutomationRunCollection();
        collection.setRunId("run-1");
        collection.setEmbyCollectionId("collection-1");
        collection.setCollectionName("Creator");
        collection.setAction("CREATE");
        collection.setStatus("IMAGE_READY");
        when(runMapper.selectById("run-1")).thenReturn(run);
        when(itemMapper.selectByRunIds(List.of("run-1"))).thenReturn(List.of(item));
        when(collectionMapper.selectByRunIds(List.of("run-1"))).thenReturn(List.of(collection));

        AdultOtherAutomationRunResponse result = recorder.details("run-1");

        assertEquals("Example", result.items().get(0).itemName());
        assertEquals("REFRESHED", result.items().get(0).status());
        assertEquals("Creator", result.collections().get(0).collectionName());
        assertEquals("IMAGE_READY", result.collections().get(0).status());
    }

    private EmbyItemState state(String id, String name, boolean primary) {
        return new EmbyItemState(
                id,
                name,
                "Movie",
                "/srv/media/STRM/Adult/Other/7.11/Creator/" + id + ".strm",
                primary,
                primary ? 2 : 0
        );
    }
}
