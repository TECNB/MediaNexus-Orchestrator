package com.medianexus.orchestrator.integration.clouddrive;

import static com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan.join;
import static com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan.normalizePath;

import com.medianexus.orchestrator.config.CloudDrive2Properties;
import io.grpc.Status;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "medianexus.clouddrive2", name = "organization-enabled", havingValue = "true")
public class TelegramCloudInboxMover {
    private static final Logger log = LoggerFactory.getLogger(TelegramCloudInboxMover.class);
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
        return countCloudFiles(cloudInboxPath());
    }

    public MoveOutcome awaitExpectedFilesAndMove(int baselineFileCount, int expectedNewFileCount) {
        if (baselineFileCount < 0 || expectedNewFileCount < 0) {
            throw new IllegalArgumentException("Telegram inbox file counts must not be negative");
        }
        int expectedTotal = Math.addExact(baselineFileCount, expectedNewFileCount);
        Instant deadline = Instant.now().plus(properties.getVisibilityTimeout());
        int actualTotal;
        int previousTotal = -1;
        do {
            actualTotal = countInboxFiles();
            if (actualTotal != previousTotal) {
                log.info("Telegram PikPak inbox visibility progress expected={} actual={} elapsedMs={}",
                        expectedTotal, actualTotal,
                        java.time.Duration.between(deadline.minus(properties.getVisibilityTimeout()), Instant.now()).toMillis());
                previousTotal = actualTotal;
            }
            if (actualTotal == expectedTotal) break;
            if (actualTotal > expectedTotal) {
                throw failure("Telegram 收件箱文件数超过预期：expected=" + expectedTotal + ", actual=" + actualTotal);
            }
            sleep();
        } while (Instant.now().isBefore(deadline));
        if (actualTotal != expectedTotal) {
            throw failure("等待 PikPak 保存文件超时：expected=" + expectedTotal + ", actual=" + actualTotal);
        }

        List<CloudDrive2FileEntry> entries = list(cloudInboxPath());
        refreshCloudDirectory(cloudLibraryPath());
        Path targetDirectory = awaitMountedPaths(entries);
        for (CloudDrive2FileEntry entry : entries) {
            Path source = inboxPath().resolve(entry.name());
            Path target = targetDirectory.resolve(source.getFileName());
            if (Files.exists(target)) {
                throw failure("Telegram 目标目录已存在同名条目：" + target.getFileName());
            }
            try {
                Files.move(source, target);
            } catch (IOException exception) {
                throw failure("Telegram 文件移动失败：" + source.getFileName(), exception);
            }
        }
        return new MoveOutcome(entries.size(), actualTotal, expectedNewFileCount);
    }

    private int countCloudFiles(String rootPath) {
        int count = 0;
        ArrayDeque<String> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        pending.add(normalizePath(rootPath));
        while (!pending.isEmpty()) {
            String directory = pending.removeFirst();
            if (!visited.add(directory)) continue;
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

    private List<CloudDrive2FileEntry> list(String path) {
        try {
            return fileOperations.list(path, true);
        } catch (CloudDrive2ClientException exception) {
            if (exception.getStatusCode() == Status.Code.NOT_FOUND) return List.of();
            throw exception;
        }
    }

    private Path inboxPath() { return mediaRoot().resolve(INBOX_DIRECTORY); }

    private String cloudInboxPath() {
        return join(properties.getCloudDrivePathPrefix(), INBOX_DIRECTORY);
    }

    private String cloudLibraryPath() {
        return normalizePath(properties.getCloudDrivePathPrefix() + "/" + LIBRARY_DIRECTORY);
    }

    private void refreshCloudDirectory(String path) {
        String prefix = normalizePath(properties.getCloudDrivePathPrefix());
        String current = prefix;
        for (String segment : normalizePath(path).substring(prefix.length()).split("/")) {
            if (segment.isBlank()) continue;
            fileOperations.list(current, true);
            current = join(current, segment);
        }
        fileOperations.list(current, true);
    }

    private Path awaitMountedPaths(List<CloudDrive2FileEntry> entries) {
        Path inbox = inboxPath();
        Path target = mediaRoot().resolve(LIBRARY_DIRECTORY);
        Instant deadline = Instant.now().plus(properties.getVisibilityTimeout());
        do {
            boolean sourcesVisible = entries.stream()
                    .allMatch(entry -> Files.exists(inbox.resolve(entry.name())));
            if (Files.isDirectory(target) && sourcesVisible) return target;
            sleep();
        } while (Instant.now().isBefore(deadline));
        if (!Files.isDirectory(target)) {
            throw failure("CD2 已刷新，但 Telegram 媒体库目标目录仍未同步到挂载：" + target);
        }
        throw failure("CD2 已刷新，但 Telegram 收件箱文件仍未同步到挂载");
    }

    private Path mediaRoot() { return Path.of(properties.getMediaSourcePathPrefix()).toAbsolutePath().normalize(); }

    private void sleep() {
        try {
            Thread.sleep(properties.getVisibilityPollInterval().toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure("等待 PikPak 保存文件时任务被中断", exception);
        }
    }

    private CloudDrive2ClientException failure(String message) { return new CloudDrive2ClientException(message); }

    private CloudDrive2ClientException failure(String message, Exception cause) {
        return new CloudDrive2ClientException(message, io.grpc.Status.Code.UNKNOWN, cause);
    }

    public record MoveOutcome(int movedEntryCount, int movedFileCount, int expectedNewFileCount) {}
}
