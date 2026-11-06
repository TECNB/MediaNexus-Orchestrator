package com.medianexus.orchestrator.integration.clouddrive;

import static com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan.fileName;
import static com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan.parentPath;

import com.medianexus.orchestrator.config.CloudDrive2Properties;
import com.medianexus.orchestrator.service.settings.targets.IngestTargetRegistry;
import io.grpc.Status;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class CloudDrive2MediaDeletion {

    private final IngestTargetRegistry targetRegistry;
    private final CloudDrive2FileOperations fileOperations;
    private final CloudDrive2Properties properties;

    CloudDrive2MediaDeletion(
            CloudDrive2FileOperations fileOperations,
            CloudDrive2Properties properties,
            IngestTargetRegistry targetRegistry
    ) {
        this.targetRegistry = targetRegistry;
        this.fileOperations = fileOperations;
        this.properties = properties;
    }

    public void deleteMediaSourcePaths(List<String> mediaSourcePaths) {
        List<String> cloudPaths = mediaSourcePaths.stream().map(this::toCloudDrivePath).toList();
        Set<String> existingPaths = new LinkedHashSet<>();
        for (String cloudPath : cloudPaths) {
            if (exists(cloudPath)) {
                existingPaths.add(cloudPath);
            }
        }
        if (!existingPaths.isEmpty()) {
            fileOperations.delete(List.copyOf(existingPaths));
        }

        Instant deadline = Instant.now().plus(properties.getVisibilityTimeout());
        do {
            boolean anyExists = cloudPaths.stream().anyMatch(this::exists);
            if (!anyExists) {
                return;
            }
            sleep();
        } while (Instant.now().isBefore(deadline));
        throw new CloudDrive2ClientException("CD2 删除结果可见性超时");
    }

    public boolean mediaSourcePathExists(String mediaSourcePath) {
        return exists(toCloudDrivePath(mediaSourcePath));
    }

    public MediaSourceCheckResult checkMediaSourcePaths(Collection<String> mediaSourcePaths) {
        Map<String, String> cloudPaths = new LinkedHashMap<>();
        Map<String, Set<String>> namesByParent = new LinkedHashMap<>();
        for (String mediaSourcePath : mediaSourcePaths) {
            String cloudPath = toCloudDrivePath(mediaSourcePath);
            cloudPaths.put(mediaSourcePath, cloudPath);
            namesByParent.computeIfAbsent(parentPath(cloudPath), ignored -> new LinkedHashSet<>())
                    .add(fileName(cloudPath));
        }

        Set<String> existing = new LinkedHashSet<>();
        Set<String> failed = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> entry : namesByParent.entrySet()) {
            Set<String> names;
            try {
                names = fileOperations.list(entry.getKey(), true).stream()
                        .map(CloudDrive2FileEntry::name)
                        .collect(java.util.stream.Collectors.toSet());
            } catch (CloudDrive2ClientException exception) {
                if (exception.getStatusCode() == Status.Code.NOT_FOUND) {
                    continue;
                }
                cloudPaths.forEach((source, cloudPath) -> {
                    if (entry.getKey().equals(parentPath(cloudPath))) {
                        failed.add(source);
                    }
                });
                continue;
            }
            cloudPaths.forEach((source, cloudPath) -> {
                if (entry.getKey().equals(parentPath(cloudPath)) && names.contains(fileName(cloudPath))) {
                    existing.add(source);
                }
            });
        }
        return new MediaSourceCheckResult(existing, failed);
    }

    public record MediaSourceCheckResult(Set<String> existing, Set<String> failed) {
    }

    private boolean exists(String cloudPath) {
        try {
            return fileOperations.list(parentPath(cloudPath), true).stream()
                    .anyMatch(entry -> fileName(cloudPath).equals(entry.name()));
        } catch (CloudDrive2ClientException exception) {
            if (exception.getStatusCode() == Status.Code.NOT_FOUND) {
                return false;
            }
            throw exception;
        }
    }

    private String toCloudDrivePath(String mediaSourcePath) {
        return targetRegistry.toCloudDriveMediaPath(mediaSourcePath,
                properties.getMediaSourcePathPrefix(), properties.getCloudDrivePathPrefix());
    }

    private void sleep() {
        try {
            Thread.sleep(properties.getVisibilityPollInterval().toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CloudDrive2ClientException("等待 CloudDrive2 删除结果时任务被中断");
        }
    }
}
