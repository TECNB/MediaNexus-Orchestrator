package com.medianexus.orchestrator.integration.clouddrive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.medianexus.orchestrator.config.CloudDrive2Properties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TelegramCloudInboxMoverTest {

    @Test
    void countsNestedFilesAndMovesTopLevelEntriesWhenExpectedCountIsPresent() {
        FakeOperations operations = new FakeOperations();
        operations.put("/WebDAV/My Telegram", List.of(
                entry("album", "/WebDAV/My Telegram/album", true),
                entry("single.mp4", "/WebDAV/My Telegram/single.mp4", false)
        ));
        operations.put("/WebDAV/My Telegram/album", List.of(
                entry("one.mp4", "/WebDAV/My Telegram/album/one.mp4", false),
                entry("two.jpg", "/WebDAV/My Telegram/album/two.jpg", false)
        ));
        operations.put("/WebDAV/Media/Adult/Other/电报", new ArrayList<>());
        TelegramCloudInboxMover mover = new TelegramCloudInboxMover(operations, properties());

        assertEquals(3, mover.countInboxFiles());
        TelegramCloudInboxMover.MoveOutcome outcome = mover.awaitExpectedFilesAndMove(0, 3);

        assertEquals(2, outcome.movedEntryCount());
        assertEquals(3, outcome.movedFileCount());
        assertEquals(2, operations.movedPaths.size());
    }

    @Test
    void rejectsInboxContentBeyondTheExpectedCount() {
        FakeOperations operations = new FakeOperations();
        operations.put("/WebDAV/My Telegram", List.of(
                entry("one.mp4", "/WebDAV/My Telegram/one.mp4", false),
                entry("two.mp4", "/WebDAV/My Telegram/two.mp4", false)
        ));
        TelegramCloudInboxMover mover = new TelegramCloudInboxMover(operations, properties());

        assertThrows(
                CloudDrive2ClientException.class,
                () -> mover.awaitExpectedFilesAndMove(0, 1)
        );
    }

    private static CloudDrive2Properties properties() {
        CloudDrive2Properties properties = new CloudDrive2Properties();
        properties.setCloudDrivePathPrefix("/WebDAV");
        properties.setVisibilityTimeout(Duration.ofSeconds(1));
        properties.setVisibilityPollInterval(Duration.ofMillis(1));
        return properties;
    }

    private static CloudDrive2FileEntry entry(String name, String path, boolean directory) {
        return new CloudDrive2FileEntry(name, path, 1, directory);
    }

    private static final class FakeOperations implements CloudDrive2FileOperations {
        private final Map<String, List<CloudDrive2FileEntry>> entries = new LinkedHashMap<>();
        private final List<String> movedPaths = new ArrayList<>();

        void put(String path, List<CloudDrive2FileEntry> value) {
            entries.put(path, new ArrayList<>(value));
        }

        @Override
        public List<CloudDrive2FileEntry> list(String path, boolean forceRefresh) {
            return List.copyOf(entries.getOrDefault(path, List.of()));
        }

        @Override
        public void move(List<String> sourcePaths, String targetDirectory) {
            movedPaths.addAll(sourcePaths);
            List<CloudDrive2FileEntry> source = entries.get("/WebDAV/My Telegram");
            List<CloudDrive2FileEntry> target = entries.computeIfAbsent(targetDirectory, ignored -> new ArrayList<>());
            target.addAll(source);
            source.clear();
        }

        @Override
        public void rename(List<CloudDrive2RenameOperation> operations) {
        }

        @Override
        public void delete(List<String> paths) {
        }
    }
}
