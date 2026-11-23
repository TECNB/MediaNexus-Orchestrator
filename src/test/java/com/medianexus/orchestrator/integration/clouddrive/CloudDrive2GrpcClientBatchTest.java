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
        List<Integer> renames = new java.util.concurrent.CopyOnWriteArrayList<>(), moves = new java.util.concurrent.CopyOnWriteArrayList<>();
        var server = ServerBuilder.forPort(0).addService(new CloudDriveFileSrvGrpc.CloudDriveFileSrvImplBase() {
            @Override public void renameFiles(RenameFilesRequest request, StreamObserver<FileOperationResult> response) {
                renames.add(request.getRenameFilesCount());
                response.onNext(FileOperationResult.newBuilder().setSuccess(true).build()); response.onCompleted();
            }
            @Override public void moveFile(MoveFileRequest request, StreamObserver<FileOperationResult> response) {
                moves.add(request.getTheFilePathsCount());
                response.onNext(FileOperationResult.newBuilder().setSuccess(!request.getTheFilePaths(0).equals("/source/11.mkv")).build()); response.onCompleted();
            }
        }).build().start();
        var properties = new CloudDrive2Properties(); properties.setOrganizationEnabled(true);
        properties.setHost("127.0.0.1"); properties.setPort(server.getPort()); properties.setApiToken("test");
        var client = new CloudDrive2GrpcClient(properties);
        try {
            var paths = IntStream.rangeClosed(1, 25).mapToObj(i -> "/source/" + i + ".mkv").toList();
            client.rename(paths.stream().map(path -> new CloudDrive2RenameOperation(path, "new-" + path.substring(8))).toList());
            assertThat(renames).allMatch(count -> count <= 10);
            assertThat(renames.stream().mapToInt(Integer::intValue).sum()).isEqualTo(25);
            assertThatThrownBy(() -> client.move(paths, "/target")).isInstanceOf(CloudDrive2ClientException.class);
            assertThat(moves).containsExactlyInAnyOrder(10, 10);
        } finally { client.close(); server.shutdownNow(); }
    }

    @Test void overlapsTwoBalancedRequestsAndDrainsAcceptedWorkBeforeFailure() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(2);
        var release = new java.util.concurrent.CountDownLatch(1);
        var firstRejected = new java.util.concurrent.CountDownLatch(1);
        var requests = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
        var server = ServerBuilder.forPort(0).addService(new CloudDriveFileSrvGrpc.CloudDriveFileSrvImplBase() {
            @Override public void moveFile(MoveFileRequest request, StreamObserver<FileOperationResult> response) {
                requests.add(request.getTheFilePathsCount()); entered.countDown();
                if (request.getTheFilePaths(0).equals("/source/1.mkv")) {
                    response.onNext(FileOperationResult.newBuilder().setSuccess(false).build());
                    response.onCompleted(); firstRejected.countDown(); return;
                }
                try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                response.onNext(FileOperationResult.newBuilder().setSuccess(true).build()); response.onCompleted();
            }
        }).build().start();
        var properties = new CloudDrive2Properties(); properties.setOrganizationEnabled(true);
        properties.setHost("127.0.0.1"); properties.setPort(server.getPort()); properties.setApiToken("test");
        var client = new CloudDrive2GrpcClient(properties);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var call = executor.submit(() -> client.move(IntStream.rangeClosed(1, 25)
                    .mapToObj(i -> "/source/" + i + ".mkv").toList(), "/target"));
            assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(firstRejected.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(call.isDone()).isFalse();
            release.countDown();
            assertThatThrownBy(() -> call.get(2, java.util.concurrent.TimeUnit.SECONDS))
                    .hasCauseInstanceOf(CloudDrive2ClientException.class);
            assertThat(requests).containsExactlyInAnyOrder(10, 10);
        } finally { release.countDown(); executor.shutdownNow(); client.close(); server.shutdownNow(); }
    }

    @Test void balancesElevenIndependentRenamesAcrossTwoRequests() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(2);
        var counts = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
        var server = ServerBuilder.forPort(0).addService(new CloudDriveFileSrvGrpc.CloudDriveFileSrvImplBase() {
            @Override public void renameFiles(RenameFilesRequest request, StreamObserver<FileOperationResult> response) {
                counts.add(request.getRenameFilesCount()); entered.countDown();
                try { assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                response.onNext(FileOperationResult.newBuilder().setSuccess(true).build()); response.onCompleted();
            }
        }).build().start();
        var properties = new CloudDrive2Properties(); properties.setOrganizationEnabled(true);
        properties.setHost("127.0.0.1"); properties.setPort(server.getPort()); properties.setApiToken("test");
        var client = new CloudDrive2GrpcClient(properties);
        try {
            client.rename(IntStream.rangeClosed(1, 11).mapToObj(i ->
                    new CloudDrive2RenameOperation("/source/" + i + ".mkv", "new-" + i + ".mkv")).toList());
            assertThat(counts).containsExactlyInAnyOrder(6, 5);
        } finally { client.close(); server.shutdownNow(); }
    }

    @Test void keepsDependentRenamesInOriginalOrder() throws Exception {
        var paths = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var active = new java.util.concurrent.atomic.AtomicInteger();
        var max = new java.util.concurrent.atomic.AtomicInteger();
        var server = ServerBuilder.forPort(0).addService(new CloudDriveFileSrvGrpc.CloudDriveFileSrvImplBase() {
            @Override public void renameFiles(RenameFilesRequest request, StreamObserver<FileOperationResult> response) {
                max.accumulateAndGet(active.incrementAndGet(), Math::max);
                request.getRenameFilesList().forEach(row -> paths.add(row.getTheFilePath()));
                active.decrementAndGet();
                response.onNext(FileOperationResult.newBuilder().setSuccess(true).build()); response.onCompleted();
            }
        }).build().start();
        var properties = new CloudDrive2Properties(); properties.setOrganizationEnabled(true);
        properties.setHost("127.0.0.1"); properties.setPort(server.getPort()); properties.setApiToken("test");
        var client = new CloudDrive2GrpcClient(properties);
        try {
            var operations = new ArrayList<CloudDrive2RenameOperation>();
            operations.add(new CloudDrive2RenameOperation("/source/a.mkv", "b.mkv"));
            operations.add(new CloudDrive2RenameOperation("/source/b.mkv", "c.mkv"));
            IntStream.rangeClosed(3, 21).forEach(i -> operations.add(new CloudDrive2RenameOperation("/source/"+i+".mkv", "new-"+i+".mkv")));
            client.rename(operations);
            assertThat(paths).containsExactlyElementsOf(operations.stream().map(CloudDrive2RenameOperation::sourcePath).toList());
            assertThat(max.get()).isEqualTo(1);
        } finally { client.close(); server.shutdownNow(); }
    }

    @Test void drainsAcceptedRequestWhenSecondSubmissionIsRejected() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var rejected = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var server = ServerBuilder.forPort(0).addService(new CloudDriveFileSrvGrpc.CloudDriveFileSrvImplBase() {
            @Override public void moveFile(MoveFileRequest request, StreamObserver<FileOperationResult> response) {
                entered.countDown();
                try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                response.onNext(FileOperationResult.newBuilder().setSuccess(true).build()); response.onCompleted();
            }
        }).build().start();
        var properties = new CloudDrive2Properties(); properties.setOrganizationEnabled(true);
        properties.setHost("127.0.0.1"); properties.setPort(server.getPort()); properties.setApiToken("test");
        var client = new CloudDrive2GrpcClient(properties);
        var requests = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<Runnable>()) {
            int accepted;
            @Override public void execute(Runnable command) {
                if (accepted++ == 1) {
                    rejected.countDown(); throw new java.util.concurrent.RejectedExecutionException("closing");
                }
                super.execute(command);
            }
        };
        ((java.util.concurrent.ExecutorService) org.springframework.test.util.ReflectionTestUtils
                .getField(client, "fileMutationRequests")).shutdown();
        org.springframework.test.util.ReflectionTestUtils.setField(client, "fileMutationRequests", requests);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var call = executor.submit(() -> client.move(List.of("/source/a.mkv", "/source/b.mkv"), "/target"));
            assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(rejected.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> call.get(100, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown();
            assertThatThrownBy(() -> call.get(2, java.util.concurrent.TimeUnit.SECONDS))
                    .hasCauseInstanceOf(java.util.concurrent.RejectedExecutionException.class);
        } finally { release.countDown(); executor.shutdownNow(); client.close(); server.shutdownNow(); }
    }
}
