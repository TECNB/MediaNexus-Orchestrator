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
        var canceledIncomplete = task("canceled-incomplete", "upstream-6");
        canceledIncomplete.setStatus("FAILED");
        canceledIncomplete.setErrorMessage("[OFFLINE_UNRESOLVED] 离线下载超时，取消未确认；保留源文件");
        var responseLost = task("response-lost", "upstream-7");
        responseLost.setStatus("FAILED");
        responseLost.setErrorMessage("[OFFLINE_UNRESOLVED] 离线下载超时，取消请求结果未知；保留源文件");
        var missing = task("missing", null);
        when(series.selectList(any())).thenReturn(List.of(stopped, running, mismatched, missing, unavailable, completed, canceledIncomplete, responseLost));
        when(movies.selectList(any())).thenReturn(List.of());
        when(series.selectById("stopped")).thenReturn(stopped);
        when(series.selectById("canceled-incomplete")).thenReturn(canceledIncomplete);
        when(series.selectById("response-lost")).thenReturn(responseLost);
        when(client.offlineTaskInfo("upstream-7")).thenReturn(new OpenListOfflineTaskInfo("upstream-7", 2, "Saving", 1, null, null));
        when(client.offlineTaskInfo("upstream-6")).thenReturn(new OpenListOfflineTaskInfo("upstream-6", 2, "Saving", 88, null, null));
        when(client.offlineTaskInfo("upstream-1")).thenReturn(new OpenListOfflineTaskInfo("upstream-1", 7, "Saving", 76, null, "authorization expired"));
        when(client.offlineTaskInfo("upstream-2")).thenReturn(new OpenListOfflineTaskInfo("upstream-2", 1, "Saving", 76, null, null));
        when(client.offlineTaskInfo("upstream-4")).thenThrow(new IllegalStateException("read unavailable"));
        when(client.offlineTaskInfo("upstream-5")).thenReturn(new OpenListOfflineTaskInfo("upstream-5", 2, "Complete", 100, null, null));
        when(client.offlineTaskInfo("upstream-3")).thenReturn(new OpenListOfflineTaskInfo("another-id", 7, "Failed", 76, null, null));
        when(series.selectCount(any())).thenReturn(0L, 1L);
        service.markUnfinishedTasksInterrupted();
        verify(scheduler).blockUnresolvedRestart(anyString());
        var captured = org.mockito.ArgumentCaptor.forClass(SeriesMagnetIngestTaskLog.class);
        verify(logs, times(3)).insert(captured.capture());
        assertThat(captured.getAllValues()).extracting(SeriesMagnetIngestTaskLog::getTaskId).containsExactly("stopped", "canceled-incomplete", "response-lost");
        assertThat(captured.getAllValues().get(0).getDetail()).contains("state=7", "upstream-1").doesNotContain("OFFLINE_UNRESOLVED");
        assertThat(captured.getAllValues().get(1).getDetail()).contains("state=2", "upstream-6").doesNotContain("OFFLINE_UNRESOLVED");
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

    @Test void onlyPristinePendingTasksResumeOnceWithoutCreatingNewAttempts() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "series"), SeriesMagnetIngestTask.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "movie"), MovieMagnetIngestTask.class);
        var movies = mock(MovieMagnetIngestTaskMapper.class);
        var series = mock(SeriesMagnetIngestTaskMapper.class);
        var client = mock(OpenListClient.class);
        var scheduler = mock(OfflineIngestScheduler.class);
        var executor = mock(ExecutorService.class);
        var service = new MagnetIngestService(movies, mock(MovieMagnetIngestTaskLogMapper.class), series,
                mock(SeriesMagnetIngestTaskLogMapper.class), client, new OpenListProperties(), new MovieSeriesFileRenameService(),
                null, null, null, null, null, null, null, IngestTargetFixtures.legacy(), executor, mock(IngestTaskNotifier.class));
        ReflectionTestUtils.setField(service, "offlineScheduler", scheduler);
        var pending = task("pending", null); pending.setStatus("PENDING"); pending.setErrorMessage(null);
        var unknown = task("unknown", null); unknown.setStatus("PENDING");
        var submitted = task("submitted", "upstream"); submitted.setStatus("PENDING"); submitted.setErrorMessage(null);
        when(series.selectList(any())).thenReturn(List.of(pending, unknown, submitted));
        when(movies.selectList(any())).thenReturn(List.of());
        service.restorePendingRestartTasks();
        service.restorePendingRestartTasks();
        verify(executor, times(1)).submit(any(Runnable.class));
        verify(client, never()).addOfflineDownload(anyString(), anyString());
        verify(series, never()).insert(any(SeriesMagnetIngestTask.class));
    }

    @Test void completedAfterKnownCancellationRetainsLocalClaimsWithoutGlobalPauseOrWrites() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "movie"), MovieMagnetIngestTask.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "series"), SeriesMagnetIngestTask.class);
        var movies = mock(MovieMagnetIngestTaskMapper.class);
        var series = mock(SeriesMagnetIngestTaskMapper.class);
        var movieLogs = mock(MovieMagnetIngestTaskLogMapper.class);
        var seriesLogs = mock(SeriesMagnetIngestTaskLogMapper.class);
        var client = mock(OpenListClient.class);
        var scheduler = mock(OfflineIngestScheduler.class);
        var executor = mock(ExecutorService.class);
        var service = new MagnetIngestService(movies, movieLogs, series, seriesLogs,
                client, new OpenListProperties(), new MovieSeriesFileRenameService(), null, null, null,
                null, null, null, null, IngestTargetFixtures.legacy(), executor, mock(IngestTaskNotifier.class));
        ReflectionTestUtils.setField(service, "offlineScheduler", scheduler);
        var movie = new MovieMagnetIngestTask();
        movie.setId("original-movie"); movie.setStatus("FAILED"); movie.setOpenlistTaskId("movie-upstream");
        movie.setSavePath("/movie"); movie.setMagnetHash("movie-hash");
        movie.setErrorMessage("[OFFLINE_UNRESOLVED] 离线下载超时，取消未确认；原始失败");
        var show = task("original-series", "series-upstream"); show.setStatus("FAILED");
        show.setSavePath("/series"); show.setMagnetHash("series-hash");
        show.setErrorMessage("[OFFLINE_UNRESOLVED] 离线下载超时，取消请求结果未知；原始失败");
        when(movies.selectList(any())).thenReturn(List.of(movie));
        when(series.selectList(any())).thenReturn(List.of(show));
        when(movies.selectById(movie.getId())).thenReturn(movie);
        when(series.selectById(show.getId())).thenReturn(show);
        when(client.offlineTaskInfo("movie-upstream")).thenReturn(new OpenListOfflineTaskInfo("movie-upstream", 2, "Complete", 100, null, null));
        when(client.offlineTaskInfo("series-upstream")).thenReturn(new OpenListOfflineTaskInfo("series-upstream", 2, "Complete", 100, null, null));
        var movieUpdates = new java.util.ArrayList<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper>();
        var seriesUpdates = new java.util.ArrayList<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper>();
        when(movies.update(any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class))).thenAnswer(call -> {
            var update = (com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper) call.getArgument(0);
            update.getSqlSegment(); movieUpdates.add(update); return 1;
        });
        when(series.update(any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class))).thenAnswer(call -> {
            var update = (com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper) call.getArgument(0);
            update.getSqlSegment(); seriesUpdates.add(update); return 1;
        });
        service.markUnfinishedTasksInterrupted();
        var order = inOrder(scheduler, movies, series);
        order.verify(scheduler).retainOrganizationRecovery("movie:original-movie", "/movie", "movie-hash");
        order.verify(movies).update(any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class));
        order.verify(scheduler).retainOrganizationRecovery("series:original-series", "/series", "series-hash");
        order.verify(series).update(any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class));
        assertThat(movieUpdates.get(0).getParamNameValuePairs().values()).anyMatch(value -> value.toString().contains("[ORGANIZATION_UNRESOLVED]"));
        assertThat(seriesUpdates.get(0).getParamNameValuePairs().values()).anyMatch(value -> value.toString().contains("[ORGANIZATION_UNRESOLVED]"));
        var movieLog = org.mockito.ArgumentCaptor.forClass(MovieMagnetIngestTaskLog.class);
        var seriesLog = org.mockito.ArgumentCaptor.forClass(SeriesMagnetIngestTaskLog.class);
        verify(movieLogs).insert(movieLog.capture()); verify(seriesLogs).insert(seriesLog.capture());
        assertThat(movieLog.getValue().getDetail()).contains(movie.getErrorMessage());
        assertThat(seriesLog.getValue().getDetail()).contains(show.getErrorMessage());
        verify(scheduler, never()).blockUnresolvedRestart(anyString());
        verify(client, times(1)).offlineTaskInfo("movie-upstream");
        verify(client, times(1)).offlineTaskInfo("series-upstream");
        verifyNoMoreInteractions(client);
        verifyNoInteractions(executor);
    }

    private static SeriesMagnetIngestTask task(String id, String upstream) {
        var task = new SeriesMagnetIngestTask();
        task.setId(id); task.setStatus("INTERRUPTED"); task.setOpenlistTaskId(upstream);
        task.setErrorMessage("[OFFLINE_UNRESOLVED] restarted");
        return task;
    }
}
