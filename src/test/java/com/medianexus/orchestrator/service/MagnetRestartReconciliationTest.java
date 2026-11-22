package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.medianexus.orchestrator.config.OpenListProperties;
import com.medianexus.orchestrator.integration.openlist.OpenListClient;
import com.medianexus.orchestrator.integration.openlist.OpenListOfflineTaskInfo;
import com.medianexus.orchestrator.mapper.*;
import com.medianexus.orchestrator.model.*;
import com.medianexus.orchestrator.support.IngestTargetFixtures;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MagnetRestartReconciliationTest {
    @Test void terminalOriginalTaskIsRecordedWithoutRetryWhileUnknownRemainsBlocked() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "movie"), MovieMagnetIngestTask.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "series"), SeriesMagnetIngestTask.class);
        var movies = mock(MovieMagnetIngestTaskMapper.class);
        var series = mock(SeriesMagnetIngestTaskMapper.class);
        var logs = mock(SeriesMagnetIngestTaskLogMapper.class);
        var client = mock(OpenListClient.class);
        var scheduler = mock(OfflineIngestScheduler.class);
        var executor = mock(ExecutorService.class);
        var service = new MagnetIngestService(movies, mock(MovieMagnetIngestTaskLogMapper.class), series, logs,
                client, new OpenListProperties(), new MovieSeriesFileRenameService(), null, null, null,
                null, null, null, null, IngestTargetFixtures.legacy(), executor, mock(IngestTaskNotifier.class));
        ReflectionTestUtils.setField(service, "offlineScheduler", scheduler);
        var stopped = task("stopped", "upstream-1");
        var running = task("running", "upstream-2");
        var mismatched = task("mismatched", "upstream-3");
        var unavailable = task("unavailable", "upstream-4");
        var completed = task("completed", "upstream-5");
        var missing = task("missing", null);
        when(series.selectList(any())).thenReturn(List.of(stopped, running, mismatched, missing, unavailable, completed));
        when(movies.selectList(any())).thenReturn(List.of());
        when(series.selectById("stopped")).thenReturn(stopped);
        when(client.offlineTaskInfo("upstream-1")).thenReturn(new OpenListOfflineTaskInfo("upstream-1", 7, "Saving", 76, null, "authorization expired"));
        when(client.offlineTaskInfo("upstream-2")).thenReturn(new OpenListOfflineTaskInfo("upstream-2", 1, "Saving", 76, null, null));
        when(client.offlineTaskInfo("upstream-4")).thenThrow(new IllegalStateException("read unavailable"));
        when(client.offlineTaskInfo("upstream-5")).thenReturn(new OpenListOfflineTaskInfo("upstream-5", 2, "Complete", 100, null, null));
        when(client.offlineTaskInfo("upstream-3")).thenReturn(new OpenListOfflineTaskInfo("another-id", 7, "Failed", 76, null, null));
        when(series.selectCount(any())).thenReturn(0L, 1L);
        service.markUnfinishedTasksInterrupted();
        verify(scheduler).blockUnresolvedRestart(anyString());
        var captured = org.mockito.ArgumentCaptor.forClass(SeriesMagnetIngestTaskLog.class);
        verify(logs).insert(captured.capture());
        assertThat(captured.getValue().getTaskId()).isEqualTo("stopped");
        assertThat(captured.getValue().getDetail()).contains("state=7", "upstream-1").doesNotContain("OFFLINE_UNRESOLVED");
        verify(client, never()).retryOfflineTask(anyString());
        verify(client, never()).cancelOfflineTask(anyString());
        verify(client, never()).addOfflineDownload(anyString(), anyString());
        verifyNoInteractions(executor);
        when(series.selectList(any())).thenReturn(List.of(stopped));
        when(series.selectCount(any())).thenReturn(0L);
        clearInvocations(scheduler);
        service.markUnfinishedTasksInterrupted();
        verify(scheduler, never()).blockUnresolvedRestart(anyString());
    }

    private static SeriesMagnetIngestTask task(String id, String upstream) {
        var task = new SeriesMagnetIngestTask();
        task.setId(id); task.setStatus("INTERRUPTED"); task.setOpenlistTaskId(upstream);
        task.setErrorMessage("[OFFLINE_UNRESOLVED] restarted");
        return task;
    }
}
