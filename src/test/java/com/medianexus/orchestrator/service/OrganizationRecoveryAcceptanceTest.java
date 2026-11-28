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
    final OrganizationMediaReadability readability = mock(OrganizationMediaReadability.class);
    final OrganizationRecoveryJournal.Payload payload;
    OrganizationRecoveryAcceptanceTest() {
        org.springframework.test.util.ReflectionTestUtils.setField(acceptance, "readability", readability);
        when(targets.ownsMediaSourcePath(anyString(), anyString())).thenReturn(true);
        when(targets.bindingForPath("MOVIE", "/ingest/Movie")).thenReturn(Optional.of(new IngestTargetRegistry.CategoryBinding(
                "target", "B", "MOVIE", "/ingest", "uuid", "{}", "/strm", "library")));
        when(targets.cloudMediaPaths(anyString(), anyString())).thenReturn(path -> "/source/video.mkv".equals(path) ? "/cloud/video.mkv" : null);
        when(links.strmFiles(Path.of("/strm/Movie"))).thenReturn(List.of("/strm/Movie/video.strm"));
        when(links.resolveOwnedMediaSourcePath("/strm/Movie/video.strm")).thenReturn("/source/video.mkv");
        var plan = new LibraryOrganizationPlan("/ingest/Movie", List.of(), List.of(), List.of(), Set.of("video.mkv"), Set.of());
        var snapshot = new Snapshot(plan, "mapping", Map.of("/ingest/Movie/video.mkv", "/cloud/video.mkv"),
                List.of(new FileEvidence("/ingest/Movie/video.mkv", "/ingest/Movie/video.mkv", "/ingest/Movie/video.mkv", "official", "a".repeat(40), 100)));
        payload = new OrganizationRecoveryJournal.Payload(snapshot, Map.of(), "hash", new OpenListClient.OfflineDirectory("account", "root", "/Movie"),
                1, 0, 1, 1171145, null, true, null);
        when(emby.organizationMoviesAtPaths("library", Set.of("/strm/Movie/video.strm"))).thenReturn(List.of(
                new EmbyMovieIdentitySnapshot("id", "/strm/Movie/video.strm", "1171145", 2026,
                        Set.of("/source/video.mkv"), true, "revision")));
    }
    @Test void mutatingPlanStillChecksOriginalDirectoryIdentityBeforeAnyAdmission() {
        var plan = new LibraryOrganizationPlan("/ingest/Movie", List.of(new LibraryOrganizationPlan.RenameOperation(
                "/ingest/Movie/old.mkv", "video.mkv")), List.of(), List.of(), Set.of("video.mkv"), Set.of());
        var snapshot = new Snapshot(plan, payload.snapshot().namespaceProof(), payload.snapshot().cloudPaths(), payload.snapshot().files());
        var changing = new OrganizationRecoveryJournal.Payload(snapshot, payload.actions(), payload.hash(), payload.directory(),
                1, 0, 1, payload.tmdbId(), null, false, null);
        doThrow(new EmbyClientException("Emby 身份冲突；禁止自动覆盖")).when(emby)
                .requireOrganizationPathUnconflicted("library", "/strm/Movie", 1171145, null);
        assertThatThrownBy(() -> acceptance.validateCapturedTarget("movie", changing)).isInstanceOf(EmbyClientException.class);
        verify(emby, never()).organizationMoviesAtPaths(anyString(), anySet());
    }

    @Test void requiresExactStrmContentAndProviderProof() {
        acceptance.verify("movie", payload);
        verify(emby).requireOrganizationIdentity("id", 1171145, null);
        when(links.resolveOwnedMediaSourcePath(anyString())).thenReturn("/source/unrelated.mkv");
        assertThatThrownBy(() -> acceptance.verify("movie", payload)).hasMessageContaining("STRM");
    }
    @Test void wrongEmbyIdentityCannotCompleteOrAutoOverride() {
        doThrow(new EmbyClientException("identity mismatch")).when(emby).requireOrganizationIdentity("id", 1171145, null);
        assertThatThrownBy(() -> acceptance.verify("movie", payload)).isInstanceOf(EmbyClientException.class);
        verify(emby, never()).refreshLibrary(anyString());
    }
    @Test void wrongExactPathIdentityIsClassifiedAndMixedSourcesAreIsolated() {
        var wrong = new EmbyMovieIdentitySnapshot("id", "/strm/Movie/video.strm", "210577", 2014,
                Set.of("/source/video.mkv"), true, "revision");
        when(emby.organizationMoviesAtPaths(anyString(), anySet())).thenReturn(List.of(wrong));
        assertThatThrownBy(() -> acceptance.verify("movie", payload)).isInstanceOf(OrganizationVerificationException.class)
                .hasMessage("EMBY_IDENTITY_MISMATCH");
        var mixed = new EmbyMovieIdentitySnapshot("id", wrong.path(), wrong.tmdbId(), wrong.year(),
                Set.of("/source/video.mkv", "/unrelated/other.mkv"), true, "revision");
        when(emby.organizationMoviesAtPaths(anyString(), anySet())).thenReturn(List.of(mixed));
        assertThatThrownBy(() -> acceptance.verify("movie", payload)).hasMessage("EMBY_MEDIA_SOURCES_CONFLICT");
        verify(emby, never()).applyRemoteMetadata(anyString(), any(), anyBoolean());
    }

    @Test void repairRefusesChangedEtagOrLockedItemBeforeApply() {
        var before = new EmbyMovieIdentitySnapshot("id", "/strm/Movie/video.strm", "210577", 2014,
                Set.of("/source/video.mkv"), true, "revision");
        when(emby.organizationMoviesAtPaths(anyString(), anySet())).thenReturn(List.of(before));
        when(emby.organizationMovieIdentity("id")).thenReturn(new EmbyMovieIdentitySnapshot("id", before.path(),
                before.tmdbId(), before.year(), before.mediaPaths(), false, "changed"));
        assertThatThrownBy(() -> acceptance.applyMovieRepair(payload, before, Set.of(before.path()), mock(EmbyRemoteSearchCandidate.class)))
                .hasMessage("EMBY_REPAIR_ITEM_CHANGED");
        verify(emby, never()).applyRemoteMetadata(anyString(), any(), anyBoolean());
    }

    @Test void absentIndexWaitsWhileLockedMismatchIsImmediatelyIsolated() {
        when(emby.organizationMoviesAtPaths(anyString(), anySet())).thenReturn(List.of());
        assertThatThrownBy(() -> acceptance.verify("movie", payload)).hasMessage("EMBY_INDEX_PENDING");
        when(emby.organizationMoviesAtPaths(anyString(), anySet())).thenReturn(List.of(new EmbyMovieIdentitySnapshot(
                "id", "/strm/Movie/video.strm", "999", 2014, Set.of("/source/video.mkv"), false, "revision")));
        assertThatThrownBy(() -> acceptance.verify("movie", payload)).hasMessage("EMBY_IDENTITY_LOCKED_OR_INCOMPLETE");
    }

    @Test void zeroBytePlaybackReadCannotCompleteDespiteCorrectIndex() {
        doThrow(new IllegalStateException("MEDIA_READ_NOT_READY")).when(readability).requireReadable("/source/video.mkv", 100);
        assertThatThrownBy(() -> acceptance.verify("movie", payload)).hasMessage("MEDIA_READ_NOT_READY");
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
