package com.medianexus.orchestrator.integration.clouddrive;

import com.medianexus.orchestrator.config.CloudDrive2Properties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "medianexus.clouddrive2", name = "organization-enabled", havingValue = "true")
public class TelegramCloudInboxMover {
    private static final String INBOX_DIRECTORY = "My Telegram";
    private static final String LIBRARY_DIRECTORY = "Media/Adult/Other/电报";
    private final CloudDrive2Properties properties;

    TelegramCloudInboxMover(CloudDrive2Properties properties) {
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
            if (actualTotal == expectedTotal) break;
            if (actualTotal > expectedTotal) {
                throw failure("Telegram 收件箱文件数超过预期：expected=" + expectedTotal + ", actual=" + actualTotal);
            }
            sleep();
        } while (Instant.now().isBefore(deadline));
        if (actualTotal != expectedTotal) {
            throw failure("等待 PikPak 保存文件超时：expected=" + expectedTotal + ", actual=" + actualTotal);
        }

        List<Path> entries = topLevelEntries(inboxPath());
        Path targetDirectory = libraryPath();
        for (Path source : entries) {
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
        if (!topLevelEntries(inboxPath()).isEmpty()) {
            throw failure("Telegram 文件移动后收件箱仍有残留内容");
        }
        return new MoveOutcome(entries.size(), actualTotal, expectedNewFileCount);
    }

    private int countFiles(Path root) {
        if (!Files.exists(root)) return 0;
        try (var paths = Files.walk(root)) {
            return Math.toIntExact(paths.filter(Files::isRegularFile).count());
        } catch (IOException exception) {
            throw failure("无法读取 Telegram 收件箱", exception);
        }
    }

    private List<Path> topLevelEntries(Path directory) {
        if (!Files.exists(directory)) return List.of();
        try (var paths = Files.list(directory)) {
            return paths.toList();
        } catch (IOException exception) {
            throw failure("无法读取 Telegram 收件箱顶层内容", exception);
        }
    }

    private Path inboxPath() { return mediaRoot().resolve(INBOX_DIRECTORY); }

    private Path libraryPath() {
        Path path = mediaRoot().resolve(LIBRARY_DIRECTORY);
        if (!Files.isDirectory(path)) throw failure("Telegram 媒体库目标目录不存在：" + path);
        return path;
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
