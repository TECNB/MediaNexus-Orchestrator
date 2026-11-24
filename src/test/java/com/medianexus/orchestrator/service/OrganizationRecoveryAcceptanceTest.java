package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.config.CloudDrive2Properties;
import com.medianexus.orchestrator.integration.emby.*;
import com.medianexus.orchestrator.integration.openlist.OpenListClient;
import com.medianexus.orchestrator.service.organization.DurableLibraryOrganizer.*;
import com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan;
import com.medianexus.orchestrator.service.settings.targets.IngestTargetRegistry;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrganizationRecoveryAcceptanceTest {
    final EmbyClient emby = mock(EmbyClient.class);
    final IngestTargetRegistry targets = mock(IngestTargetRegistry.class);
    final LocalStrmDeletion links = mock(LocalStrmDeletion.class);
    final OrganizationRecoveryAcceptance acceptance = new OrganizationRecoveryAcceptance(emby, targets, links,
            new CloudDrive2Properties(), mock(AutoSymlinkRefreshService.class), new MovieSeriesFileRenameService());
    final OrganizationRecoveryJournal.Payload payload;
    OrganizationRecoveryAcceptanceTest() {
        when(targets.bindingForPath("MOVIE", "/ingest/Movie")).thenReturn(Optional.of(new IngestTargetRegistry.CategoryBinding(
                "target", "B", "MOVIE", "/ingest", "uuid", "{}", "/strm", "library")));
        when(targets.cloudMediaPaths(anyString(), anyString())).thenReturn(path -> "/source/video.mkv".equals(path) ? "/cloud/video.mkv" : null);
        when(links.strmFiles(Path.of("/strm/Movie"))).thenReturn(List.of("/strm/Movie/video.strm"));
        when(links.resolveMediaSourcePath("/strm/Movie/video.strm")).thenReturn("/source/video.mkv");
        var plan = new LibraryOrganizationPlan("/ingest/Movie", List.of(), List.of(), List.of(), Set.of("video.mkv"), Set.of());
        var snapshot = new Snapshot(plan, "mapping", Map.of("/ingest/Movie/video.mkv", "/cloud/video.mkv"),
                List.of(new FileEvidence("/ingest/Movie/video.mkv", "/ingest/Movie/video.mkv", "/ingest/Movie/video.mkv", "official", "a".repeat(40), 100)));
        payload = new OrganizationRecoveryJournal.Payload(snapshot, Map.of(), "hash", new OpenListClient.OfflineDirectory("account", "root", "/Movie"),
                1, 0, 1, 1171145, null, true, null);
        when(emby.findMoviesByTmdbId(1171145)).thenReturn(List.of(new EmbyCatalogItem("id", "Movie", "Movie", "/strm/Movie/video.strm", null)));
    }
    @Test void requiresExactStrmContentAndProviderProof() {
        acceptance.verify("movie", payload);
        verify(emby).requireOrganizationIdentity("id", 1171145, null);
        when(links.resolveMediaSourcePath(anyString())).thenReturn("/source/unrelated.mkv");
        assertThatThrownBy(() -> acceptance.verify("movie", payload)).hasMessageContaining("STRM");
    }
    @Test void wrongEmbyIdentityCannotCompleteOrAutoOverride() {
        doThrow(new EmbyClientException("identity mismatch")).when(emby).requireOrganizationIdentity("id", 1171145, null);
        assertThatThrownBy(() -> acceptance.verify("movie", payload)).isInstanceOf(EmbyClientException.class);
        verify(emby, never()).refreshLibrary(anyString());
    }
    OrganizationRecoveryJournal.Payload provenance(String status) {
        var evidence = new OrganizationRecoveryJournal.FrozenTaskEvidence("Movie", "Movie", 2026, 1171145, null, "release", "table", status);
        return new OrganizationRecoveryJournal.Payload(payload.snapshot(), payload.actions(), payload.hash(), payload.directory(),
                1, 0, 1, 1171145, null, true, evidence);
    }

    @Test void normalCompletionCommitsOnceWhileHistoricalFailureRemainsUntouched() {
        com.baomidou.mybatisplus.core.MybatisConfiguration configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        for (Class<?> type : List.of(com.medianexus.orchestrator.model.MovieMagnetIngestTask.class,
                com.medianexus.orchestrator.model.MovieMagnetIngestTaskLog.class))
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, ""), type);
        var tasks = mock(com.medianexus.orchestrator.mapper.MovieMagnetIngestTaskMapper.class);
        var logs = mock(com.medianexus.orchestrator.mapper.MovieMagnetIngestTaskLogMapper.class);
        var notifier = mock(IngestTaskNotifier.class);
        org.springframework.test.util.ReflectionTestUtils.setField(acceptance, "movieTasks", tasks);
        org.springframework.test.util.ReflectionTestUtils.setField(acceptance, "movieLogs", logs);
        org.springframework.test.util.ReflectionTestUtils.setField(acceptance, "notifier", notifier);
        var task = new com.medianexus.orchestrator.model.MovieMagnetIngestTask(); task.setId("id"); task.setStatus("ORGANIZING");
        when(tasks.selectById("id")).thenReturn(task);
        when(tasks.update(any())).thenAnswer(call -> { task.setStatus("SUCCEEDED"); return 1; });
        acceptance.verify("movie", provenance("ORGANIZING"));
        acceptance.recordCompletion("movie", "id", provenance("ORGANIZING"));
        acceptance.recordCompletion("movie", "id", provenance("ORGANIZING"));
        verify(tasks, times(1)).update(any()); verify(notifier, times(1)).movieCompleted(task);
        task.setStatus("FAILED");
        acceptance.recordCompletion("movie", "id", provenance("FAILED"));
        assertThat(task.getStatus()).isEqualTo("FAILED");
        verify(tasks, times(1)).update(any()); verify(notifier, times(1)).movieCompleted(task);
    }

    @Test void missingStrmAndWrongIdentityDoNotReachOriginalSuccessCheckpoint() {
        var tasks = mock(com.medianexus.orchestrator.mapper.MovieMagnetIngestTaskMapper.class);
        org.springframework.test.util.ReflectionTestUtils.setField(acceptance, "movieTasks", tasks);
        when(links.strmFiles(any())).thenReturn(List.of());
        assertThatThrownBy(() -> { acceptance.verify("movie", provenance("ORGANIZING"));
            acceptance.recordCompletion("movie", "id", provenance("ORGANIZING")); }).hasMessageContaining("STRM");
        verifyNoInteractions(tasks);
    }
}
