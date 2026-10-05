package com.medianexus.orchestrator.integration.clouddrive;

import static com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan.join;
import static com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan.normalizePath;

import com.medianexus.orchestrator.config.CloudDrive2Properties;
import io.grpc.Status;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.function.Consumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "medianexus.clouddrive2", name = "organization-enabled", havingValue = "true")
public class TelegramCloudInboxMover {
    private final CloudDrive2FileOperations fileOperations;
    private final CloudDrive2Properties properties;

    TelegramCloudInboxMover(CloudDrive2FileOperations fileOperations, CloudDrive2Properties properties) {
        this.fileOperations = fileOperations;
        this.properties = properties;
    }

    public record ExpectedVideo(String key, String originalName, long size, String caption, String savedName) {}
    public record FileDelivery(String key, String originalName, String savedName, String sourcePath,
                               String libraryPath, String status, String note) {}
    public record MoveOutcome(int movedEntryCount, int movedFileCount) {}

    public MoveOutcome drainInbox(List<ExpectedVideo> videos, Consumer<FileDelivery> recordDelivery) {
        String inbox = join(properties.getCloudDrivePathPrefix(), "My Telegram");
        String target = normalizePath(properties.getCloudDrivePathPrefix() + "/Media/Adult/Other/电报");
        refreshDirectory(inbox);
        refreshDirectory(target);
        reconcileAcceptedVideos(videos, target, recordDelivery);
        int movedEntries = 0;
        int movedFiles = 0;
        for (CloudDrive2FileEntry entry : list(inbox)) {
            List<FileDelivery> prepared = new ArrayList<>();
            try {
                prepareFiles(entry, videos, prepared);
                String source = entry.directory() ? entry.fullPath() : prepared.get(0).sourcePath();
                Set<String> existingPaths = listWithAncestorRecovery(target).stream().map(CloudDrive2FileEntry::fullPath).collect(Collectors.toSet());
                List<String> destinations = fileOperations.moveKeepingBoth(source, target);
                String libraryRoot = destinations.isEmpty()
                        ? awaitMovedPath(source, target, entry.size(), entry.directory(), existingPaths) : destinations.get(0);
                // Verify the API actually removed the source; a successful Skip is not an accepted move.
                if (list(inbox).stream().anyMatch(file -> file.fullPath().equals(source))) {
                    throw new IllegalStateException("移动后文件仍在收件箱");
                }
                for (FileDelivery file : prepared) {
                    String destination = entry.directory()
                            ? libraryRoot + file.sourcePath().substring(entry.fullPath().length()) : libraryRoot;
                    recordDelivery.accept(new FileDelivery(file.key(), file.originalName(), destination.substring(destination.lastIndexOf('/') + 1),
                            file.sourcePath(), destination, "MOVED", file.note()));
                }
                movedEntries++;
                movedFiles += prepared.size();
                if (entry.directory()) {
                    try {
                        refreshDirectory(libraryRoot);
                        for (String parent : prepared.stream().map(file -> libraryRoot
                                + file.sourcePath().substring(entry.fullPath().length(), file.sourcePath().lastIndexOf('/')))
                                .distinct().toList()) refreshDirectory(parent);
                    } catch (RuntimeException refreshFailure) {
                        recordDelivery.accept(new FileDelivery(null, entry.name(), entry.name(), entry.fullPath(),
                                libraryRoot, "MOVED", "已移动，目标目录刷新暂未完成：" + refreshFailure.getMessage()));
                    }
                }
            } catch (RuntimeException exception) {
                if (prepared.isEmpty()) {
                    recordDelivery.accept(new FileDelivery(null, entry.name(), entry.name(), entry.fullPath(),
                            null, "PENDING", exception.getMessage()));
                } else {
                    for (FileDelivery file : prepared) {
                        recordDelivery.accept(new FileDelivery(file.key(), file.originalName(), file.savedName(),
                                file.sourcePath(), null, "PENDING", exception.getMessage()));
                    }
                }
            }
        }
        refreshDirectory(target);
        return new MoveOutcome(movedEntries, movedFiles);
    }

    private void prepareFiles(CloudDrive2FileEntry entry, List<ExpectedVideo> videos, List<FileDelivery> prepared) {
        if (entry.directory()) {
            for (CloudDrive2FileEntry child : list(entry.fullPath())) prepareFiles(child, videos, prepared);
            return;
        }
        List<ExpectedVideo> matches = videos.stream().filter(video -> video.size() == entry.size()
                && (entry.name().equals(video.originalName()) || entry.name().equals(video.savedName())
                || entry.name().equals(readableName(video.caption(), video.originalName()))
                || stripSaveSuffix(entry.name()).equals(video.originalName()))).toList();
        ExpectedVideo video = matches.size() == 1 ? matches.get(0) : null;
        String original = entry.name();
        String renamed = original;
        String path = entry.fullPath();
        String note = video == null ? "历史、手动添加或无法明确关联的文件，保留原名" : null;
        if (video != null) {
            // A prior interrupted run may already have renamed this object.
            String desired = entry.name().equals(video.savedName()) || entry.name().equals(readableName(video.caption(), video.originalName()))
                    ? entry.name() : readableName(video.caption(), entry.name());
            if (!desired.equals(entry.name())) {
                try {
                    fileOperations.rename(List.of(new CloudDrive2RenameOperation(entry.fullPath(), desired)));
                    renamed = desired;
                    path = entry.fullPath().substring(0, entry.fullPath().lastIndexOf('/') + 1) + desired;
                } catch (RuntimeException exception) {
                    note = "改名未完成，保留文件继续入库：" + exception.getMessage();
                    // A response can be lost after a successful rename; re-read before choosing a source path.
                    List<CloudDrive2FileEntry> siblings = list(entry.fullPath().substring(0, entry.fullPath().lastIndexOf('/')));
                    if (siblings.stream().anyMatch(file -> file.name().equals(desired) && file.size() == entry.size())) {
                        renamed = desired;
                        path = entry.fullPath().substring(0, entry.fullPath().lastIndexOf('/') + 1) + desired;
                    }
                }
            }
        }
        prepared.add(new FileDelivery(video == null ? null : video.key(), original, renamed, path, null, "PREPARED", note));
    }

    private void reconcileAcceptedVideos(List<ExpectedVideo> videos, String target, Consumer<FileDelivery> record) {
        if (videos.isEmpty()) return;
        List<CloudDrive2FileEntry> accepted = listWithAncestorRecovery(target);
        for (ExpectedVideo video : videos) {
            List<CloudDrive2FileEntry> matches = accepted.stream().filter(file -> !file.directory()
                    && file.size() == video.size()
                    && (file.name().equals(video.savedName())
                    || file.name().equals(readableName(video.caption(), video.originalName())))).toList();
            if (matches.size() == 1) {
                CloudDrive2FileEntry file = matches.get(0);
                record.accept(new FileDelivery(video.key(), video.originalName(), file.name(), null,
                        file.fullPath(), "MOVED", "复查确认此前已入库，补齐记录及 AS 刷新"));
            }
        }
    }

    private String awaitMovedPath(String source, String target, long size, boolean directory, Set<String> existingPaths) {
        Instant deadline = Instant.now().plus(properties.getVisibilityTimeout());
        do {
            // CD2 acknowledges cloud moves before parent WebDAV listings catch up.
            // Retry observation only; never repeat an acknowledged move.
            try {
                refreshDirectory(target);
                String matched = findMovedPath(source, target, size, directory, existingPaths);
                if (matched != null) return matched;
            } catch (CloudDrive2ClientException transientListingFailure) {
                // A transient WebDAV list failure must not turn an acknowledged move into a repeat move.
            }
            try {
                Thread.sleep(properties.getVisibilityPollInterval().toMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("移动结果确认被中断", exception);
            }
        } while (Instant.now().isBefore(deadline));
        throw new IllegalStateException("移动已返回成功，但刷新后目标路径仍无法明确确认；保留记录等待复查");
    }

    private List<CloudDrive2FileEntry> listWithAncestorRecovery(String path) {
        try {
            return list(path);
        } catch (CloudDrive2ClientException exception) {
            refreshDirectory(path);
            return list(path);
        }
    }

    private String findMovedPath(String source, String target, long size, boolean directory, Set<String> existingPaths) {
        String name = source.substring(source.lastIndexOf('/') + 1);
        List<CloudDrive2FileEntry> candidates = list(target).stream().filter(file -> !existingPaths.contains(file.fullPath())
                && file.directory() == directory
                && (directory || file.size() == size)
                && stripSaveSuffix(file.name()).equals(stripSaveSuffix(name))).toList();
        return candidates.size() == 1 ? candidates.get(0).fullPath() : null;
    }

    static String stripSaveSuffix(String name) {
        return name.replaceFirst("[（(]\\d+[）)](?=\\.[^.]+$|$)", "");
    }

    static String readableName(String caption, String original) {
        if (caption == null || caption.isBlank()) return original;
        String title = caption.lines().map(line -> line.replaceAll("https?://\\S+|t\\.me/\\S+", "")
                        .replaceAll("[#<>:\"/\\\\|?*\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim())
                .filter(line -> !line.isBlank() && !line.matches(".*(交流群|点击链接|加入频道|完整合集|领取资源|保存教程).*"))
                .findFirst().orElse("");
        if (title.isBlank() || original.contains(title)) return original;
        int allowance = 240 - original.getBytes(StandardCharsets.UTF_8).length - 3;
        StringBuilder bounded = new StringBuilder();
        int bytes = 0;
        for (int point : title.codePoints().limit(48).toArray()) {
            String letter = new String(Character.toChars(point));
            if (bytes + letter.getBytes(StandardCharsets.UTF_8).length > allowance) break;
            bounded.append(letter);
            bytes += letter.getBytes(StandardCharsets.UTF_8).length;
        }
        String prefix = bounded.toString().trim();
        return prefix.isBlank() ? original : prefix + " - " + original;
    }

    private List<CloudDrive2FileEntry> list(String path) {
        try {
            return fileOperations.list(path, true);
        } catch (CloudDrive2ClientException exception) {
            if (exception.getStatusCode() == Status.Code.NOT_FOUND) return List.of();
            throw exception;
        }
    }

    private void refreshDirectory(String path) {
        String prefix = normalizePath(properties.getCloudDrivePathPrefix());
        String current = prefix;
        fileOperations.list(current, true);
        for (String segment : normalizePath(path).substring(prefix.length()).split("/")) {
            if (segment.isBlank()) continue;
            current = join(current, segment);
            list(current);
        }
    }
}
