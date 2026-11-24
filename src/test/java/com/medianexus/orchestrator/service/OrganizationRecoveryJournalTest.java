package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.integration.clouddrive.CloudDrive2LibraryOrganizer;
import com.medianexus.orchestrator.integration.openlist.OpenListClient;
import com.medianexus.orchestrator.mapper.OrganizationJournalMapper;
import com.medianexus.orchestrator.mapper.OrganizationJournalMapper.Row;
import com.medianexus.orchestrator.service.organization.DurableLibraryOrganizer.*;
import com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrganizationRecoveryJournalTest {
    final OrganizationJournalMapper db = mock(OrganizationJournalMapper.class);
    final OfflineIngestScheduler lane = mock(OfflineIngestScheduler.class);
    final OpenListClient openList = mock(OpenListClient.class);
    final CloudDrive2LibraryOrganizer files = mock(CloudDrive2LibraryOrganizer.class);
    final OrganizationRecoveryAcceptance acceptance = mock(OrganizationRecoveryAcceptance.class);
    final ObjectMapper json = new ObjectMapper();
    final LibraryOrganizationPlan plan = new LibraryOrganizationPlan("/library", List.of(), List.of(), List.of(), Set.of("video.mkv"), Set.of());
    final Snapshot snapshot = new Snapshot(plan, "mapping", Map.of("/library/video.mkv", "/cloud/video.mkv"),
            List.of(new FileEvidence("/library/video.mkv", "/library/video.mkv", "/library/video.mkv", "official", "a".repeat(40), 100)));
    final OrganizationRecoveryJournal.FrozenTaskEvidence evidence = new OrganizationRecoveryJournal.FrozenTaskEvidence("title", "original", 2026, 1, null, "release", "frozen file table");
    final AtomicReference<Row> row = new AtomicReference<>();
    final OrganizationRecoveryJournal journal;

    OrganizationRecoveryJournalTest() {
        when(files.capture(plan)).thenReturn(snapshot);
        when(openList.offlineDirectoryForPath(anyString())).thenReturn(new OpenListClient.OfflineDirectory("account", "root", "/library"));
        when(db.insert(anyString(), anyString(), anyString(), anyString())).thenAnswer(c -> {
            row.set(new Row(c.getArgument(0), c.getArgument(1), c.getArgument(2), "FILES", "RUNNING", 0, c.getArgument(3), 0, 0, null)); return 1;
        });
        when(db.get(anyString(), anyString())).thenAnswer(c -> row.get());
        when(db.checkpoint(any(), anyString(), anyString(), anyString(), anyLong(), anyInt(), nullable(String.class))).thenAnswer(c -> {
            Row previous = c.getArgument(0);
            if (row.get().version() != previous.version()) return 0;
            row.set(new Row(previous.taskType(), previous.taskId(), c.getArgument(1), c.getArgument(2), c.getArgument(3),
                    previous.version() + 1, previous.ownerToken(), c.getArgument(4), c.getArgument(5), c.getArgument(6))); return 1;
        });
        journal = new OrganizationRecoveryJournal(db, json, lane, openList, files, acceptance);
    }
    void organize() { journal.organize("movie", "id", plan, 1, 0, 1, 1, null, "hash", evidence); }

    @Test void foreignFirstPageDoesNotStarveNinthOwnerAcrossTicks() {
        organize();
        Row own = row.get();
        List<Row> foreign = new ArrayList<>();
        for (int index = 0; index < 8; index++) foreign.add(new Row("movie", "foreign" + index, own.payload(), "REFRESH", "WAITING", 0,
                "foreign-owner", 0, 0, null));
        when(db.due(anyLong(), anyString(), anyString())).thenAnswer(call -> ((String) call.getArgument(2)).isEmpty() ? foreign : List.of(own));
        org.springframework.test.util.ReflectionTestUtils.setField(journal, "ready", true);
        journal.dispatch(); journal.dispatch();
        verify(lane).enqueueOrganizationRecovery(eq("movie:id"), eq("id"), eq("/library"), eq("hash"), any());
        verify(db, never()).claim(any(), anyString());
    }

    @Test void originalCompletionFailureKeepsVerifyAndClaimsUntilCheckpointSucceeds() {
        organize();
        when(acceptance.refresh(anyString(), anyString())).thenReturn(AutoSymlinkRefreshService.RefreshOutcome.submitted("accepted"));
        doThrow(new org.springframework.dao.TransientDataAccessResourceException("database down")).doNothing()
                .when(acceptance).recordCompletion(anyString(), anyString(), any());
        journal.refreshAfterOrganization("movie", "id");
        assertThat(row.get().phase()).isEqualTo("VERIFY");
        verify(lane, never()).completeOrganizationRecovery(anyString(), any(OpenListClient.OfflineDirectory.class), anyString());
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(journal, "recover", "movie", "id");
        assertThat(row.get().status()).isEqualTo("COMPLETE");
        verify(files, times(1)).resume(any(), anyMap(), any());
        verify(acceptance, times(1)).refresh(anyString(), anyString());
    }

    @Test void planInsertFailureMeansNoFileWrites() {
        doThrow(new IllegalStateException("database down")).when(db).insert(anyString(), anyString(), anyString(), anyString());
        assertThatThrownBy(this::organize).isInstanceOf(IllegalStateException.class);
        verify(files, never()).resume(any(), anyMap(), any());
    }
    @Test void lostOwnershipBeforeIntentMeansNoAuthorizedWrite() {
        doAnswer(c -> {
            Checkpoint checkpoint = c.getArgument(2);
            row.set(new Row("movie", "id", row.get().payload(), "FILES", "RUNNING", 99, "another", 0, 0, null));
            checkpoint.save("rename", ActionState.SENT);
            throw new AssertionError("checkpoint must fail before remote call");
        }).when(files).resume(any(), anyMap(), any());
        assertThatThrownBy(this::organize).isInstanceOf(IllegalStateException.class);
        verify(acceptance, never()).refresh(anyString(), anyString());
    }
    @Test void filesArePersistedBeforeRefreshAndOnlyKnownUnsentRefreshMayRetry() {
        organize();
        assertThat(row.get().phase()).isEqualTo("REFRESH");
        when(acceptance.refresh(anyString(), anyString())).thenReturn(AutoSymlinkRefreshService.RefreshOutcome.skipped("not sent"),
                AutoSymlinkRefreshService.RefreshOutcome.submitted("accepted"));
        journal.refreshAfterOrganization("movie", "id");
        assertThat(row.get().phase()).isEqualTo("REFRESH");
        journal.refreshAfterOrganization("movie", "id");
        assertThat(row.get().status()).isEqualTo("COMPLETE");
        verify(files, times(1)).resume(any(), anyMap(), any());
        verify(lane).completeOrganizationRecovery("movie:id", new OpenListClient.OfflineDirectory("account", "root", "/library"), "hash");
        verify(acceptance, times(2)).refresh(anyString(), anyString());
    }
    @Test void unknownRefreshOutcomeIsObservedWithoutResubmission() {
        organize();
        when(acceptance.refresh(anyString(), anyString())).thenReturn(AutoSymlinkRefreshService.RefreshOutcome.failed("unknown"));
        doThrow(new IllegalStateException("not visible")).doNothing().when(acceptance).verify(anyString(), any());
        journal.refreshAfterOrganization("movie", "id");
        assertThat(row.get().phase()).isEqualTo("VERIFY");
        journal.refreshAfterOrganization("movie", "id");
        verify(acceptance, times(1)).refresh(anyString(), anyString());
        verify(files, times(1)).resume(any(), anyMap(), any());
        assertThat(journal.status("movie", "id").videos()).isEqualTo(1);
        assertThat(row.get().payload()).contains("frozen file table").doesNotContain("raw_url", "https://");
    }
    @Test void mappingAccountChangeBlocksRefresh() {
        organize();
        when(openList.offlineDirectoryForPath(anyString())).thenReturn(new OpenListClient.OfflineDirectory("other", "root", "/library"));
        journal.refreshAfterOrganization("movie", "id");
        verify(acceptance, never()).refresh(anyString(), anyString());
        assertThat(row.get().status()).isEqualTo("WAITING");
    }
    void replaceOwner(String owner) {
        Row previous = row.get();
        row.set(new Row(previous.taskType(), previous.taskId(), previous.payload(), previous.phase(), previous.status(),
                previous.version(), owner, previous.nextCheck(), previous.attempt(), previous.lastError()));
    }

    @Test void retiredOwnerAcrossNamespacesCanBeClaimedWithoutReplayingFiles() {
        organize();
        replaceOwner("RETIRED:foreign-namespace:1:old-process");
        when(db.claim(any(), anyString())).thenAnswer(call -> {
            Row previous = call.getArgument(0);
            replaceOwner(call.getArgument(1));
            return previous.version() == row.get().version() ? 1 : 0;
        });
        when(acceptance.refresh(anyString(), anyString())).thenReturn(AutoSymlinkRefreshService.RefreshOutcome.submitted("accepted"));
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(journal, "recover", "movie", "id");
        verify(db).claim(any(), anyString());
        verify(files, times(1)).resume(any(), anyMap(), any());
        assertThat(row.get().status()).isEqualTo("COMPLETE");
    }

    @Test void foreignOwnerWithoutRetirementRemainsReadonlyAndReportsManualCheck() {
        organize();
        replaceOwner("foreign-namespace:1:old-process");
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(journal, "recover", "movie", "id");
        verify(db, never()).claim(any(), anyString());
        verify(acceptance, never()).refresh(anyString(), anyString());
        verify(files, times(1)).resume(any(), anyMap(), any());
        assertThat(journal.status("movie", "id").status()).isEqualTo("MANUAL_CHECK");
        assertThat(journal.status("movie", "id").lastError()).isEqualTo("OWNER_NOT_RETIRED");
    }

    @Test void retirementRequiresDrainAndBothRemoteQueuesQuiet() {
        organize();
        when(lane.quiesceOrganizationRecovery()).thenReturn(true);
        when(db.unfinished()).thenAnswer(call -> List.of(row.get()));
        journal.retireAfterQuiescence();
        var order = inOrder(lane, openList, files, db);
        order.verify(lane).quiesceOrganizationRecovery();
        order.verify(db).unfinished();
        order.verify(openList).requireOrganizationQueuesQuiet();
        order.verify(files).requireRecoveryWritersQuiet();
        order.verify(db).retireOwner(row.get().ownerToken());
        assertThatThrownBy(this::organize).hasMessageContaining("停止");
    }

    @Test void failedDrainNeverPersistsRetirement() {
        organize();
        when(lane.quiesceOrganizationRecovery()).thenReturn(false);
        journal.retireAfterQuiescence();
        verify(db, never()).retireOwner(anyString());
        verify(files, never()).requireRecoveryWritersQuiet();
    }

    @Test void remoteQueueReadFailureNeverPersistsRetirement() {
        organize();
        when(lane.quiesceOrganizationRecovery()).thenReturn(true);
        when(db.unfinished()).thenAnswer(call -> List.of(row.get()));
        doThrow(new IllegalStateException("queue unknown")).when(files).requireRecoveryWritersQuiet();
        journal.retireAfterQuiescence();
        verify(db, never()).retireOwner(anyString());
    }
}
