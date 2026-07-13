package com.medianexus.orchestrator.integration.clouddrive;

import static com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan.fileName;
import static com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan.join;
import static com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan.normalizePath;
import static com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan.parentPath;

import com.medianexus.orchestrator.config.CloudDrive2Properties;
import com.medianexus.orchestrator.service.organization.LibraryOrganizer;
import com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan;
import com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan.MoveOperation;
import com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan.RenameOperation;
import com.medianexus.orchestrator.service.organization.LibraryOrganizationProgressObserver;
import io.grpc.Status;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "medianexus.clouddrive2",
        name = "organization-enabled",
        havingValue = "true"
)
public class CloudDrive2LibraryOrganizer implements LibraryOrganizer {

    private final CloudDrive2FileOperations fileOperations;
    private final CloudDrive2Properties properties;

    CloudDrive2LibraryOrganizer(
            CloudDrive2FileOperations fileOperations,
            CloudDrive2Properties properties
    ) {
        this.fileOperations = fileOperations;
        this.properties = properties;
    }

    @Override
    public void organize(LibraryOrganizationPlan plan, LibraryOrganizationProgressObserver progressObserver) {
        progressObserver.record("正在等待 CD2 看见完整源文件", plan.targetDirectory());
        awaitPathsPresent(initialSourcePaths(plan), "CD2 源文件可见性超时");
        progressObserver.record("CD2 已看见完整源文件", plan.targetDirectory());

        if (!plan.renames().isEmpty()) {
            progressObserver.record("正在通过 CD2 批量重命名文件", "count=" + plan.renames().size());
            fileOperations.rename(plan.renames().stream()
                    .map(operation -> new CloudDrive2RenameOperation(
                            toCloudDrivePath(operation.sourcePath()),
                            operation.targetName()
                    ))
                    .toList());
            awaitPathsPresent(plan.renames().stream().map(RenameOperation::targetPath).collect(LinkedHashSet::new, Set::add, Set::addAll),
                    "CD2 重命名结果可见性超时");
            progressObserver.record("CD2 批量重命名完成", "count=" + plan.renames().size());
        }

        Map<String, List<String>> movesByTarget = new LinkedHashMap<>();
        for (MoveOperation operation : plan.moves()) {
            movesByTarget.computeIfAbsent(operation.targetDirectory(), ignored -> new ArrayList<>())
                    .add(operation.sourcePath());
        }
        for (Map.Entry<String, List<String>> entry : movesByTarget.entrySet()) {
            progressObserver.record(
                    "正在通过 CD2 批量移动文件到目标目录",
                    "dstDir=" + entry.getKey() + ", count=" + entry.getValue().size()
            );
            fileOperations.move(
                    entry.getValue().stream().map(this::toCloudDrivePath).toList(),
                    toCloudDrivePath(entry.getKey())
            );
            progressObserver.record("CD2 批量移动完成", "count=" + entry.getValue().size());
        }
        if (!plan.moves().isEmpty()) {
            progressObserver.record("正在确认 CD2 移动源路径已消失", "count=" + plan.moves().size());
            Set<String> movedSourcePaths = plan.moves().stream()
                    .map(MoveOperation::sourcePath)
                    .collect(LinkedHashSet::new, Set::add, Set::addAll);
            awaitPathsAbsent(movedSourcePaths, "CD2 移动源路径仍然可见");
            progressObserver.record("CD2 移动源路径已消失", "count=" + plan.moves().size());
        }

        if (!plan.deletions().isEmpty()) {
            progressObserver.record("正在通过 CD2 删除辅助内容", "count=" + plan.deletions().size());
            fileOperations.delete(plan.deletions().stream()
                    .map(operation -> toCloudDrivePath(operation.path()))
                    .toList());
            progressObserver.record("CD2 辅助内容删除请求完成", "count=" + plan.deletions().size());
        }

        cleanupEmptySourceDirectories(plan);
        progressObserver.record("正在确认 CD2 整理结果完整可见", plan.targetDirectory());
        awaitTargetManifest(plan);
        progressObserver.record("CD2 整理结果已完整可见", plan.targetDirectory());
    }

    private Set<String> initialSourcePaths(LibraryOrganizationPlan plan) {
        Map<String, String> initialPathByRenamedTarget = new LinkedHashMap<>();
        for (RenameOperation rename : plan.renames()) {
            initialPathByRenamedTarget.put(rename.targetPath(), rename.sourcePath());
        }
        Set<String> sourcePaths = new LinkedHashSet<>(initialPathByRenamedTarget.values());
        for (MoveOperation move : plan.moves()) {
            sourcePaths.add(initialPathByRenamedTarget.getOrDefault(move.sourcePath(), move.sourcePath()));
        }
        plan.deletions().forEach(operation -> sourcePaths.add(operation.path()));
        return sourcePaths;
    }

    private void awaitTargetManifest(LibraryOrganizationPlan plan) {
        Set<String> expectedTargetPaths = new LinkedHashSet<>();
        for (String expectedTargetName : plan.expectedTargetNames()) {
            expectedTargetPaths.add(join(plan.targetDirectory(), expectedTargetName));
        }
        awaitPathsPresent(expectedTargetPaths, "CD2 目标文件完整性校验超时");

        Set<String> deletedPaths = new LinkedHashSet<>();
        plan.deletions().forEach(operation -> deletedPaths.add(operation.path()));
        awaitPathsAbsent(deletedPaths, "CD2 删除结果可见性超时");
    }

    private void cleanupEmptySourceDirectories(LibraryOrganizationPlan plan) {
        String targetDirectory = normalizePath(plan.targetDirectory());
        Set<String> candidates = new LinkedHashSet<>();
        for (MoveOperation move : plan.moves()) {
            addCleanupCandidates(candidates, targetDirectory, parentPath(move.sourcePath()));
        }
        plan.deletions().forEach(operation ->
                addCleanupCandidates(candidates, targetDirectory, parentPath(operation.path())));
        candidates.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .filter(candidate -> !plan.cleanupExcludedDirectories().contains(candidate))
                .forEach(candidate -> {
                    String cloudDriveCandidate = toCloudDrivePath(candidate);
                    try {
                        if (fileOperations.list(cloudDriveCandidate, true).isEmpty()) {
                            fileOperations.delete(List.of(cloudDriveCandidate));
                        }
                    } catch (CloudDrive2ClientException exception) {
                        if (exception.getStatusCode() != Status.Code.NOT_FOUND) {
                            throw exception;
                        }
                    }
                });
    }

    private void addCleanupCandidates(Set<String> candidates, String targetDirectory, String sourceDirectory) {
        String current = normalizePath(sourceDirectory);
        while (!current.equals(targetDirectory) && current.startsWith(targetDirectory + "/")) {
            candidates.add(current);
            current = parentPath(current);
        }
    }

    private void awaitPathsPresent(Set<String> ingestPaths, String timeoutMessage) {
        if (ingestPaths.isEmpty()) {
            return;
        }
        Set<String> missing = new LinkedHashSet<>();
        awaitCondition(() -> {
            missing.clear();
            missing.addAll(missingPaths(ingestPaths, true));
            return missing.isEmpty();
        }, timeoutMessage, missing);
    }

    private void awaitPathsAbsent(Set<String> ingestPaths, String timeoutMessage) {
        if (ingestPaths.isEmpty()) {
            return;
        }
        Set<String> remaining = new LinkedHashSet<>();
        awaitCondition(() -> {
            remaining.clear();
            remaining.addAll(existingPaths(ingestPaths));
            return remaining.isEmpty();
        }, timeoutMessage, remaining);
    }

    private Set<String> missingPaths(Set<String> ingestPaths, boolean forceRefresh) {
        Set<String> missing = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> entry : namesByParent(ingestPaths).entrySet()) {
            refreshAncestorDirectories(entry.getKey());
            String cloudDriveParent = toCloudDrivePath(entry.getKey());
            Set<String> visibleNames;
            try {
                visibleNames = fileOperations.list(cloudDriveParent, forceRefresh).stream()
                        .map(CloudDrive2FileEntry::name)
                        .collect(LinkedHashSet::new, Set::add, Set::addAll);
            } catch (CloudDrive2ClientException exception) {
                if (exception.getStatusCode() == Status.Code.NOT_FOUND) {
                    entry.getValue().forEach(name -> missing.add(join(entry.getKey(), name)));
                    continue;
                }
                throw exception;
            }
            for (String name : entry.getValue()) {
                if (!visibleNames.contains(name)) {
                    missing.add(join(entry.getKey(), name));
                }
            }
        }
        return missing;
    }

    private void refreshAncestorDirectories(String ingestDirectory) {
        String cloudDriveDirectory = toCloudDrivePath(ingestDirectory);
        String cloudDrivePrefix = normalizePath(properties.getCloudDrivePathPrefix());
        String relativeDirectory = cloudDriveDirectory.substring(cloudDrivePrefix.length());
        String current = cloudDrivePrefix;
        for (String segment : relativeDirectory.split("/")) {
            if (segment.isBlank()) {
                continue;
            }
            try {
                fileOperations.list(current, true);
            } catch (CloudDrive2ClientException exception) {
                if (exception.getStatusCode() == Status.Code.NOT_FOUND) {
                    return;
                }
                throw exception;
            }
            current = join(current, segment);
        }
    }

    private Set<String> existingPaths(Set<String> ingestPaths) {
        Set<String> existing = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> entry : namesByParent(ingestPaths).entrySet()) {
            Set<String> visibleNames;
            try {
                visibleNames = fileOperations.list(toCloudDrivePath(entry.getKey()), true).stream()
                        .map(CloudDrive2FileEntry::name)
                        .collect(LinkedHashSet::new, Set::add, Set::addAll);
            } catch (CloudDrive2ClientException exception) {
                if (exception.getStatusCode() == Status.Code.NOT_FOUND) {
                    continue;
                }
                throw exception;
            }
            for (String name : entry.getValue()) {
                if (visibleNames.contains(name)) {
                    existing.add(join(entry.getKey(), name));
                }
            }
        }
        return existing;
    }

    private Map<String, Set<String>> namesByParent(Set<String> paths) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (String path : paths) {
            result.computeIfAbsent(parentPath(path), ignored -> new LinkedHashSet<>()).add(fileName(path));
        }
        return result;
    }

    private void awaitCondition(
            BooleanSupplier condition,
            String timeoutMessage,
            Set<String> incompletePaths
    ) {
        Instant deadline = Instant.now().plus(properties.getVisibilityTimeout());
        do {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(properties.getVisibilityPollInterval());
        } while (Instant.now().isBefore(deadline));
        throw new CloudDrive2ClientException(timeoutMessage + ": " + String.join(", ", incompletePaths));
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CloudDrive2ClientException("等待 CloudDrive2 目录可见时任务被中断");
        }
    }

    private String toCloudDrivePath(String ingestPath) {
        String normalizedPath = normalizePath(ingestPath);
        String ingestPrefix = normalizePath(properties.getIngestPathPrefix());
        String cloudDrivePrefix = normalizePath(properties.getCloudDrivePathPrefix());
        if (normalizedPath.equals(ingestPrefix)) {
            return cloudDrivePrefix;
        }
        String requiredPrefix = ingestPrefix + "/";
        if (!normalizedPath.startsWith(requiredPrefix)) {
            throw new CloudDrive2ClientException("入库路径不在已配置的映射前缀下: " + normalizedPath);
        }
        return normalizePath(cloudDrivePrefix + normalizedPath.substring(ingestPrefix.length()));
    }
}
