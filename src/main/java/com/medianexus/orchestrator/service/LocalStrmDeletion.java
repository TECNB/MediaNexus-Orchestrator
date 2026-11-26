package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.config.CloudDrive2Properties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class LocalStrmDeletion {
    private final Path root;

    public LocalStrmDeletion(CloudDrive2Properties properties) {
        this.root = Path.of(properties.getStrmPathPrefix()).toAbsolutePath().normalize();
    }

    public void delete(List<String> paths) {
        paths.stream()
                .map(this::allowedPath)
                .distinct()
                .sorted(Comparator.comparingInt((Path path) -> path.getNameCount()).reversed())
                .forEach(this::deleteRecursively);
    }

    public String resolveMediaSourcePath(String value) {
        if (value == null || !value.startsWith(root.toString())) {
            return value;
        }
        if (!value.endsWith(".strm")) return value;
        if (!isLocalStrm(value)) return null;
        Path path = Path.of(value);
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            // Read one byte beyond the limit to reject oversized pointers without loading media into the heap.
            byte[] bytes = input.readNBytes(16_385);
            if (bytes.length > 16_384) throw new IllegalStateException("STRM 内容超过 16 KiB，停止解析");
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8).lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .findFirst()
                    .orElse(null);
        } catch (IOException exception) {
            return null;
        }
    }

    /** A scan never follows directory symlinks or leaves the configured STRM tree. */
    List<String> strmFiles(Path directory) {
        if (!directory.startsWith(root) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try {
            if (!directory.toRealPath().startsWith(root.toRealPath())) {
                throw new IllegalStateException("STRM 目录超出媒体库范围");
            }
            try (var paths = Files.walk(directory)) {
                return paths.filter(path -> path.toString().endsWith(".strm"))
                        .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .map(Path::toString).toList();
            }
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取本地 STRM 目录", exception);
        }
    }

    List<String> allStrmFiles() {
        return strmFiles(root);
    }

    boolean isLocalStrm(String value) {
        Path path = Path.of(value);
        if (!path.isAbsolute() || !path.equals(path.normalize()) || !path.startsWith(root) || path.equals(root)
                || !value.endsWith(".strm") || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        for (Path component = path; component.startsWith(root); component = component.getParent()) {
            if (Files.isSymbolicLink(component)) return false;
            if (component.equals(root)) break;
        }
        try {
            return path.toRealPath().startsWith(root.toRealPath());
        } catch (IOException exception) {
            return false;
        }
    }

    private Path allowedPath(String value) {
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (path.equals(root) || !path.startsWith(root)) {
            throw new IllegalArgumentException("STRM 删除路径超出媒体库范围: " + path);
        }
        return path;
    }

    private void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (var children = Files.walk(path)) {
            for (Path child : children.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(child);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("无法删除本地 STRM 路径: " + path, exception);
        }
    }
}
