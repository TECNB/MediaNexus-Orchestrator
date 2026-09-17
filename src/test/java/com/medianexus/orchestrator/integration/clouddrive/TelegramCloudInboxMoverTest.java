package com.medianexus.orchestrator.integration.clouddrive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medianexus.orchestrator.config.CloudDrive2Properties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TelegramCloudInboxMoverTest {
    @TempDir Path root;

    @Test
    void countsNestedFilesAndMovesTopLevelEntriesWhenExpectedCountIsPresent() throws IOException {
        Path inbox = Files.createDirectories(root.resolve("My Telegram"));
        Path target = Files.createDirectories(root.resolve("Media/Adult/Other/电报"));
        Path album = Files.createDirectories(inbox.resolve("album"));
        Files.writeString(album.resolve("one.mp4"), "one");
        Files.writeString(album.resolve("two.jpg"), "two");
        Files.writeString(inbox.resolve("single.mp4"), "single");
        CloudDrive2FileOperations fileOperations = mock(CloudDrive2FileOperations.class);
        when(fileOperations.list("/WebDAV/My Telegram", true)).thenReturn(List.of(
                new CloudDrive2FileEntry("album", "/WebDAV/My Telegram/album", 0, true),
                new CloudDrive2FileEntry("single.mp4", "/WebDAV/My Telegram/single.mp4", 6, false)
        ));
        when(fileOperations.list("/WebDAV/My Telegram/album", true)).thenReturn(List.of(
                new CloudDrive2FileEntry("one.mp4", "/WebDAV/My Telegram/album/one.mp4", 3, false),
                new CloudDrive2FileEntry("two.jpg", "/WebDAV/My Telegram/album/two.jpg", 3, false)
        ));
        TelegramCloudInboxMover mover = new TelegramCloudInboxMover(fileOperations, properties());

        assertEquals(3, mover.countInboxFiles());
        TelegramCloudInboxMover.MoveOutcome outcome = mover.awaitExpectedFilesAndMove(0, 3);

        assertEquals(2, outcome.movedEntryCount());
        assertEquals(3, outcome.movedFileCount());
        assertTrue(Files.exists(target.resolve("album/one.mp4")));
        assertTrue(Files.exists(target.resolve("single.mp4")));
        assertFalse(Files.exists(inbox.resolve("album")));
    }

    @Test
    void rejectsInboxContentBeyondTheExpectedCount() throws IOException {
        Path inbox = Files.createDirectories(root.resolve("My Telegram"));
        Files.createDirectories(root.resolve("Media/Adult/Other/电报"));
        Files.writeString(inbox.resolve("one.mp4"), "one");
        Files.writeString(inbox.resolve("two.mp4"), "two");
        CloudDrive2FileOperations fileOperations = mock(CloudDrive2FileOperations.class);
        when(fileOperations.list("/WebDAV/My Telegram", true)).thenReturn(List.of(
                new CloudDrive2FileEntry("one.mp4", "/WebDAV/My Telegram/one.mp4", 3, false),
                new CloudDrive2FileEntry("two.mp4", "/WebDAV/My Telegram/two.mp4", 3, false)
        ));
        TelegramCloudInboxMover mover = new TelegramCloudInboxMover(fileOperations, properties());

        assertThrows(CloudDrive2ClientException.class, () -> mover.awaitExpectedFilesAndMove(0, 1));
    }

    @Test
    void countsForcedCloudDriveListingWhenMountedDirectoryIsStale() throws IOException {
        Files.createDirectories(root.resolve("My Telegram"));
        Files.createDirectories(root.resolve("Media/Adult/Other/电报"));
        CloudDrive2FileOperations fileOperations = mock(CloudDrive2FileOperations.class);
        when(fileOperations.list("/WebDAV/My Telegram", true)).thenReturn(List.of(
                new CloudDrive2FileEntry("remote.mp4", "/WebDAV/My Telegram/remote.mp4", 3, false)
        ));
        TelegramCloudInboxMover mover = new TelegramCloudInboxMover(fileOperations, properties());

        assertEquals(1, mover.countInboxFiles());
        verify(fileOperations).list("/WebDAV/My Telegram", true);
    }

    private CloudDrive2Properties properties() {
        CloudDrive2Properties properties = new CloudDrive2Properties();
        properties.setMediaSourcePathPrefix(root.toString());
        properties.setVisibilityTimeout(Duration.ofSeconds(1));
        properties.setVisibilityPollInterval(Duration.ofMillis(1));
        return properties;
    }
}
