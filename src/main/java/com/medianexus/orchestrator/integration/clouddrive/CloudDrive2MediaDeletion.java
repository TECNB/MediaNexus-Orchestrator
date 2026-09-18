package com.medianexus.orchestrator.integration.clouddrive;

import static com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan.fileName;
import static com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan.normalizePath;
import static com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan.parentPath;

import com.medianexus.orchestrator.config.CloudDrive2Properties;
import io.grpc.Status;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "medianexus.clouddrive2",
        name = "organization-enabled",
        havingValue = "true"
)
public class CloudDrive2MediaDeletion {

    private final CloudDrive2FileOperations fileOperations;
    private final CloudDrive2Properties properties;

    CloudDrive2MediaDeletion(
            CloudDrive2FileOperations fileOperations,
            CloudDrive2Properties properties
    ) {
        this.fileOperations = fileOperations;
        this.properties = properties;
    }

    public void deleteMediaSourcePaths(List<String> mediaSourcePaths) {
        Set<String> existingPaths = new LinkedHashSet<>();
        for (String mediaSourcePath : mediaSourcePaths) {
            String cloudPath = toCloudDrivePath(mediaSourcePath);
            if (exists(cloudPath)) {
                existingPaths.add(cloudPath);
            }
        }
        if (!existingPaths.isEmpty()) {
            fileOperations.delete(List.copyOf(existingPaths));
        }

        Instant deadline = Instant.now().plus(properties.getVisibilityTimeout());
        do {
            boolean anyExists = mediaSourcePaths.stream()
                    .map(this::toCloudDrivePath)
                    .anyMatch(this::exists);
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
        String normalizedPath = normalizePath(mediaSourcePath);
        String mediaSourcePrefix = normalizePath(properties.getMediaSourcePathPrefix());
        String cloudDrivePrefix = normalizePath(properties.getCloudDrivePathPrefix());
        if (!normalizedPath.startsWith(mediaSourcePrefix + "/")) {
            throw new CloudDrive2ClientException("媒体源路径不在 CD2 映射范围内: " + normalizedPath);
        }
        return normalizePath(cloudDrivePrefix + normalizedPath.substring(mediaSourcePrefix.length()));
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
