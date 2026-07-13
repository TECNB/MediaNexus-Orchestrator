package com.medianexus.orchestrator.integration.openlist;

import static com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan.fileName;
import static com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan.normalizePath;
import static com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan.parentPath;

import com.medianexus.orchestrator.service.organization.LibraryOrganizer;
import com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan;
import com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan.DeleteOperation;
import com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan.MoveOperation;
import com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan.RenameOperation;
import com.medianexus.orchestrator.service.organization.LibraryOrganizationProgressObserver;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 关闭 CD2 整理时保留原有 OpenList 行为的兼容 Adapter。
 */
@Component
public class OpenListLibraryOrganizer implements LibraryOrganizer {

    private static final int BATCH_SIZE = 10;

    private final OpenListClient openListClient;

    public OpenListLibraryOrganizer(OpenListClient openListClient) {
        this.openListClient = openListClient;
    }

    @Override
    public void organize(LibraryOrganizationPlan plan, LibraryOrganizationProgressObserver progressObserver) {
        rename(plan.renames(), progressObserver);
        move(plan.moves(), progressObserver);
        delete(plan.deletions(), progressObserver);
        progressObserver.record("正在清理空目录", plan.targetDirectory());
        cleanupEmptySourceDirectories(plan);
        progressObserver.record("空目录清理完成", plan.targetDirectory());
    }

    private void rename(List<RenameOperation> operations, LibraryOrganizationProgressObserver progressObserver) {
        Map<String, Map<String, String>> byDirectory = new LinkedHashMap<>();
        for (RenameOperation operation : operations) {
            byDirectory.computeIfAbsent(parentPath(operation.sourcePath()), ignored -> new LinkedHashMap<>())
                    .put(fileName(operation.sourcePath()), operation.targetName());
        }
        for (Map.Entry<String, Map<String, String>> entry : byDirectory.entrySet()) {
            List<Map<String, String>> batches = chunkMap(entry.getValue());
            for (int index = 0; index < batches.size(); index++) {
                Map<String, String> batch = batches.get(index);
                String detail = batchDetail(entry.getKey(), batch.size(), index + 1, batches.size());
                progressObserver.record("正在批量重命名文件", detail);
                openListClient.batchRename(entry.getKey(), batch);
                progressObserver.record("批量重命名完成", detail);
            }
        }
    }

    private void move(List<MoveOperation> operations, LibraryOrganizationProgressObserver progressObserver) {
        Map<MoveGroup, List<String>> byDirectory = new LinkedHashMap<>();
        for (MoveOperation operation : operations) {
            MoveGroup group = new MoveGroup(parentPath(operation.sourcePath()), operation.targetDirectory());
            byDirectory.computeIfAbsent(group, ignored -> new ArrayList<>()).add(fileName(operation.sourcePath()));
        }
        for (Map.Entry<MoveGroup, List<String>> entry : byDirectory.entrySet()) {
            List<List<String>> batches = chunkList(entry.getValue());
            for (int index = 0; index < batches.size(); index++) {
                List<String> batch = batches.get(index);
                String detail = "srcDir=" + entry.getKey().sourceDirectory()
                        + ", dstDir=" + entry.getKey().targetDirectory()
                        + ", count=" + batch.size()
                        + ", batch=" + (index + 1) + "/" + batches.size();
                progressObserver.record("正在批量移动文件到 Season 目录", detail);
                openListClient.move(
                        entry.getKey().sourceDirectory(),
                        entry.getKey().targetDirectory(),
                        batch
                );
                progressObserver.record("批量移动完成", detail);
            }
        }
    }

    private void delete(List<DeleteOperation> operations, LibraryOrganizationProgressObserver progressObserver) {
        Map<String, List<String>> byDirectory = new LinkedHashMap<>();
        for (DeleteOperation operation : operations) {
            byDirectory.computeIfAbsent(parentPath(operation.path()), ignored -> new ArrayList<>())
                    .add(fileName(operation.path()));
        }
        for (Map.Entry<String, List<String>> entry : byDirectory.entrySet()) {
            List<List<String>> batches = chunkList(entry.getValue());
            for (int index = 0; index < batches.size(); index++) {
                List<String> batch = batches.get(index);
                String detail = batchDetail(entry.getKey(), batch.size(), index + 1, batches.size());
                progressObserver.record("正在删除跳过文件", detail);
                openListClient.remove(entry.getKey(), batch);
                progressObserver.record("跳过文件删除完成", detail);
            }
        }
    }

    private void cleanupEmptySourceDirectories(LibraryOrganizationPlan plan) {
        String targetDirectory = normalizePath(plan.targetDirectory());
        Set<String> candidates = new LinkedHashSet<>();
        plan.moves().forEach(operation ->
                addCleanupCandidates(candidates, targetDirectory, parentPath(operation.sourcePath())));
        plan.deletions().forEach(operation ->
                addCleanupCandidates(candidates, targetDirectory, parentPath(operation.path())));
        candidates.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .filter(candidate -> !plan.cleanupExcludedDirectories().contains(candidate))
                .forEach(candidate -> {
                    try {
                        if (openListClient.listFiles(candidate).isEmpty()) {
                            openListClient.remove(parentPath(candidate), List.of(fileName(candidate)));
                        }
                    } catch (RuntimeException ignored) {
                        // 空目录清理沿用原有 best-effort 契约，不覆盖已经完成的文件整理。
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

    private String batchDetail(String sourceDirectory, int count, int batchNumber, int batchCount) {
        return "srcDir=" + sourceDirectory + ", count=" + count + ", batch=" + batchNumber + "/" + batchCount;
    }

    private List<Map<String, String>> chunkMap(Map<String, String> values) {
        List<Map.Entry<String, String>> entries = new ArrayList<>(values.entrySet());
        List<Map<String, String>> chunks = new ArrayList<>();
        for (int start = 0; start < entries.size(); start += BATCH_SIZE) {
            Map<String, String> chunk = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : entries.subList(start, Math.min(start + BATCH_SIZE, entries.size()))) {
                chunk.put(entry.getKey(), entry.getValue());
            }
            chunks.add(chunk);
        }
        return chunks;
    }

    private List<List<String>> chunkList(List<String> values) {
        List<List<String>> chunks = new ArrayList<>();
        for (int start = 0; start < values.size(); start += BATCH_SIZE) {
            chunks.add(new ArrayList<>(values.subList(start, Math.min(start + BATCH_SIZE, values.size()))));
        }
        return chunks;
    }

    private record MoveGroup(String sourceDirectory, String targetDirectory) {
    }
}
