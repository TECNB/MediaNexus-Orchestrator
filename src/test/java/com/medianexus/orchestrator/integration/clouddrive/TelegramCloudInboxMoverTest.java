package com.medianexus.orchestrator.integration.clouddrive;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.medianexus.orchestrator.config.CloudDrive2Properties;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TelegramCloudInboxMoverTest {
    private static final String INBOX = "/WebDAV/My Telegram";
    private static final String TARGET = "/WebDAV/Media/Adult/Other/电报";

    @Test
    void movesAvailableAndManualFilesWithoutAMountOrExpectedCount() {
        CloudDrive2FileOperations api = mock(CloudDrive2FileOperations.class);
        CloudDrive2FileEntry file = new CloudDrive2FileEntry("manual.mp4", INBOX + "/manual.mp4", 123, false);
        when(api.list(INBOX, true)).thenReturn(List.of(file), List.of(file), List.of());
        when(api.moveKeepingBoth(file.fullPath(), TARGET)).thenReturn(List.of(TARGET + "/manual.mp4"));
        List<TelegramCloudInboxMover.FileDelivery> recorded = new ArrayList<>();
        var outcome = new TelegramCloudInboxMover(api, new CloudDrive2Properties()).drainInbox(List.of(), recorded::add);
        assertEquals(1, outcome.movedFileCount());
        assertEquals("MOVED", recorded.get(0).status());
        assertNull(recorded.get(0).key());
        verify(api).moveKeepingBoth(file.fullPath(), TARGET);
    }

    @Test
    void oneMoveFailureDoesNotBlockOtherEntries() {
        CloudDrive2FileOperations api = mock(CloudDrive2FileOperations.class);
        var bad = new CloudDrive2FileEntry("bad.mp4", INBOX + "/bad.mp4", 1, false);
        var good = new CloudDrive2FileEntry("good.mp4", INBOX + "/good.mp4", 2, false);
        when(api.list(INBOX, true)).thenReturn(List.of(bad, good), List.of(bad, good), List.of(bad));
        when(api.moveKeepingBoth(bad.fullPath(), TARGET)).thenThrow(new IllegalStateException("temporary API error"));
        when(api.moveKeepingBoth(good.fullPath(), TARGET)).thenReturn(List.of(TARGET + "/good.mp4"));
        List<TelegramCloudInboxMover.FileDelivery> recorded = new ArrayList<>();
        var outcome = new TelegramCloudInboxMover(api, new CloudDrive2Properties()).drainInbox(List.of(), recorded::add);
        assertEquals(1, outcome.movedFileCount());
        assertEquals(List.of("PENDING", "MOVED"), recorded.stream().map(TelegramCloudInboxMover.FileDelivery::status).toList());
    }

    @Test
    void aLateVideoUsesSavedCaptionAndAnInterruptedRenameIsNotPrefixedAgain() {
        CloudDrive2FileOperations api = mock(CloudDrive2FileOperations.class);
        String name = "系列作品 - clip(1).mp4";
        var file = new CloudDrive2FileEntry(name, INBOX + "/" + name, 100, false);
        when(api.list(INBOX, true)).thenReturn(List.of(file), List.of(file), List.of());
        when(api.moveKeepingBoth(file.fullPath(), TARGET)).thenReturn(List.of(TARGET + "/" + name));
        var expected = new TelegramCloudInboxMover.ExpectedVideo("channel:1", "clip.mp4", 100, "系列作品", name);
        List<TelegramCloudInboxMover.FileDelivery> recorded = new ArrayList<>();
        new TelegramCloudInboxMover(api, new CloudDrive2Properties()).drainInbox(List.of(expected), recorded::add);
        assertEquals("channel:1", recorded.get(0).key());
        verify(api, never()).rename(any());
    }

    @Test
    void captionIsAddedBeforeMovingAndConflictPathReturnedByApiIsPreserved() {
        CloudDrive2FileOperations api = mock(CloudDrive2FileOperations.class);
        var file = new CloudDrive2FileEntry("clip.mp4", INBOX + "/clip.mp4", 100, false);
        when(api.list(INBOX, true)).thenReturn(List.of(file), List.of(file), List.of());
        when(api.moveKeepingBoth(INBOX + "/系列作品 - clip.mp4", TARGET))
                .thenReturn(List.of(TARGET + "/系列作品 - clip(1).mp4"));
        var expected = new TelegramCloudInboxMover.ExpectedVideo("channel:1", "clip.mp4", 100, "系列作品", null);
        List<TelegramCloudInboxMover.FileDelivery> recorded = new ArrayList<>();
        new TelegramCloudInboxMover(api, new CloudDrive2Properties()).drainInbox(List.of(expected), recorded::add);
        verify(api).rename(List.of(new CloudDrive2RenameOperation(file.fullPath(), "系列作品 - clip.mp4")));
        assertTrue(recorded.get(0).libraryPath().endsWith("clip(1).mp4"));
    }

    @Test
    void ambiguousMetadataKeepsOriginalName() {
        CloudDrive2FileOperations api = mock(CloudDrive2FileOperations.class);
        var file = new CloudDrive2FileEntry("clip.mp4", INBOX + "/clip.mp4", 100, false);
        when(api.list(INBOX, true)).thenReturn(List.of(file), List.of(file), List.of());
        when(api.moveKeepingBoth(file.fullPath(), TARGET)).thenReturn(List.of(TARGET + "/clip.mp4"));
        var one = new TelegramCloudInboxMover.ExpectedVideo("a:1", "clip.mp4", 100, "标题一", null);
        var two = new TelegramCloudInboxMover.ExpectedVideo("b:1", "clip.mp4", 100, "标题二", null);
        new TelegramCloudInboxMover(api, new CloudDrive2Properties()).drainInbox(List.of(one, two), ignored -> {});
        verify(api, never()).rename(any());
    }

    @Test
    void emptyApiMoveReplyConfirmsNewConflictNameWithoutRejectingExistingFile() {
        CloudDrive2FileOperations api = mock(CloudDrive2FileOperations.class);
        var source = new CloudDrive2FileEntry("same.mp4", INBOX + "/same.mp4", 100, false);
        var old = new CloudDrive2FileEntry("same.mp4", TARGET + "/same.mp4", 100, false);
        var added = new CloudDrive2FileEntry("same(1).mp4", TARGET + "/same(1).mp4", 100, false);
        when(api.list(INBOX, true)).thenReturn(List.of(source), List.of(source), List.of());
        when(api.list(TARGET, true)).thenReturn(List.of(old), List.of(old), List.of(old, added));
        when(api.moveKeepingBoth(source.fullPath(), TARGET)).thenReturn(List.of());
        List<TelegramCloudInboxMover.FileDelivery> recorded = new ArrayList<>();
        var outcome = new TelegramCloudInboxMover(api, new CloudDrive2Properties()).drainInbox(List.of(), recorded::add);
        assertEquals(1, outcome.movedFileCount());
        assertEquals(added.fullPath(), recorded.get(0).libraryPath());
        assertEquals("same(1).mp4", recorded.get(0).savedName());
    }

    @Test
    void alreadyMovedVideoIsReconciledWithoutAnotherMove() {
        CloudDrive2FileOperations api = mock(CloudDrive2FileOperations.class);
        var accepted = new CloudDrive2FileEntry("系列 - clip.mp4", TARGET + "/系列 - clip.mp4", 100, false);
        when(api.list(TARGET, true)).thenReturn(List.of(accepted));
        var expected = new TelegramCloudInboxMover.ExpectedVideo("a:1", "clip.mp4", 100, "系列", "系列 - clip.mp4");
        List<TelegramCloudInboxMover.FileDelivery> recorded = new ArrayList<>();
        new TelegramCloudInboxMover(api, new CloudDrive2Properties()).drainInbox(List.of(expected), recorded::add);
        assertEquals("MOVED", recorded.get(0).status());
        assertEquals(accepted.fullPath(), recorded.get(0).libraryPath());
        verify(api, never()).moveKeepingBoth(anyString(), anyString());
    }

    @Test
    void acknowledgedMoveWaitsForAncestorRefreshInsteadOfResending() {
        CloudDrive2FileOperations api = mock(CloudDrive2FileOperations.class);
        var source = new CloudDrive2FileEntry("late.mp4", INBOX + "/late.mp4", 100, false);
        var accepted = new CloudDrive2FileEntry("late.mp4", TARGET + "/late.mp4", 100, false);
        when(api.list(INBOX, true)).thenReturn(List.of(source), List.of(source), List.of());
        when(api.list(TARGET, true)).thenReturn(List.of(), List.of(), List.of(), List.of(), List.of(accepted));
        when(api.moveKeepingBoth(source.fullPath(), TARGET)).thenReturn(List.of());
        CloudDrive2Properties config = new CloudDrive2Properties();
        config.setVisibilityTimeout(java.time.Duration.ofSeconds(1));
        config.setVisibilityPollInterval(java.time.Duration.ofMillis(1));
        List<TelegramCloudInboxMover.FileDelivery> recorded = new ArrayList<>();
        var outcome = new TelegramCloudInboxMover(api, config).drainInbox(List.of(), recorded::add);
        assertEquals(1, outcome.movedFileCount());
        assertEquals("MOVED", recorded.get(0).status());
        verify(api, times(1)).moveKeepingBoth(source.fullPath(), TARGET);
        verify(api, atLeast(2)).list("/WebDAV/Media/Adult/Other", true);
    }

    @Test
    void readableTitleKeepsCompleteOriginalNameAndFitsUtf8Limit() {
        assertEquals("夏晴子 10月新作 - 69rt48er.mp4", TelegramCloudInboxMover.readableName("夏晴子 10月新作\n点击链接", "69rt48er.mp4"));
        assertEquals("系列作品 - clip(1).mp4", TelegramCloudInboxMover.readableName("系列作品", "clip(1).mp4"));
        String original = "长".repeat(70) + ".mp4";
        String name = TelegramCloudInboxMover.readableName("标题".repeat(30), original);
        assertTrue(name.endsWith(original));
        assertTrue(name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 240);
        assertEquals("clip.mp4", TelegramCloudInboxMover.readableName("", "clip.mp4"));
    }
}
