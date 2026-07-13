package com.medianexus.orchestrator.integration.clouddrive;

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
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConditionalOnProperty(
        prefix = "medianexus.clouddrive2",
        name = "organization-enabled",
        havingValue = "true"
)
class CloudDrive2GrpcClient implements CloudDrive2FileOperations {

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final CloudDrive2Properties properties;
    private final ManagedChannel channel;
    private final CloudDriveFileSrvGrpc.CloudDriveFileSrvBlockingStub blockingStub;

    CloudDrive2GrpcClient(CloudDrive2Properties properties) {
        validateConfiguration(properties);
        this.properties = properties;
        this.channel = ManagedChannelBuilder.forAddress(properties.getHost().trim(), properties.getPort())
                .usePlaintext()
                .build();
        Metadata metadata = new Metadata();
        metadata.put(AUTHORIZATION, bearerToken(properties.getApiToken()));
        this.blockingStub = CloudDriveFileSrvGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
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
                        file.getIsDirectory()
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
        RenameFilesRequest.Builder request = RenameFilesRequest.newBuilder();
        operations.forEach(operation -> request.addRenameFiles(RenameFileRequest.newBuilder()
                .setTheFilePath(operation.sourcePath())
                .setNewName(operation.targetName())
                .build()));
        try {
            requireSuccess("rename", stub().renameFiles(request.build()));
        } catch (StatusRuntimeException exception) {
            throw grpcFailure("CloudDrive2 rename failed", exception);
        }
    }

    @Override
    public void move(List<String> sourcePaths, String targetDirectory) {
        if (sourcePaths.isEmpty()) {
            return;
        }
        MoveFileRequest request = MoveFileRequest.newBuilder()
                .addAllTheFilePaths(sourcePaths)
                .setDestPath(targetDirectory)
                .setConflictPolicy(MoveFileRequest.ConflictPolicy.Skip)
                .build();
        try {
            requireSuccess("move", stub().moveFile(request));
        } catch (StatusRuntimeException exception) {
            throw grpcFailure("CloudDrive2 move failed", exception);
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

    private CloudDriveFileSrvGrpc.CloudDriveFileSrvBlockingStub stub() {
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
        return new CloudDrive2ClientException(message, exception.getStatus().getCode(), exception);
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
