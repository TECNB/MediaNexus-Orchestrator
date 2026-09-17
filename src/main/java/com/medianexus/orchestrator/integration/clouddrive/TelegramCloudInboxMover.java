package com.medianexus.orchestrator.integration.clouddrive;

import static com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan.join;
import static com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan.normalizePath;

import com.medianexus.orchestrator.config.CloudDrive2Properties;
import io.grpc.Status;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "medianexus.clouddrive2",
        name = "organization-enabled",
        havingValue = "true"
)
public class TelegramCloudInboxMover {

    private static final String INBOX_DIRECTORY = "My Telegram";
    private static final String LIBRARY_DIRECTORY = "Media/Adult/Other/电报";

    private final CloudDrive2FileOperations fileOperations;
    private final CloudDrive2Properties properties;

    TelegramCloudInboxMover(
            CloudDrive2FileOperations fileOperations,
            CloudDrive2Properties properties
    ) {
        this.fileOperations = fileOperations;
        this.properties = properties;
    }

    public int countInboxFiles() {
        return countFiles(inboxPath());
    }

    public MoveOutcome awaitExpectedFilesAndMove(int baselineFileCount, int expectedNewFileCount) {
        if (baselineFileCount < 0 || expectedNewFileCount < 0) {
            throw new IllegalArgumentException("Telegram inbox file counts must not be negative");
        }
        int expectedTotal = Math.addExact(baselineFileCount, expectedNewFileCount);
        Instant deadline = Instant.now().plus(properties.getVisibilityTimeout());
        int actualTotal;
        do {
            actualTotal = countInboxFiles();
            if (actualTotal == expectedTotal) {
                break;
            }
            if (actualTotal > expectedTotal) {
                throw new CloudDrive2ClientException(
                        "Telegram 收件箱文件数超过预期：expected=" + expectedTotal + ", actual=" + actualTotal
                );
            }
            sleep();
        } while (Instant.now().isBefore(deadline));
        if (actualTotal != expectedTotal) {
            throw new CloudDrive2ClientException(
                    "等待 PikPak 保存文件超时：expected=" + expectedTotal + ", actual=" + actualTotal
            );
        }

        List<CloudDrive2FileEntry> entries = list(inboxPath());
        if (entries.isEmpty()) {
            return new MoveOutcome(0, 0, expectedNewFileCount);
        }
        Set<String> movedNames = new LinkedHashSet<>();
        List<String> sourcePaths = entries.stream().map(entry -> {
            movedNames.add(entry.name());
            return entry.fullPath();
        }).toList();
        fileOperations.move(sourcePaths, libraryPath());
        awaitMoveVisible(movedNames);
        return new MoveOutcome(entries.size(), actualTotal, expectedNewFileCount);
    }

    private int countFiles(String rootPath) {
        int count = 0;
        ArrayDeque<String> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        pending.add(normalizePath(rootPath));
        while (!pending.isEmpty()) {
            String directory = pending.removeFirst();
            if (!visited.add(directory)) {
                continue;
            }
            for (CloudDrive2FileEntry entry : list(directory)) {
                if (entry.directory()) {
                    pending.addLast(entry.fullPath());
                } else {
                    count++;
                }
            }
        }
        return count;
    }

    private void awaitMoveVisible(Set<String> movedNames) {
        Instant deadline = Instant.now().plus(properties.getVisibilityTimeout());
        do {
            List<CloudDrive2FileEntry> sourceEntries = list(inboxPath());
            Set<String> targetNames = list(libraryPath()).stream()
                    .map(CloudDrive2FileEntry::name)
                    .collect(LinkedHashSet::new, Set::add, Set::addAll);
            if (sourceEntries.isEmpty() && targetNames.containsAll(movedNames)) {
                return;
            }
            sleep();
        } while (Instant.now().isBefore(deadline));
        throw new CloudDrive2ClientException("Telegram 文件移动结果可见性超时");
    }

    private List<CloudDrive2FileEntry> list(String path) {
        try {
            return fileOperations.list(path, true);
        } catch (CloudDrive2ClientException exception) {
            if (exception.getStatusCode() == Status.Code.NOT_FOUND) {
                return List.of();
            }
            throw exception;
        }
    }

    private String inboxPath() {
        return join(properties.getCloudDrivePathPrefix(), INBOX_DIRECTORY);
    }

    private String libraryPath() {
        return normalizePath(properties.getCloudDrivePathPrefix() + "/" + LIBRARY_DIRECTORY);
    }

    private void sleep() {
        try {
            Thread.sleep(properties.getVisibilityPollInterval().toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CloudDrive2ClientException("等待 PikPak 保存文件时任务被中断");
        }
    }

    public record MoveOutcome(
            int movedEntryCount,
            int movedFileCount,
            int expectedNewFileCount
    ) {
    }
}
