package com.medianexus.orchestrator.integration.clouddrive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.medianexus.orchestrator.config.CloudDrive2Properties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
        TelegramCloudInboxMover mover = new TelegramCloudInboxMover(properties());

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
        TelegramCloudInboxMover mover = new TelegramCloudInboxMover(properties());

        assertThrows(CloudDrive2ClientException.class, () -> mover.awaitExpectedFilesAndMove(0, 1));
    }

    private CloudDrive2Properties properties() {
        CloudDrive2Properties properties = new CloudDrive2Properties();
        properties.setMediaSourcePathPrefix(root.toString());
        properties.setVisibilityTimeout(Duration.ofSeconds(1));
        properties.setVisibilityPollInterval(Duration.ofMillis(1));
        return properties;
    }
}
