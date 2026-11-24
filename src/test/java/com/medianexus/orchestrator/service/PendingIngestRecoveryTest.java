package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.medianexus.orchestrator.config.OpenListProperties;
import com.medianexus.orchestrator.integration.openlist.*;
import com.medianexus.orchestrator.mapper.*;
import com.medianexus.orchestrator.model.*;
import com.medianexus.orchestrator.support.IngestTargetFixtures;
import java.util.concurrent.ExecutorService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class PendingIngestRecoveryTest {
    @Test void pendingRecoveryRevalidatesFileEvidenceWithoutRiskPermissionAndNeverSubmitsOnReadFailure() {
        var movies = mock(MovieMagnetIngestTaskMapper.class);
        var series = mock(SeriesMagnetIngestTaskMapper.class);
        var client = mock(OpenListClient.class);
        var presence = mock(MediaLibraryPresenceService.class);
        var safety = mock(ReleaseSubmissionSafety.class);
        var scheduler = mock(OfflineIngestScheduler.class);
        var service = service(movies, series, client, presence);
        ReflectionTestUtils.setField(service, "offlineScheduler", scheduler);
        ReflectionTestUtils.setField(service, "submissionSafety", safety);
        var task = new SeriesMagnetIngestTask();
        task.setId("original"); task.setStatus("PENDING"); task.setTmdbId(123); task.setSeasonNumber(1);
        task.setTitle("Show"); task.setMagnet("magnet"); task.setSavePath("/show");
        when(presence.checkForAutomation(any())).thenReturn(new MediaLibraryPresenceService.AutomationPresence(true, false, true));
        when(safety.checkSeries(any(), any(), any(), any())).thenThrow(new IllegalStateException("file table unavailable"));
        ReflectionTestUtils.invokeMethod(service, "resumePendingSeries", task);
        var metadata = ArgumentCaptor.forClass(ReleaseIngestMetadata.class);
        verify(safety).checkSeries(eq(123), eq("magnet"), any(), metadata.capture());
        assertThat(metadata.getValue().acceptUnverifiedRisk()).isFalse();
        verifyNoInteractions(scheduler);
        verify(client, never()).addOfflineDownload(anyString(), anyString());
        when(presence.checkForAutomation(any())).thenReturn(new MediaLibraryPresenceService.AutomationPresence(true, true, true));
        clearInvocations(safety);
        ReflectionTestUtils.invokeMethod(service, "resumePendingSeries", task);
        verifyNoInteractions(safety);
        assertThat(task.getStatus()).isEqualTo("PENDING");
    }

    @Test void settlementCanPersistFailedEvenWhenOriginalFailureUpdateWasLostAndRejectsZeroRowUpdate() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "series"), SeriesMagnetIngestTask.class);
        var movies = mock(MovieMagnetIngestTaskMapper.class);
        var series = mock(SeriesMagnetIngestTaskMapper.class);
        var service = service(movies, series, mock(OpenListClient.class), mock(MediaLibraryPresenceService.class));
        var task = new SeriesMagnetIngestTask(); task.setId("original"); task.setStatus("DOWNLOADING"); task.setOpenlistTaskId("upstream");
        when(series.selectById("original")).thenReturn(task);
        when(series.update(any(LambdaUpdateWrapper.class))).thenReturn(1, 0);
        var snapshot = new OpenListOfflineTaskInfo("upstream", 4, "canceled", 1, null, null);
        ReflectionTestUtils.invokeMethod(service, "settleSeriesCancellation", "original", snapshot);
        var wrapper = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(series).update(wrapper.capture());
        assertThat(wrapper.getValue().getSqlSegment()).contains("status", "openlist_task_id");
        assertThat(wrapper.getValue().getParamNameValuePairs().values()).contains("FAILED", "DOWNLOADING", "upstream");
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "settleSeriesCancellation", "original", snapshot))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("保留认领");
    }

    private MagnetIngestService service(MovieMagnetIngestTaskMapper movies, SeriesMagnetIngestTaskMapper series,
            OpenListClient client, MediaLibraryPresenceService presence) {
        return new MagnetIngestService(movies, mock(MovieMagnetIngestTaskLogMapper.class), series,
                mock(SeriesMagnetIngestTaskLogMapper.class), client, new OpenListProperties(), new MovieSeriesFileRenameService(),
                null, null, null, null, null, null, presence, IngestTargetFixtures.legacy(), mock(ExecutorService.class), mock(IngestTaskNotifier.class));
    }
}
