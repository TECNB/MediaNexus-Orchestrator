package com.medianexus.orchestrator.service.organization;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record LibraryOrganizationPlan(
        String targetDirectory,
        List<RenameOperation> renames,
        List<MoveOperation> moves,
        List<DeleteOperation> deletions,
        Set<String> expectedTargetNames,
        Set<String> cleanupExcludedDirectories
) {

    public LibraryOrganizationPlan {
        targetDirectory = requirePath(targetDirectory, "targetDirectory");
        renames = List.copyOf(Objects.requireNonNull(renames, "renames"));
        moves = List.copyOf(Objects.requireNonNull(moves, "moves"));
        deletions = List.copyOf(Objects.requireNonNull(deletions, "deletions"));
        expectedTargetNames = Set.copyOf(Objects.requireNonNull(expectedTargetNames, "expectedTargetNames"));
        cleanupExcludedDirectories = Set.copyOf(Objects.requireNonNull(
                cleanupExcludedDirectories,
                "cleanupExcludedDirectories"
        ));
    }

    public static LibraryOrganizationPlan fromGroupedOperations(
            String targetDirectory,
            Map<String, Map<String, String>> renamesByDirectory,
            Map<String, List<String>> movesByDirectory,
            Map<String, List<String>> deletionsByDirectory,
            Set<String> expectedTargetNames,
            Set<String> cleanupExcludedDirectories
    ) {
        List<RenameOperation> renames = new ArrayList<>();
        renamesByDirectory.forEach((directory, entries) -> entries.forEach((sourceName, targetName) ->
                renames.add(new RenameOperation(join(directory, sourceName), targetName))));

        List<MoveOperation> moves = new ArrayList<>();
        movesByDirectory.forEach((directory, names) -> names.forEach(name ->
                moves.add(new MoveOperation(join(directory, name), targetDirectory))));

        List<DeleteOperation> deletions = new ArrayList<>();
        deletionsByDirectory.forEach((directory, names) -> names.forEach(name ->
                deletions.add(new DeleteOperation(join(directory, name)))));

        return new LibraryOrganizationPlan(
                targetDirectory,
                renames,
                moves,
                deletions,
                new LinkedHashSet<>(expectedTargetNames),
                new LinkedHashSet<>(cleanupExcludedDirectories)
        );
    }

    public record RenameOperation(String sourcePath, String targetName) {

        public RenameOperation {
            sourcePath = requirePath(sourcePath, "sourcePath");
            targetName = requireName(targetName, "targetName");
        }

        public String targetPath() {
            return join(parentPath(sourcePath), targetName);
        }
    }

    public record MoveOperation(String sourcePath, String targetDirectory) {

        public MoveOperation {
            sourcePath = requirePath(sourcePath, "sourcePath");
            targetDirectory = requirePath(targetDirectory, "targetDirectory");
        }
    }

    public record DeleteOperation(String path) {

        public DeleteOperation {
            path = requirePath(path, "path");
        }
    }

    public static String normalizePath(String path) {
        String normalized = requirePath(path, "path").replaceAll("/{2,}", "/");
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        return normalized.length() > 1 && normalized.endsWith("/")
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
    }

    public static String join(String parent, String name) {
        return normalizePath(normalizePath(parent) + "/" + requireName(name, "name"));
    }

    public static String parentPath(String path) {
        String normalized = normalizePath(path);
        int separator = normalized.lastIndexOf('/');
        return separator <= 0 ? "/" : normalized.substring(0, separator);
    }

    public static String fileName(String path) {
        String normalized = normalizePath(path);
        int separator = normalized.lastIndexOf('/');
        return separator < 0 ? normalized : normalized.substring(separator + 1);
    }

    private static String requirePath(String path, String fieldName) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return path.trim();
    }

    private static String requireName(String name, String fieldName) {
        if (name == null || name.isBlank() || name.contains("/")) {
            throw new IllegalArgumentException(fieldName + " must be a single non-blank path segment");
        }
        return name.trim();
    }
}
