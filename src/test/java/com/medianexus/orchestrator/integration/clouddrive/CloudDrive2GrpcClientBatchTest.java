package com.medianexus.orchestrator.integration.clouddrive;

import static org.assertj.core.api.Assertions.*;
import com.medianexus.orchestrator.config.CloudDrive2Properties;
import com.medianexus.orchestrator.integration.clouddrive.proto.*;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class CloudDrive2GrpcClientBatchTest {
    @Test void boundsRenameAndMoveRequestsAndStopsAfterRejectedBatch() throws Exception {
        List<Integer> renames = new ArrayList<>(), moves = new ArrayList<>();
        var server = ServerBuilder.forPort(0).addService(new CloudDriveFileSrvGrpc.CloudDriveFileSrvImplBase() {
            @Override public void renameFiles(RenameFilesRequest request, StreamObserver<FileOperationResult> response) {
                renames.add(request.getRenameFilesCount());
                response.onNext(FileOperationResult.newBuilder().setSuccess(true).build()); response.onCompleted();
            }
            @Override public void moveFile(MoveFileRequest request, StreamObserver<FileOperationResult> response) {
                moves.add(request.getTheFilePathsCount());
                response.onNext(FileOperationResult.newBuilder().setSuccess(moves.size() != 2).build()); response.onCompleted();
            }
        }).build().start();
        var properties = new CloudDrive2Properties(); properties.setOrganizationEnabled(true);
        properties.setHost("127.0.0.1"); properties.setPort(server.getPort()); properties.setApiToken("test");
        var client = new CloudDrive2GrpcClient(properties);
        try {
            var paths = IntStream.rangeClosed(1, 25).mapToObj(i -> "/source/" + i + ".mkv").toList();
            client.rename(paths.stream().map(path -> new CloudDrive2RenameOperation(path, "new-" + path.substring(8))).toList());
            assertThat(renames).containsExactly(10, 10, 5);
            assertThatThrownBy(() -> client.move(paths, "/target")).isInstanceOf(CloudDrive2ClientException.class);
            assertThat(moves).containsExactly(10, 10);
        } finally { client.close(); server.shutdownNow(); }
    }
}
