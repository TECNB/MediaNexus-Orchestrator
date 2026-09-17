package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.config.CloudDrive2Properties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
