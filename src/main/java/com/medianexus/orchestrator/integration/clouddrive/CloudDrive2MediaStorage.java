package com.medianexus.orchestrator.integration.clouddrive;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.config.CloudDrive2Properties;
import com.medianexus.orchestrator.config.OpenListProperties;
import com.medianexus.orchestrator.service.settings.targets.IngestTargetRegistry;
import io.grpc.Status;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** CD2 paths are only exposed for configured Adult roots. Sizes are logical file bytes. */
@Component
public class CloudDrive2MediaStorage {
    private final CloudDrive2FileOperations files;
    private final IngestTargetRegistry targets;
    private final CloudDrive2Properties cloud;
    private final OpenListProperties openList;

    CloudDrive2MediaStorage(CloudDrive2FileOperations files, IngestTargetRegistry targets,
            CloudDrive2Properties cloud, OpenListProperties openList) {
        this.files = files;
        this.targets = targets;
        this.cloud = cloud;
        this.openList = openList;
    }

    public List<String> adultRoots(String library) {
        String subdirectory = switch (library) {
            case "adult-jav" -> "JAV";
            case "adult-other" -> "Other";
            default -> throw new BusinessException(ErrorCode.BAD_REQUEST, "只有 Adult 媒体库支持网盘文件夹视图");
        };
        return mappedRoots("ADULT", openList.getAdultRootPath()).stream()
                .map(root -> Path.of(root).resolve(subdirectory).toString())
                .distinct().toList();
    }

    public List<String> workRoots(String library) {
        return switch (library) {
            case "movies" -> mappedRoots("MOVIE", openList.getMovieRootPath());
            case "tv", "variety" -> mappedRoots("TV", openList.getTvRootPath());
            case "anime" -> {
                String template = openList.getAnimePathTemplate();
                int variable = template.indexOf('{');
                yield mappedRoots("ANIME", variable < 0 ? null : template.substring(0, template.lastIndexOf('/', variable)));
            }
            default -> List.of();
        };
    }

    private List<String> mappedRoots(String category, String legacyRoot) {
        return targets.rootsForExistingMedia(category, legacyRoot).stream()
                .map(root -> targets.toCloudDrivePath(root, cloud.getIngestPathPrefix(), cloud.getCloudDrivePathPrefix()))
                .distinct().toList();
    }

    public List<Entry> browseAdult(String library, String directory) {
        requireAdultPath(library, directory);
        return children(directory, false);
    }

    public Entry confirmAdultTarget(String library, Entry expected) {
        requireAdultTarget(library, expected.path());
        Entry actual = findLive(expected.path());
        if (actual == null || !sameTarget(actual, expected)) {
            throw conflict("所选文件或文件夹已变化，请刷新后重新选择");
        }
        return actual;
    }

    public void deleteAdultTarget(String library, Entry expected) {
        requireAdultTarget(library, expected.path());
        Entry actual = findLive(expected.path());
        if (actual == null) {
            return; // Retry after a successful cloud operation.
        }
        if (!sameTarget(actual, expected)) {
            throw conflict("所选文件或文件夹已变化，请刷新后重新选择");
        }
        files.delete(List.of(actual.path()));
        Instant deadline = Instant.now().plus(cloud.getVisibilityTimeout());
        do {
            Entry remaining = findLive(expected.path());
            if (remaining == null) return;
            if (!sameTarget(remaining, expected)) throw conflict("目标路径出现了新条目，请核对网盘");
            try {
                Thread.sleep(cloud.getVisibilityPollInterval().toMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new CloudDrive2ClientException("等待网盘删除结果时任务被中断", Status.Code.CANCELLED, exception);
            }
        } while (Instant.now().isBefore(deadline));
        throw conflict("网盘尚未确认目标已删除，请稍后重试");
    }

    private boolean sameTarget(Entry actual, Entry expected) {
        return actual.directory() == expected.directory()
                && StringUtils.hasText(actual.id()) && actual.id().equals(expected.id())
                && (actual.directory() || actual.bytes() == expected.bytes());
    }

    private void requireAdultTarget(String library, String value) {
        if (requireAdultPath(library, value).equals(value)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不能删除 Adult 媒体库根目录");
        }
    }

    private String requireAdultPath(String library, String value) {
        Path path;
        try {
            path = Path.of(value);
        } catch (InvalidPathException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的网盘路径");
        }
        if (!path.isAbsolute() || !value.equals(path.normalize().toString()) || value.indexOf('\\') >= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的网盘路径");
        }
        return adultRoots(library).stream().filter(root -> path.startsWith(Path.of(root)))
                .findFirst().orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "路径不在所选 Adult 媒体库内"));
    }

    public Function<String, String> sourcePathMapping() {
        return targets.cloudMediaPaths(cloud.getMediaSourcePathPrefix(), cloud.getCloudDrivePathPrefix());
    }

    public List<Entry> children(String path) {
        return children(path, false);
    }

    public Map<String, Long> cachedFolderFiles(String path) {
        return folderFiles(path, this::children);
    }

    public Map<String, Long> liveFolderFiles(String path) {
        return folderFiles(path, directory -> children(directory, true));
    }

    private Map<String, Long> folderFiles(String path, Function<String, List<Entry>> listing) {
        Map<String, Long> found = new LinkedHashMap<>();
        collectFiles(path, 0, found, listing);
        return found;
    }

    private void collectFiles(String path, int depth, Map<String, Long> found, Function<String, List<Entry>> listing) {
        if (depth >= 32 || found.size() >= 50_000) {
            throw conflict("目录超出单次检查范围");
        }
        for (Entry entry : listing.apply(path)) {
            if (entry.directory()) {
                collectFiles(entry.path(), depth + 1, found, listing);
            } else {
                found.put(entry.path(), entry.bytes());
            }
        }
    }

    private Entry findLive(String path) {
        try {
            return children(Path.of(path).getParent().toString(), true).stream()
                    .filter(entry -> entry.path().equals(path)).findFirst().orElse(null);
        } catch (CloudDrive2ClientException exception) {
            if (exception.getStatusCode() == Status.Code.NOT_FOUND) return null;
            throw exception;
        }
    }

    private List<Entry> children(String path, boolean refresh) {
        return files.list(path, refresh).stream().map(file -> {
            Path parent = Path.of(path);
            Path child = parent.resolve(file.name()).normalize();
            if (!parent.equals(child.getParent()) || !child.getFileName().toString().equals(file.name())
                    || file.name().indexOf('\\') >= 0) {
                throw conflict("网盘返回了无效的子路径");
            }
            return new Entry(child.toString(), file.directory(), file.id(), file.size());
        }).toList();
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message, HttpStatus.CONFLICT);
    }

    public record Entry(String path, boolean directory, String id, long bytes) { }
}
