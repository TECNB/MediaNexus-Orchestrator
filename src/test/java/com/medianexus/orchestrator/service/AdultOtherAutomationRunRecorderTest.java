package com.medianexus.orchestrator.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medianexus.orchestrator.mapper.AdultOtherAutomationRunMapper;
import com.medianexus.orchestrator.model.AdultOtherAutomationRun;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AdultOtherAutomationRunRecorderTest {

    @Test
    void persistsNewItemProgressAndCompletion() {
        AdultOtherAutomationRunMapper mapper = mock(AdultOtherAutomationRunMapper.class);
        AdultOtherAutomationRunRecorder recorder = new AdultOtherAutomationRunRecorder(mapper);
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
    }
}
