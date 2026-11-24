package com.medianexus.orchestrator.integration.clouddrive;

import static com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan.join;
import static com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan.normalizePath;
import static com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan.parentPath;
import static com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan.fileName;

import com.medianexus.orchestrator.config.CloudDrive2Properties;
import com.medianexus.orchestrator.integration.clouddrive.proto.CloudDriveFileSrvGrpc;
import com.medianexus.orchestrator.integration.clouddrive.proto.FileOperationResult;
import com.medianexus.orchestrator.integration.clouddrive.proto.ListSubFileRequest;
import com.medianexus.orchestrator.integration.clouddrive.proto.MoveFileRequest;
import com.medianexus.orchestrator.integration.clouddrive.proto.MultiFileRequest;
import com.medianexus.orchestrator.integration.clouddrive.proto.RenameFileRequest;
import com.medianexus.orchestrator.integration.clouddrive.proto.RenameFilesRequest;
import com.medianexus.orchestrator.integration.clouddrive.proto.SubFilesReply;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class CloudDrive2GrpcClient implements CloudDrive2FileOperations {
    private static final Logger log = LoggerFactory.getLogger(CloudDrive2GrpcClient.class);
    private static final int MAX_MUTATION_REQUESTS = 6;

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final CloudDrive2Properties properties;
    private final ManagedChannel channel;
    private final CloudDriveFileSrvGrpc.CloudDriveFileSrvBlockingStub blockingStub;
    private final ExecutorService fileMutationRequests = Executors.newFixedThreadPool(MAX_MUTATION_REQUESTS);

    CloudDrive2GrpcClient(CloudDrive2Properties properties) {
        this.properties = properties;
        if (!properties.isOrganizationEnabled()) {
            this.channel = null;
            this.blockingStub = null;
            return;
        }
        validateConfiguration(properties);
        this.channel = ManagedChannelBuilder.forAddress(properties.getHost().trim(), properties.getPort())
                .usePlaintext()
                .build();
        Metadata metadata = new Metadata();
        metadata.put(AUTHORIZATION, bearerToken(properties.getApiToken()));
        this.blockingStub = CloudDriveFileSrvGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }

    @Override
    public void requireWritersQuiet() {
        try {
            var counts = stub().getAllTasksCount(com.google.protobuf.Empty.getDefaultInstance());
            if (counts.getDownloadCount() != 0 || counts.getUploadCount() != 0 || counts.getCopyTaskCount() != 0)
                throw new CloudDrive2ClientException("CD2 写入/传输队列未结清");
        } catch (StatusRuntimeException unavailable) {
            throw new CloudDrive2ClientException("无法核实 CD2 写入队列，禁止恢复写入");
        }
    }

    @Override
    public List<CloudDrive2FileEntry> list(String path, boolean forceRefresh) {
        ListSubFileRequest request = ListSubFileRequest.newBuilder()
                .setPath(path)
                .setForceRefresh(forceRefresh)
                .build();
        try {
            Iterator<SubFilesReply> replies = stub().getSubFiles(request);
            List<CloudDrive2FileEntry> files = new ArrayList<>();
            while (replies.hasNext()) {
                replies.next().getSubFilesList().forEach(file -> files.add(new CloudDrive2FileEntry(
                        file.getName(),
                        file.getFullPathName(),
                        file.getSize(),
                        file.getIsDirectory(),
                        file.getId()
                )));
            }
            return files;
        } catch (StatusRuntimeException exception) {
            throw grpcFailure("CloudDrive2 list failed: " + path, exception);
        }
    }

    @Override
    public void rename(List<CloudDrive2RenameOperation> operations) {
        if (operations.isEmpty()) {
            return;
        }
        List<String> sources = operations.stream().map(CloudDrive2RenameOperation::sourcePath).toList();
        List<String> targets = operations.stream()
                .map(operation -> join(parentPath(operation.sourcePath()), operation.targetName())).toList();
        int requestWidth = independentRenames(sources, targets) ? MAX_MUTATION_REQUESTS : 1;
        executeMutationBatches(operations, requestWidth, batch -> {
            RenameFilesRequest.Builder request = RenameFilesRequest.newBuilder();
            batch.forEach(operation -> request.addRenameFiles(RenameFileRequest.newBuilder()
                    .setTheFilePath(operation.sourcePath()).setNewName(operation.targetName()).build()));
            long started = System.nanoTime();
            try {
                requireSuccess("rename", stub().renameFiles(request.build()));
            } catch (StatusRuntimeException exception) {
                throw grpcFailure("CloudDrive2 rename failed", exception);
            } finally {
                log.info("CD2 batch operation=rename count={} elapsedMs={}", batch.size(),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            }
        });
    }

    @Override
    public void move(List<String> sourcePaths, String targetDirectory) {
        if (sourcePaths.isEmpty()) {
            return;
        }
        Set<String> targetNames = new HashSet<>();
        boolean independent = independentPaths(sourcePaths) && sourcePaths.stream()
                .map(CloudDrive2GrpcClient::canonicalPath).map(path -> parentPath(path)).distinct().count() == 1
                && sourcePaths.stream().allMatch(path -> targetNames.add(fileName(path).toLowerCase(Locale.ROOT)));
        executeMutationBatches(sourcePaths, independent ? MAX_MUTATION_REQUESTS : 1, batch -> {
            MoveFileRequest request = MoveFileRequest.newBuilder().addAllTheFilePaths(batch)
                    .setDestPath(targetDirectory).setConflictPolicy(MoveFileRequest.ConflictPolicy.Skip).build();
            long started = System.nanoTime();
            try {
                requireSuccess("move", stub().moveFile(request));
            } catch (StatusRuntimeException exception) {
                throw grpcFailure("CloudDrive2 move failed", exception);
            } finally {
                log.info("CD2 batch operation=move count={} elapsedMs={}", batch.size(),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            }
        });
    }

    @Override
    public List<String> moveKeepingBoth(String sourcePath, String targetDirectory) {
        MoveFileRequest request = MoveFileRequest.newBuilder()
                .addTheFilePaths(sourcePath)
                .setDestPath(targetDirectory)
                .setConflictPolicy(MoveFileRequest.ConflictPolicy.Rename)
                .build();
        try {
            FileOperationResult reply = stub().moveFile(request);
            requireSuccess("move preserving conflicts", reply);
            return reply.getResultFilePathsList();
        } catch (StatusRuntimeException exception) {
            throw grpcFailure("CloudDrive2 move failed: " + sourcePath, exception);
        }
    }

    @Override
    public void delete(List<String> paths) {
        if (paths.isEmpty()) {
            return;
        }
        try {
            requireSuccess("delete", stub().deleteFiles(MultiFileRequest.newBuilder().addAllPath(paths).build()));
        } catch (StatusRuntimeException exception) {
            throw grpcFailure("CloudDrive2 delete failed", exception);
        }
    }

    private <T> void executeMutationBatches(List<T> operations, int requestWidth, Consumer<List<T>> submit) {
        for (int offset = 0; offset < operations.size(); offset += 10 * requestWidth) {
            int end = Math.min(offset + 10 * requestWidth, operations.size());
            int groups = Math.min(requestWidth, end - offset);
            if (requestWidth == 1 || end - offset == 1) {
                submit.accept(operations.subList(offset, end));
                continue;
            }
            List<CompletableFuture<Void>> pending = new ArrayList<>();
            RuntimeException submissionFailure = null;
            try {
                for (int group = 0; group < groups; group++) {
                    int start = offset + group * (end - offset) / groups;
                    int stop = offset + (group + 1) * (end - offset) / groups;
                    List<T> batch = operations.subList(start, stop);
                    pending.add(CompletableFuture.runAsync(() -> submit.accept(batch), fileMutationRequests));
                }
            } catch (RuntimeException exception) {
                submissionFailure = exception;
            }
            // 失败或调用线程中断都不取消已发写请求；全部结清后才能释放整理认领。
            try {
                CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).join();
            } catch (CompletionException exception) {
                if (submissionFailure != null) {
                    submissionFailure.addSuppressed(exception);
                    throw submissionFailure;
                }
                if (exception.getCause() instanceof RuntimeException failure) throw failure;
                throw exception;
            }
            if (submissionFailure != null) throw submissionFailure;
        }
    }

    private boolean independentRenames(List<String> sources, List<String> targets) {
        if (!independentPaths(sources) || !independentPaths(targets)) return false;
        for (String source : sources) {
            for (String target : targets) {
                if (overlappingPaths(canonicalPath(source), canonicalPath(target))) return false;
            }
        }
        return true;
    }

    private boolean independentPaths(List<String> paths) {
        List<String> canonical = paths.stream().map(CloudDrive2GrpcClient::canonicalPath).toList();
        for (int i = 0; i < canonical.size(); i++) {
            for (int j = i + 1; j < canonical.size(); j++) {
                if (overlappingPaths(canonical.get(i), canonical.get(j))) return false;
            }
        }
        return true;
    }

    private static String canonicalPath(String path) {
        return normalizePath(path).toLowerCase(Locale.ROOT);
    }

    private static boolean overlappingPaths(String first, String second) {
        return first.equals(second) || first.startsWith(second + "/") || second.startsWith(first + "/");
    }

    private CloudDriveFileSrvGrpc.CloudDriveFileSrvBlockingStub stub() {
        if (!properties.isOrganizationEnabled()) {
            throw new CloudDrive2ClientException("CloudDrive2 媒体整理已停用");
        }
        Duration timeout = properties.getOperationTimeout();
        return blockingStub.withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void requireSuccess(String operation, FileOperationResult operationResult) {
        if (!operationResult.getSuccess()) {
            throw new CloudDrive2ClientException(
                    "CloudDrive2 " + operation + " rejected: " + operationResult.getErrorMessage()
            );
        }
    }

    private CloudDrive2ClientException grpcFailure(String message, StatusRuntimeException exception) {
        return new CloudDrive2ClientException(message + ": " + exception.getStatus().getDescription(),
                exception.getStatus().getCode(), exception);
    }

    private static void validateConfiguration(CloudDrive2Properties properties) {
        if (!StringUtils.hasText(properties.getHost())) {
            throw new IllegalStateException("MEDIANEXUS_CLOUDDRIVE2_HOST must be configured when CD2 organization is enabled");
        }
        if (!StringUtils.hasText(properties.getApiToken())) {
            throw new IllegalStateException("MEDIANEXUS_CLOUDDRIVE2_API_TOKEN must be configured when CD2 organization is enabled");
        }
    }

    private static String bearerToken(String apiToken) {
        String normalized = apiToken.trim();
        return normalized.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())
                ? normalized
                : "Bearer " + normalized;
    }

    @PreDestroy
    void close() {
        fileMutationRequests.shutdown();
        if (channel == null) return;
        channel.shutdown();
        try {
            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                channel.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            channel.shutdownNow();
        }
    }
}
