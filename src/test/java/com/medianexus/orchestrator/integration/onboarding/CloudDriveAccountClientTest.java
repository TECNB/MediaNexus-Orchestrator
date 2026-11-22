package com.medianexus.orchestrator.integration.onboarding;

import static org.assertj.core.api.Assertions.*;
import com.medianexus.orchestrator.config.CloudDrive2Properties;
import com.medianexus.orchestrator.integration.clouddrive.proto.*;
import com.google.protobuf.Empty;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CloudDriveAccountClientTest {
    @Test void refreshesActualPlaybackMountAncestorsBeforeTarget() throws Exception {
        List<String> paths = new ArrayList<>();
        var server = ServerBuilder.forPort(0).addService(new CloudDriveFileSrvGrpc.CloudDriveFileSrvImplBase() {
            @Override public void getMountPoints(Empty request, StreamObserver<GetMountPointsResult> response) {
                response.onNext(GetMountPointsResult.newBuilder().addMountPoints(MountPoint.newBuilder()
                        .setMountPoint("/play").setSourceDir("/Relay").setIsMounted(true)).build());
                response.onCompleted();
            }
            @Override public void getSubFiles(ListSubFileRequest request, StreamObserver<SubFilesReply> response) {
                assertThat(request.getForceRefresh()).isTrue();
                paths.add(request.getPath());
                response.onNext(SubFilesReply.getDefaultInstance()); response.onCompleted();
            }
        }).build().start();
        try {
            var properties = new CloudDrive2Properties();
            properties.setHost("127.0.0.1"); properties.setPort(server.getPort()); properties.setApiToken("test");
            var client = new CloudDriveAccountClient();
            client.refreshMountedDirectory(properties, "/play/Media/Movie/Film (2026)");
            assertThat(paths).containsExactly("/Relay", "/Relay/Media", "/Relay/Media/Movie", "/Relay/Media/Movie/Film (2026)");
            assertThatThrownBy(() -> client.refreshMountedDirectory(properties, "/play-other/Film"))
                    .isInstanceOf(com.medianexus.orchestrator.common.exception.BusinessException.class);
            assertThat(paths).hasSize(4);
        } finally { server.shutdownNow(); }
    }
}
