package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.AutoSymlinkProperties;
import com.medianexus.orchestrator.config.CloudDrive2Properties;
import com.medianexus.orchestrator.config.OpenListProperties;
import com.medianexus.orchestrator.config.QasProperties;
import com.medianexus.orchestrator.config.WeComProperties;
import com.sun.net.httpserver.HttpServer;
import com.google.protobuf.Empty;
import com.medianexus.orchestrator.integration.clouddrive.proto.CloudAPI;
import com.medianexus.orchestrator.integration.clouddrive.proto.CloudAPIList;
import com.medianexus.orchestrator.integration.clouddrive.proto.CloudDriveFileSrvGrpc;
import com.medianexus.orchestrator.integration.clouddrive.proto.FileOperationResult;
import com.medianexus.orchestrator.integration.clouddrive.proto.GetMountPointsResult;
import com.medianexus.orchestrator.integration.clouddrive.proto.ListSubFileRequest;
import com.medianexus.orchestrator.integration.clouddrive.proto.MountPoint;
import com.medianexus.orchestrator.integration.clouddrive.proto.SubFilesReply;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SettingsConnectionProbeTest {
    private final ObjectMapper json = new ObjectMapper();
    private final List<String> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
    private HttpServer server;
    private Server grpcServer;

    @BeforeEach
    void startUpstream() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            String body = exchange.getRequestURI().getPath().contains("storage/list") ? """
                    {"code":200,"data":{"content":[{"id":7,"mount_path":"/新盘/Media","driver":"PikPak",
                    "disabled":false,"status":"work","addition":"{\\"password\\":\\"driver-secret\\"}",
                    "mount_details":{"total_space":1000,"used_space":250,"free_space":750}}]}}
                    """ : "{\"errcode\":0,\"access_token\":\"probe-only-token\",\"expires_in\":7200}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopUpstream() {
        server.stop(0);
        if (grpcServer != null) grpcServer.shutdownNow();
    }

    @Test
    void readsOpenListCapacityWithoutReturningDriverCredentials() throws Exception {
        OpenListProperties p = new OpenListProperties();
        p.setBaseUrl(base()); p.setAuthorization("management-secret"); p.setTimeout(Duration.ofSeconds(2));

        var result = new SettingsConnectionProbe(json).check("openlist", p, "CANDIDATE");

        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.resources().get(0).attributes()).containsEntry("used_space", 250);
        assertThat(json.writeValueAsString(result)).doesNotContain("driver-secret", "addition", "management-secret");
        assertThat(requests).containsExactly("GET /api/admin/storage/list");
    }

    @Test
    void neverCallsSmartstrmCallbackWhileChecking() {
        QasProperties p = new QasProperties(); p.setSmartstrmWebhook(base() + "/callback?token=callback-secret");

        var result = new SettingsConnectionProbe(json).check("smartstrm", p, "CANDIDATE");

        assertThat(result.status()).isEqualTo("UNSUPPORTED");
        assertThat(requests).isEmpty();
    }

    @Test
    void disablingOptionalClientsRetainsCredentialsAndRefusesNetworkOperations() {
        var pansou = new com.medianexus.orchestrator.config.PanSouProperties();
        pansou.setBaseUrl(base()); pansou.setUsername("fixture"); pansou.setPassword("retained-secret");
        pansou.setTimeout(Duration.ofSeconds(2)); pansou.setDisabled(true);
        var as = new com.medianexus.orchestrator.config.AutoSymlinkProperties();
        as.setBaseUrl(base()); as.setCookie("retained-cookie"); as.setTimeout(Duration.ofSeconds(2)); as.setDisabled(true);
        var prowlarr = new com.medianexus.orchestrator.config.ProwlarrProperties();
        prowlarr.setBaseUrl(base()); prowlarr.setApiKey("retained-key"); prowlarr.setTimeout(Duration.ofSeconds(2)); prowlarr.setDisabled(true);
        SettingsConnectionProbe probe = new SettingsConnectionProbe(json);
        assertThat(probe.check("pansou", pansou, "CANDIDATE").status()).isEqualTo("NOT_CONFIGURED");
        assertThat(probe.check("autosymlink", as, "CANDIDATE").status()).isEqualTo("NOT_CONFIGURED");
        assertThat(probe.check("prowlarr", prowlarr, "CANDIDATE").status()).isEqualTo("NOT_CONFIGURED");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new com.medianexus.orchestrator.integration.autosymlink.AutoSymlinkClient(as, json)
                        .submitSyncTask("real-task", json.createObjectNode()))
                .isInstanceOf(com.medianexus.orchestrator.integration.autosymlink.AutoSymlinkClientException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new com.medianexus.orchestrator.integration.prowlarr.ProwlarrClient(prowlarr, json).search("movie"))
                .isInstanceOf(com.medianexus.orchestrator.integration.prowlarr.ProwlarrClientException.class);
        assertThat(pansou.getPassword()).isEqualTo("retained-secret");
        assertThat(as.getCookie()).isEqualTo("retained-cookie");
        assertThat(prowlarr.getApiKey()).isEqualTo("retained-key");
        assertThat(requests).isEmpty();
    }

    @Test
    void checksWecomAuthenticationWithoutSendingAMessageAndHonorsDisable() {
        WeComProperties p = new WeComProperties();
        p.setApiBaseUrl(base()); p.setCorpId("fixture-corp"); p.setAppSecret("fixture-secret"); p.setTimeout(Duration.ofSeconds(2));
        SettingsConnectionProbe probe = new SettingsConnectionProbe(json);
        assertThat(probe.check("wecom", p, "CANDIDATE").status()).isEqualTo("NOT_CONFIGURED");
        assertThat(requests).isEmpty();

        p.setEnabled(true);
        assertThat(probe.check("wecom", p, "CANDIDATE").status()).isEqualTo("OK");
        assertThat(requests).containsExactly("GET /cgi-bin/gettoken");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"status\":\"ok\",\"authorized\":true,\"stateWritable\":true}|OK",
            "{\"status\":\"ok\",\"authorized\":false,\"stateWritable\":true}|UNAUTHORIZED",
            "{\"status\":\"failed\",\"authorized\":true,\"stateWritable\":true}|FAILED",
            "{\"status\":\"ok\",\"authorized\":true,\"stateWritable\":false}|FAILED",
            "{\"status\":\"ok\"}|UNSUPPORTED",
            "{\"status\":\"ok\",\"authorized\":\"true\",\"stateWritable\":true}|UNSUPPORTED",
            "{}|UNSUPPORTED"
    })
    void checksTelegramHealthAndSessionSeparately(String body, String expectedStatus) {
        respond("/health", 200, body);
        var p = new com.medianexus.orchestrator.config.TelegramWorkerProperties();
        p.setBaseUrl(base()); p.setTimeout(Duration.ofSeconds(2));

        var result = new SettingsConnectionProbe(json).check("telegram-worker", p, "CANDIDATE");

        assertThat(result.status()).isEqualTo(expectedStatus);
        assertThat(result.resources()).isEmpty();
        assertThat(requests).containsExactly("GET /health");
    }

    private String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    @Test
    void readsAutoSymlinkTaskFormWithoutReturningSecrets() throws Exception {
        String uuid = "12345678-1234-1234-1234-123456789abc";
        respond("/", 200, "<a href='/sync_list/" + uuid + "'>任务</a><a href='/add_sync'>新增</a>");
        respond("/sync_list/" + uuid, 200, """
                <form>
                <input name='task_name' value='电影 &amp; 新盘'>
                <input name='media_dir' value='/挂载/新 盘/Movie'>
                <input name='symlink_dir' value='/STRM/新 盘/Movie'>
                <input type='checkbox' name='symlink_creator' checked>
                <input type='checkbox' name='metadata_copyer'>
                <input name='num_threads' value='4'>
                <input type='checkbox' name='symlink_dir_checker'>
                <input type='checkbox' name='symlink_checker'>
                <input type='checkbox' name='metadata_checker'>
                <input name='symlink_size' value='0'>
                <textarea name='symlink_ext'>mkv,mp4</textarea>
                <textarea name='metadata_ext'>nfo,jpg</textarea>
                <input name='cloud_url' value='https://fixture.invalid/?token=as-cloud-secret'>
                <input name='username' value='as-private-user'>
                </form>
                """);

        var result = new SettingsConnectionProbe(json).check("autosymlink", autoSymlink(), "CANDIDATE");

        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.resources()).hasSize(1);
        assertThat(result.resources().get(0).id()).isEqualTo(uuid);
        assertThat(result.resources().get(0).name()).isEqualTo("电影 & 新盘");
        assertThat(result.resources().get(0).attributes()).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                "uuid", uuid, "taskName", "电影 & 新盘", "source", "/挂载/新 盘/Movie", "output", "/STRM/新 盘/Movie"));
        assertThat(json.writeValueAsString(result)).doesNotContain("as-cookie-secret", "as-cloud-secret", "as-private-user", "cloud_url", "username");
        assertThat(requests).containsExactly("GET /", "GET /sync_list/" + uuid);
    }

    @ParameterizedTest
    @CsvSource({"/,401,UNAUTHORIZED", "/,403,UNAUTHORIZED", "/,404,UNSUPPORTED", "/,405,UNSUPPORTED", "/,500,FAILED",
            "/sync_list/12345678-1234-1234-1234-123456789abc,401,UNAUTHORIZED",
            "/sync_list/12345678-1234-1234-1234-123456789abc,500,FAILED"})
    void distinguishesAutoSymlinkHttpFailures(String path, int status, String expected) throws Exception {
        respond("/", 200, "<a href='/sync_list/12345678-1234-1234-1234-123456789abc'>任务</a>");
        respond(path, status, "as-private-upstream-body as-cookie-secret");

        var result = new SettingsConnectionProbe(json).check("autosymlink", autoSymlink(), "EFFECTIVE");

        assertThat(result.status()).isEqualTo(expected);
        assertThat(result.source()).isEqualTo("EFFECTIVE");
        assertThat(result.resources()).isEmpty();
        assertThat(json.writeValueAsString(result)).doesNotContain("as-private-upstream-body", "as-cookie-secret");
        assertThat(requests).allMatch(request -> request.startsWith("GET "));
    }

    @Test
    void rejectsIncompatibleAutoSymlinkListWithoutReportingEmptySuccess() {
        respond("/", 200, "<html><form action='/login'><input name='password' type='password'></form></html>");

        var result = new SettingsConnectionProbe(json).check("autosymlink", autoSymlink(), "CANDIDATE");

        assertThat(result.status()).isEqualTo("UNSUPPORTED");
        assertThat(result.resources()).isEmpty();
        assertThat(requests).containsExactly("GET /");
    }

    @ParameterizedTest
    @ValueSource(strings = {"<html>incompatible-version</html>",
            "<form><input name='task_name' value='电影'><input name='media_dir' value='/Movie'><input name='symlink_dir' value='/STRM/Movie'></form>"})
    void rejectsIncompatibleAutoSymlinkTaskForms(String html) {
        String uuid = "12345678-1234-1234-1234-123456789abc";
        respond("/", 200, "<a href='/sync_list/" + uuid + "'>任务</a>");
        respond("/sync_list/" + uuid, 200, html);

        var result = new SettingsConnectionProbe(json).check("autosymlink", autoSymlink(), "CANDIDATE");

        assertThat(result.status()).isEqualTo("UNSUPPORTED");
        assertThat(result.resources()).isEmpty();
        assertThat(requests).containsExactly("GET /", "GET /sync_list/" + uuid);
    }

    @Test
    void readsCloudDriveResourcesPermissionsAndPathsWithoutReturningCredentials() throws Exception {
        CloudDrive2Properties connection = cloudDrive("", Status.OK);

        var result = new SettingsConnectionProbe(json).check("clouddrive2", connection, "CANDIDATE");

        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.resources()).hasSize(3);
        assertThat(result.resources().get(0).name()).isEqualTo("WebDAV");
        assertThat(result.resources().get(0).attributes()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "type", "cloud", "path", "/新 盘", "locked", false, "pathStatus", "OK"));
        assertThat(result.resources().get(1).attributes()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "type", "mount", "mountPoint", "/挂载/新 盘", "sourceDir", "/新 盘", "mounted", true, "readOnly", true, "pathStatus", "OK"));
        assertThat(result.resources().get(2).attributes()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "canAddCloud", true, "canAddMount", false));
        assertThat(json.writeValueAsString(result)).doesNotContain("cd2-token-secret", "cd2-private-user", "private-upstream-error", "userName", "nickName", "permissions");
        assertThat(requests).containsExactly("gRPC GetAllCloudApis", "gRPC GetMountPoints", "gRPC CanAddMoreCloudApis",
                "gRPC CanAddMoreMountPoints", "gRPC GetSubFiles", "path /新 盘");
    }

    @ParameterizedTest
    @CsvSource({"UNAUTHENTICATED,UNAUTHORIZED", "PERMISSION_DENIED,UNAUTHORIZED", "UNIMPLEMENTED,UNSUPPORTED",
            "UNAVAILABLE,FAILED", "DEADLINE_EXCEEDED,FAILED"})
    void distinguishesCloudDriveGrpcFailuresWithoutReturningDetails(String grpcStatus, String expected) throws Exception {
        CloudDrive2Properties connection = cloudDrive("GetAllCloudApis", Status.fromCode(Status.Code.valueOf(grpcStatus)));

        var result = new SettingsConnectionProbe(json).check("clouddrive2", connection, "EFFECTIVE");

        assertThat(result.status()).isEqualTo(expected);
        assertThat(result.source()).isEqualTo("EFFECTIVE");
        assertThat(result.resources()).isEmpty();
        assertThat(json.writeValueAsString(result)).doesNotContain("cd2-token-secret", "private-upstream-error", "cd2-private-user");
        assertThat(requests).containsExactly("gRPC GetAllCloudApis");
    }

    @ParameterizedTest
    @ValueSource(strings = {"GetMountPoints", "CanAddMoreCloudApis", "CanAddMoreMountPoints", "GetSubFiles"})
    void refusesCloudDrivePartialResourceOrDirectoryReadSuccess(String failingRpc) throws Exception {
        CloudDrive2Properties connection = cloudDrive(failingRpc, Status.INTERNAL);

        var result = new SettingsConnectionProbe(json).check("clouddrive2", connection, "CANDIDATE");

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.resources()).isEmpty();
        assertThat(requests).contains("gRPC " + failingRpc);
        assertThat(json.writeValueAsString(result)).doesNotContain("private-upstream-error", "cd2-token-secret");
    }

    @ParameterizedTest
    @ValueSource(strings = {"host", "token"})
    void refusesUnconfiguredCloudDriveWithoutCallingUpstream(String missing) throws Exception {
        CloudDrive2Properties connection = cloudDrive("", Status.OK);
        if (missing.equals("host")) connection.setHost(" ");
        else connection.setApiToken(" ");

        var result = new SettingsConnectionProbe(json).check("clouddrive2", connection, "CANDIDATE");

        assertThat(result.status()).isEqualTo("NOT_CONFIGURED");
        assertThat(result.resources()).isEmpty();
        assertThat(requests).isEmpty();
    }

    private CloudDrive2Properties cloudDrive(String failingRpc, Status failure) throws Exception {
        grpcServer = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
                .intercept(new ServerInterceptor() {
                    @Override public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                        String method = call.getMethodDescriptor().getBareMethodName();
                        requests.add("gRPC " + method);
                        assertThat(headers.get(Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER)))
                                .isEqualTo("Bearer cd2-token-secret");
                        if (!List.of("GetAllCloudApis", "GetMountPoints", "CanAddMoreCloudApis", "CanAddMoreMountPoints", "GetSubFiles").contains(method)) {
                            call.close(Status.PERMISSION_DENIED, new Metadata());
                            return new ServerCall.Listener<>() {};
                        }
                        if (method.equals(failingRpc) && !method.equals("GetSubFiles")) {
                            call.close(failure.withDescription("private-upstream-error cd2-token-secret cd2-private-user"), new Metadata());
                            return new ServerCall.Listener<>() {};
                        }
                        return next.startCall(call, headers);
                    }
                })
                .addService(new CloudDriveFileSrvGrpc.CloudDriveFileSrvImplBase() {
                    @Override public void getAllCloudApis(Empty request, StreamObserver<CloudAPIList> observer) {
                        observer.onNext(CloudAPIList.newBuilder().addApis(CloudAPI.newBuilder().setName("WebDAV")
                                .setPath("/新 盘").setUserName("cd2-private-user").setNickName("private-nickname")).build());
                        observer.onCompleted();
                    }
                    @Override public void getMountPoints(Empty request, StreamObserver<GetMountPointsResult> observer) {
                        observer.onNext(GetMountPointsResult.newBuilder().addMountPoints(MountPoint.newBuilder()
                                .setMountPoint("/挂载/新 盘").setSourceDir("/新 盘").setIsMounted(true).setReadOnly(true)
                                .setUid(1000).setPermissions("private-permissions").setFailReason("private-upstream-error")).build());
                        observer.onCompleted();
                    }
                    @Override public void canAddMoreCloudApis(Empty request, StreamObserver<FileOperationResult> observer) {
                        observer.onNext(FileOperationResult.newBuilder().setSuccess(true).build()); observer.onCompleted();
                    }
                    @Override public void canAddMoreMountPoints(Empty request, StreamObserver<FileOperationResult> observer) {
                        observer.onNext(FileOperationResult.newBuilder().setSuccess(false).setErrorMessage("private-upstream-error").build()); observer.onCompleted();
                    }
                    @Override public void getSubFiles(ListSubFileRequest request, StreamObserver<SubFilesReply> observer) {
                        requests.add("path " + request.getPath());
                        observer.onNext(SubFilesReply.getDefaultInstance());
                        if (failingRpc.equals("GetSubFiles")) observer.onError(failure.withDescription("private-upstream-error").asRuntimeException());
                        else observer.onCompleted();
                    }
                }).build().start();
        CloudDrive2Properties connection = new CloudDrive2Properties();
        connection.setHost("127.0.0.1"); connection.setPort(grpcServer.getPort());
        connection.setApiToken("cd2-token-secret"); connection.setOperationTimeout(Duration.ofSeconds(2));
        return connection;
    }

    private AutoSymlinkProperties autoSymlink() {
        AutoSymlinkProperties connection = new AutoSymlinkProperties();
        connection.setBaseUrl(base()); connection.setCookie("session=as-cookie-secret"); connection.setTimeout(Duration.ofSeconds(2));
        return connection;
    }

    private void respond(String path, int status, String body) {
        if (path.equals("/")) server.removeContext(path);
        server.createContext(path, exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }
}
