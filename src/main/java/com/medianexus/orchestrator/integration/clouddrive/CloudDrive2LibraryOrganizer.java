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
import com.medianexus.orchestrator.service.settings.targets.IngestTargetRegistry;
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
import org.springframework.stereotype.Component;

@Component
public class CloudDrive2LibraryOrganizer implements com.medianexus.orchestrator.service.organization.DurableLibraryOrganizer {

    private final IngestTargetRegistry targetRegistry;
    private final CloudDrive2FileOperations fileOperations;
    private final CloudDrive2Properties properties;

    @org.springframework.beans.factory.annotation.Autowired
    private com.medianexus.orchestrator.integration.openlist.OpenListClient openList;

    CloudDrive2LibraryOrganizer(
            CloudDrive2FileOperations fileOperations,
            CloudDrive2Properties properties,
            IngestTargetRegistry targetRegistry
    ) {
        this.targetRegistry = targetRegistry;
        this.fileOperations = fileOperations;
        this.properties = properties;
    }

    @Override
    public void organize(LibraryOrganizationPlan plan, LibraryOrganizationProgressObserver progressObserver) {
        validatePlanMapping(plan);
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

        Map<MoveRequestGroup, List<MoveOperation>> moveOperationsByRequestGroup = new LinkedHashMap<>();
        for (MoveOperation operation : plan.moves()) {
            MoveRequestGroup requestGroup = new MoveRequestGroup(parentPath(operation.sourcePath()), operation.targetDirectory());
            moveOperationsByRequestGroup.computeIfAbsent(requestGroup, ignored -> new ArrayList<>()).add(operation);
        }
        for (Map.Entry<MoveRequestGroup, List<MoveOperation>> entry : moveOperationsByRequestGroup.entrySet()) {
            MoveRequestGroup requestGroup = entry.getKey();
            List<MoveOperation> operations = entry.getValue();
            List<MoveEvidence> evidence = captureMoveEvidence(operations, requestGroup.targetDirectory());
            String batchDetail = "srcDir=" + requestGroup.sourceDirectory()
                    + ", dstDir=" + requestGroup.targetDirectory() + ", count=" + operations.size();
            progressObserver.record("正在通过 CD2 批量移动文件到目标目录", batchDetail);
            try {
                fileOperations.move(operations.stream().map(operation -> toCloudDrivePath(operation.sourcePath())).toList(),
                        toCloudDrivePath(requestGroup.targetDirectory()));
            } catch (CloudDrive2ClientException failure) {
                progressObserver.record("CD2 批量移动请求失败，正在保留原始异常并核对已接受操作",
                        moveFailureDetail(failure, evidence));
                reconcileMoveFailure(evidence, failure, progressObserver);
            }
            progressObserver.record("CD2 批量移动完成", batchDetail);
        }
        if (!plan.moves().isEmpty()) {
            progressObserver.record("正在确认 CD2 移动源路径已消失", "count=" + plan.moves().size());
            Set<String> movedSourcePaths = plan.moves().stream()
                    .map(MoveOperation::sourcePath)
                    .collect(LinkedHashSet::new, Set::add, Set::addAll);
            awaitPathsAbsent(movedSourcePaths, "CD2 移动源路径仍然可见");
            progressObserver.record("CD2 移动源路径已消失", "count=" + plan.moves().size());
        }

        Map<String, Set<String>> deletionsByParent = namesByParent(plan.deletions().stream()
                .map(operation -> operation.path()).collect(LinkedHashSet::new, Set::add, Set::addAll));
        for (Map.Entry<String, Set<String>> entry : deletionsByParent.entrySet()) {
            Set<String> deletionPaths = entry.getValue().stream().map(name -> join(entry.getKey(), name))
                    .collect(LinkedHashSet::new, Set::add, Set::addAll);
            String batchDetail = "directory=" + entry.getKey() + ", count=" + deletionPaths.size();
            progressObserver.record("正在通过 CD2 删除辅助内容", batchDetail);
            try {
                fileOperations.delete(deletionPaths.stream().map(this::toCloudDrivePath).toList());
            } catch (CloudDrive2ClientException exception) {
                if (exception.getStatusCode() != Status.Code.DEADLINE_EXCEEDED) {
                    throw exception;
                }
                // RPC 超时不代表服务端未执行；只读确认全部路径消失，禁止重发删除请求。
                progressObserver.record("CD2 删除请求超时，正在确认辅助内容是否已消失", batchDetail);
                try {
                    awaitPathsAbsent(deletionPaths, "CD2 删除超时后仍有辅助内容可见");
                } catch (CloudDrive2ClientException verificationFailure) {
                    exception.addSuppressed(verificationFailure);
                    throw exception;
                }
                progressObserver.record("CD2 删除超时后已确认辅助内容全部消失", batchDetail);
            }
            progressObserver.record("CD2 辅助内容删除请求完成", batchDetail);
        }

        cleanupEmptySourceDirectories(plan);
        progressObserver.record("正在确认 CD2 整理结果完整可见", plan.targetDirectory());
        awaitTargetManifest(plan);
        progressObserver.record("CD2 整理结果已完整可见", plan.targetDirectory());
    }

    @Override
    public void requireRecoveryWritersQuiet() {
        fileOperations.requireWritersQuiet();
    }

    @Override
    public Snapshot capture(LibraryOrganizationPlan plan) {
        validatePlanMapping(plan);
        fileOperations.requireWritersQuiet();
        var account = openList.offlineDirectoryForPath(plan.targetDirectory());
        if (account.account().equals("unknown") || account.rootId().isBlank()) throw blocked();
        Map<String, String> originals = new LinkedHashMap<>();
        Map<String, String> finals = new LinkedHashMap<>();
        plan.renames().forEach(rename -> {
            if (originals.put(rename.targetPath(), rename.sourcePath()) != null) throw blocked();
        });
        plan.moves().forEach(move -> {
            if (finals.put(move.sourcePath(), join(move.targetDirectory(), fileName(move.sourcePath()))) != null) throw blocked();
        });
        Set<String> renamedPaths = new LinkedHashSet<>(originals.keySet());
        renamedPaths.addAll(finals.keySet());
        List<FileEvidence> evidence = new ArrayList<>();
        Map<String, String> cloudPaths = new LinkedHashMap<>();
        Set<String> finalNames = new java.util.HashSet<>();
        Set<String> ids = new java.util.HashSet<>();
        for (String renamed : renamedPaths) {
            String original = originals.getOrDefault(renamed, renamed);
            String target = finals.getOrDefault(renamed, renamed);
            if (!parentPath(target).equals(normalizePath(plan.targetDirectory()))
                    || !finalNames.add(fileName(target).toLowerCase(java.util.Locale.ROOT))) throw blocked();
            for (String path : List.of(original, renamed, target)) cloudPaths.put(path, durableCloudPath(path));
            var sourceAccount = openList.offlineDirectoryForPath(original);
            if (!account.account().equals(sourceAccount.account()) || !account.rootId().equals(sourceAccount.rootId())) throw blocked();
            CloudDrive2FileEntry source = durableEntry(original);
            if (source == null || source.directory() || source.size() <= 0) throw blocked();
            var identity = openList.readIdentity(original);
            if (identity.bytes() != source.size() || !ids.add(identity.providerFileId())) throw blocked();
            for (String path : new LinkedHashSet<>(List.of(renamed, target))) {
                if (!path.equals(original) && (durableEntry(path) != null || openList.readIdentity(path) != null)) throw blocked();
            }
            evidence.add(new FileEvidence(original, renamed, target, identity.providerFileId(), identity.gcid(), source.size()));
        }
        for (String name : plan.expectedTargetNames()) {
            String path = join(plan.targetDirectory(), name);
            if (finalNames.contains(name.toLowerCase(java.util.Locale.ROOT))) continue;
            cloudPaths.put(path, durableCloudPath(path));
            CloudDrive2FileEntry entry = durableEntry(path);
            if (entry == null || entry.directory() || entry.size() <= 0
                    || !finalNames.add(name.toLowerCase(java.util.Locale.ROOT))) throw blocked();
            var identity = openList.readIdentity(path);
            if (identity.bytes() != entry.size() || !ids.add(identity.providerFileId())) throw blocked();
            evidence.add(new FileEvidence(path, path, path, identity.providerFileId(), identity.gcid(), entry.size()));
        }
        if (evidence.isEmpty() || evidence.size() != plan.expectedTargetNames().size()) throw blocked();
        return new Snapshot(plan, targetRegistry.organizationNamespaceProof(), cloudPaths, evidence);
    }

    @Override
    public void resume(Snapshot snapshot, Map<String, ActionState> actions, Checkpoint checkpoint) {
        validateDurableMapping(snapshot);
        fileOperations.requireWritersQuiet();
        // A previous RPC may still be running remotely. Settle every SENT before issuing any untouched action.
        for (FileEvidence file : snapshot.files()) {
            String location = durableLocation(file);
            String renameKey = file.originalPath() + " -> " + file.renamedPath();
            String moveKey = file.renamedPath() + " -> " + file.finalPath();
            if (actions.get(renameKey) == ActionState.SENT && location.equals(file.originalPath())
                    || actions.get(moveKey) == ActionState.SENT && !location.equals(file.finalPath())) throw blocked();
        }
        // No cleanup/deletion is replayed. Each file has at most one rename and one move intent.
        for (FileEvidence file : snapshot.files()) {
            if (!file.originalPath().equals(file.renamedPath())) {
                durableAction(snapshot, file, file.originalPath(), file.renamedPath(), actions, checkpoint,
                        () -> fileOperations.rename(List.of(new CloudDrive2RenameOperation(
                                durableCloudPath(file.originalPath()), fileName(file.renamedPath())))));
            }
        }
        for (FileEvidence file : snapshot.files()) {
            if (!file.renamedPath().equals(file.finalPath())) {
                durableAction(snapshot, file, file.renamedPath(), file.finalPath(), actions, checkpoint,
                        () -> fileOperations.move(List.of(durableCloudPath(file.renamedPath())),
                                durableCloudPath(parentPath(file.finalPath()))));
            }
        }
        verifyFiles(snapshot);
    }

    private void durableAction(Snapshot snapshot, FileEvidence file, String source, String target,
                               Map<String, ActionState> actions, Checkpoint checkpoint, Runnable write) {
        validateDurableMapping(snapshot);
        String key = source + " -> " + target;
        String location = durableLocation(file);
        if (location.equals(target) || location.equals(file.finalPath())) {
            checkpoint.save(key, ActionState.DONE);
            return;
        }
        fileOperations.requireWritersQuiet();
        if (!location.equals(source) || actions.containsKey(key)) throw blocked();
        checkpoint.save(key, ActionState.SENT);
        // The committed SENT is deliberately not a retry permit, including crash-before-call.
        write.run();
        awaitCondition(() -> {
            try { return durableLocation(file).equals(target); }
            catch (RuntimeException notVisible) { return false; }
        }, "持久整理结果尚不可确认", Set.of(target));
        checkpoint.save(key, ActionState.DONE);
    }

    @Override
    public void verifyFiles(Snapshot snapshot) {
        validateDurableMapping(snapshot);
        for (FileEvidence file : snapshot.files()) {
            if (!durableLocation(file).equals(file.finalPath())) throw blocked();
        }
    }

    private String durableLocation(FileEvidence evidence) {
        String location = null;
        for (String path : new LinkedHashSet<>(List.of(evidence.originalPath(), evidence.renamedPath(), evidence.finalPath()))) {
            CloudDrive2FileEntry entry = durableEntry(path);
            var identity = openList.readIdentity(path);
            if (entry == null && identity == null) continue;
            if (entry == null || identity == null || location != null || entry.directory() || entry.size() != evidence.bytes()) throw blocked();
            if (identity.bytes() != evidence.bytes() || !evidence.providerFileId().equals(identity.providerFileId())
                    || !evidence.gcid().equals(identity.gcid())) throw blocked();
            var account = openList.offlineDirectoryForPath(path);
            var targetAccount = openList.offlineDirectoryForPath(parentPath(evidence.finalPath()));
            if (account.account().equals("unknown") || !account.account().equals(targetAccount.account())
                    || !account.rootId().equals(targetAccount.rootId())) throw blocked();
            location = path;
        }
        if (location == null) throw blocked();
        return location;
    }

    private CloudDrive2FileEntry durableEntry(String path) {
        String cloudPath = durableCloudPath(path);
        Map<String, CloudDrive2FileEntry> entries;
        try {
            entries = entriesByName(fileOperations.list(parentPath(cloudPath), true));
        } catch (CloudDrive2ClientException failure) {
            if (failure.getStatusCode() == Status.Code.NOT_FOUND) return null;
            throw failure;
        }
        CloudDrive2FileEntry entry = entryByName(entries, fileName(cloudPath));
        if (entry != null && (!entry.name().equals(fileName(cloudPath)) || !cloudPath.equals(entry.fullPath()))) throw blocked();
        return entry;
    }

    private String durableCloudPath(String path) {
        if (!path.startsWith("/") || path.contains("\\") || java.util.Arrays.stream(path.split("/", -1))
                .anyMatch(segment -> segment.equals("..") || segment.equals("."))) throw blocked();
        return toCloudDrivePath(path);
    }

    private void validateDurableMapping(Snapshot snapshot) {
        if (!snapshot.namespaceProof().equals(targetRegistry.organizationNamespaceProof())) throw blocked();
        snapshot.cloudPaths().forEach((path, cloud) -> { if (!cloud.equals(durableCloudPath(path))) throw blocked(); });
    }

    private CloudDrive2ClientException blocked() {
        return new CloudDrive2ClientException("整理证据冲突、身份缺失或写入结果未结清；仅允许只读核对，禁止重发");
    }

    /** 映射命名空间禁止重叠，因此共同祖先可映射才证明整个计划属于同一目标。 */
    private void validatePlanMapping(LibraryOrganizationPlan plan) {
        Set<String> paths = new LinkedHashSet<>(initialSourcePaths(plan));
        paths.add(plan.targetDirectory());
        plan.renames().forEach(operation -> paths.add(operation.targetPath()));
        plan.moves().forEach(operation -> {
            paths.add(operation.sourcePath());
            paths.add(operation.targetDirectory());
        });
        String commonRoot = normalizePath(plan.targetDirectory());
        for (String path : paths) {
            toCloudDrivePath(path);
            String normalized = normalizePath(path);
            while (!normalized.equals(commonRoot) && !normalized.startsWith(commonRoot + "/")) {
                if ("/".equals(commonRoot)) {
                    break;
                }
                commonRoot = parentPath(commonRoot);
            }
        }
        toCloudDrivePath(commonRoot);
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
        awaitFinalTargetPathsPresent(expectedTargetPaths);

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
            missing.addAll(missingPathsAfterForcedRefresh(ingestPaths));
            return missing.isEmpty();
        }, timeoutMessage, missing);
    }

    private void awaitFinalTargetPathsPresent(Set<String> ingestPaths) {
        if (ingestPaths.isEmpty() || missingPathsFromCache(ingestPaths).isEmpty()) {
            return;
        }
        awaitPathsPresent(ingestPaths, "CD2 目标文件完整性校验超时");
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

    private Set<String> missingPathsAfterForcedRefresh(Set<String> ingestPaths) {
        Set<String> missing = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> entry : namesByParent(ingestPaths).entrySet()) {
            Set<String> visibleNames = forcedVisibleNamesWithAncestorRecovery(entry.getKey());
            addMissingPaths(missing, entry, visibleNames);
        }
        return missing;
    }

    private Set<String> missingPathsFromCache(Set<String> ingestPaths) {
        Set<String> missing = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> entry : namesByParent(ingestPaths).entrySet()) {
            Set<String> visibleNames = cachedVisibleNamesWithAncestorRecovery(entry.getKey());
            addMissingPaths(missing, entry, visibleNames);
        }
        return missing;
    }

    private void addMissingPaths(
            Set<String> missing,
            Map.Entry<String, Set<String>> expectedNamesByDirectory,
            Set<String> visibleNames
    ) {
        if (visibleNames == null) {
            expectedNamesByDirectory.getValue().forEach(name ->
                    missing.add(join(expectedNamesByDirectory.getKey(), name)));
            return;
        }
        for (String name : expectedNamesByDirectory.getValue()) {
            if (!visibleNames.contains(name)) {
                missing.add(join(expectedNamesByDirectory.getKey(), name));
            }
        }
    }

    private Set<String> forcedVisibleNamesWithAncestorRecovery(String ingestDirectory) {
        String cloudDriveDirectory = toCloudDrivePath(ingestDirectory);
        try {
            return forcedVisibleNames(cloudDriveDirectory);
        } catch (CloudDrive2ClientException exception) {
            if (exception.getStatusCode() != Status.Code.NOT_FOUND) {
                throw exception;
            }
        }

        refreshAncestorDirectories(ingestDirectory);
        try {
            return forcedVisibleNames(cloudDriveDirectory);
        } catch (CloudDrive2ClientException exception) {
            if (exception.getStatusCode() == Status.Code.NOT_FOUND) {
                return null;
            }
            throw exception;
        }
    }

    private Set<String> cachedVisibleNamesWithAncestorRecovery(String ingestDirectory) {
        String cloudDriveDirectory = toCloudDrivePath(ingestDirectory);
        try {
            return cachedVisibleNames(cloudDriveDirectory);
        } catch (CloudDrive2ClientException exception) {
            if (exception.getStatusCode() != Status.Code.NOT_FOUND) {
                throw exception;
            }
        }

        refreshAncestorDirectories(ingestDirectory);
        try {
            return cachedVisibleNames(cloudDriveDirectory);
        } catch (CloudDrive2ClientException exception) {
            if (exception.getStatusCode() == Status.Code.NOT_FOUND) {
                return null;
            }
            throw exception;
        }
    }

    private Set<String> forcedVisibleNames(String cloudDriveDirectory) {
        return fileOperations.list(cloudDriveDirectory, true).stream()
                .map(CloudDrive2FileEntry::name)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
    }

    private Set<String> cachedVisibleNames(String cloudDriveDirectory) {
        return fileOperations.list(cloudDriveDirectory, false).stream()
                .map(CloudDrive2FileEntry::name)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
    }

    private void refreshAncestorDirectories(String ingestDirectory) {
        String cloudDriveDirectory = toCloudDrivePath(ingestDirectory);
        String cloudDrivePrefix = targetRegistry.cloudDriveRootForIngestPath(
                ingestDirectory, properties.getIngestPathPrefix(), properties.getCloudDrivePathPrefix());
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
        return targetRegistry.toCloudDrivePath(ingestPath,
                properties.getIngestPathPrefix(), properties.getCloudDrivePathPrefix());
    }

    private List<MoveEvidence> captureMoveEvidence(List<MoveOperation> operations, String targetDirectory) {
        String sourceDirectory = toCloudDrivePath(parentPath(operations.get(0).sourcePath()));
        String cloudTargetDirectory = toCloudDrivePath(targetDirectory);
        Map<String, CloudDrive2FileEntry> sources = entriesByName(fileOperations.list(sourceDirectory, true));
        Map<String, CloudDrive2FileEntry> targets = entriesByName(fileOperations.list(cloudTargetDirectory, true));
        List<MoveEvidence> evidence = new ArrayList<>();
        for (MoveOperation operation : operations) {
            String name = fileName(operation.sourcePath());
            CloudDrive2FileEntry source = entryByName(sources, name);
            if (source == null || source.size() < 0) {
                throw new CloudDrive2ClientException("CD2 移动源身份或字节不可见，拒绝写入: " + operation.sourcePath());
            }
            if (containsNameIgnoreCase(targets, name)) {
                throw new CloudDrive2ClientException("CD2 移动目标存在冲突，拒绝写入: " + join(targetDirectory, name));
            }
            evidence.add(new MoveEvidence(toCloudDrivePath(operation.sourcePath()), cloudTargetDirectory,
                    name, source.size(), source.id(), source.directory()));
        }
        return List.copyOf(evidence);
    }

    private boolean containsNameIgnoreCase(Map<String, CloudDrive2FileEntry> entries, String name) {
        return entries.keySet().stream().anyMatch(existing -> existing.equalsIgnoreCase(name));
    }

    private CloudDrive2FileEntry entryByName(Map<String, CloudDrive2FileEntry> entries, String name) {
        return entries.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private void reconcileMoveFailure(List<MoveEvidence> evidence, CloudDrive2ClientException originalFailure,
                                      LibraryOrganizationProgressObserver progressObserver) {
        // CD2 IDs encode paths. Neither a nonempty ID nor source-present/target-absent can settle
        // an accepted legacy batch or authorize compensation. Movie/TV use the durable official identity path.
        progressObserver.record("CD2 移动结果未知，保留文件和原始异常；禁止基于路径 ID 补发", "count=" + evidence.size());
        throw originalFailure;
    }


    // 大小写别名、重复项或路径不一致的上游目录不能作为残余写入的证据。
    private Map<String, CloudDrive2FileEntry> entriesByName(List<CloudDrive2FileEntry> entries) {
        Map<String, CloudDrive2FileEntry> result = new LinkedHashMap<>();
        for (CloudDrive2FileEntry entry : entries) {
            if (containsNameIgnoreCase(result, entry.name())) {
                throw new CloudDrive2ClientException("CD2 目录清单存在重名冲突: " + entry.name());
            }
            result.put(entry.name(), entry);
        }
        return result;
    }

    private String moveFailureDetail(CloudDrive2ClientException failure, List<MoveEvidence> evidence) {
        return "status=" + failure.getStatusCode() + ", error=" + failure.getMessage()
                + ", evidence=" + evidence.stream().map(move -> move.name() + "(" + move.size() + "," + move.id() + ")").toList();
    }

    private String moveFailureEvidence(List<MoveEvidence> evidence, MoveObservation observation) {
        return evidence.stream().map(move -> move.name() + " source="
                + (observation.sources().containsKey(move.name()) ? "present" : "absent")
                + " target=" + (observation.targets().containsKey(move.name()) ? "present" : "absent")).toList().toString();
    }

    private record MoveEvidence(String sourcePath, String targetDirectory, String name, long size, String id,
                                boolean directory) {
    }

    private record MoveObservation(Map<String, CloudDrive2FileEntry> sources,
                                   Map<String, CloudDrive2FileEntry> targets) {
    }

    private record MoveRequestGroup(String sourceDirectory, String targetDirectory) {
    }
}
