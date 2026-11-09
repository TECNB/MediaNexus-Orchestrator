package com.medianexus.orchestrator.integration.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.Empty;
import com.medianexus.orchestrator.config.AutoSymlinkProperties;
import com.medianexus.orchestrator.config.CloudDrive2Properties;
import com.medianexus.orchestrator.config.EmbyProperties;
import com.medianexus.orchestrator.integration.clouddrive.proto.*;
import com.sun.net.httpserver.HttpServer;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class OnboardingClientsTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void disabledAsRejectsReadsAndWritesBeforeNetwork() {
        AutoSymlinkProperties p = new AutoSymlinkProperties();
        p.setDisabled(true); p.setBaseUrl("http://127.0.0.1:1"); p.setCookie("retained-private-cookie");
        p.setTimeout(Duration.ofSeconds(1));
        AutoSymlinkAccountClient client = new AutoSymlinkAccountClient(json);
        assertThatThrownBy(() -> client.readTasks(p)).hasMessageContaining("停用");
        assertThatThrownBy(() -> client.createAndReadUuid(p, json.createObjectNode())).hasMessageContaining("停用");
        assertThat(p.getCookie()).isEqualTo("retained-private-cookie");
    }

    @Test
    void missingOptionalIntegrationCredentialsFailAtOperationBoundary() {
        assertThatThrownBy(() -> new AutoSymlinkAccountClient(json).readTasks(new AutoSymlinkProperties()))
                .hasMessageContaining("未配置");
        assertThatThrownBy(() -> new EmbyAccountLibraryClient(json).readLibraries(new EmbyProperties()))
                .hasMessageContaining("未配置");
        assertThatThrownBy(() -> new OpenListAccountClient(json).readStorages(new com.medianexus.orchestrator.config.OpenListProperties()))
                .hasMessageContaining("未配置");
    }

    @Test
    void openListFailedInitializationRetainsRealStorageIdAndDoesNotCreateAgain() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicBoolean created = new AtomicBoolean();
        server.createContext("/api/admin/storage/list", exchange -> {
            String row = created.get() ? """
                    {"id":42,"mount_path":"/new","driver":"PikPak","remark":"medianexus-target:target-1",
                     "status":"authorization-error-private-value","disabled":false,"addition":"private-value"}
                    """ : "";
            byte[] body = ("{\"code\":200,\"data\":{\"total\":" + (created.get() ? 1 : 0)
                    + ",\"content\":[" + row + "]}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/api/admin/storage/create", exchange -> {
            assertThat(created.getAndSet(true)).isFalse();
            byte[] body = "{\"code\":500,\"message\":\"private-value\",\"data\":{\"id\":42}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var p = new com.medianexus.orchestrator.config.OpenListProperties();
            p.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort()); p.setAuthorization("test-auth"); p.setTimeout(Duration.ofSeconds(3));
            var client = new OpenListAccountClient(json);
            var storage = json.createObjectNode().put("mount_path", "/new").put("addition", "{\"refresh_token\":\"private-value\"}");
            var result = client.createAndRead(p, "target-1", storage);
            assertThat(result.id()).isEqualTo(42); assertThat(result.healthy()).isFalse();
            assertThat(json.writeValueAsString(result)).doesNotContain("private-value", "addition");
            assertThat(client.createAndRead(p, "target-1", storage).id()).isEqualTo(42);
        } finally { server.stop(0); }
    }

    @Test
    void openListLostCreateResponseReadsBackStorageWithoutReplayingPost() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/api/admin/storage/list", exchange -> {
            String row = writes.get() == 0 ? "" : """
                    {"id":73,"mount_path":"/new","driver":"PikPak","remark":"medianexus-target:new-id",
                     "disabled":false,"status":"work","mount_details":{"total_space":100,"used_space":20,"free_space":80}}
                    """;
            byte[] body = ("{\"code\":200,\"data\":{\"total\":" + (writes.get() == 0 ? 0 : 1) + ",\"content\":[" + row + "]}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/api/admin/storage/create", exchange -> {
            writes.incrementAndGet();
            byte[] body = "private-html-callback?token=secret".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(502, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var p = new com.medianexus.orchestrator.config.OpenListProperties();
            p.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort()); p.setAuthorization("auth"); p.setTimeout(Duration.ofSeconds(2));
            var client = new OpenListAccountClient(json);
            var storage = json.createObjectNode().put("mount_path", "/new").put("addition", "{}");
            assertThat(client.createAndRead(p, "new-id", storage).id()).isEqualTo(73);
            assertThat(client.createAndRead(p, "new-id", storage).freeBytes()).isEqualTo(80);
            assertThat(writes.get()).isEqualTo(1);
        } finally { server.stop(0); }
    }

    @Test
    void readsRealGrpcResourceMethodsWithoutExposingCloudCredentials() throws Exception {
        Server server = ServerBuilder.forPort(0).addService(new CloudDriveFileSrvGrpc.CloudDriveFileSrvImplBase() {
            @Override public void getAllCloudApis(Empty request, StreamObserver<CloudAPIList> observer) {
                observer.onNext(CloudAPIList.newBuilder().addApis(CloudAPI.newBuilder().setName("WebDAV")
                        .setUserName("private-dav-account").setPath("/CloudA")).build()); observer.onCompleted();
            }
            @Override public void getMountPoints(Empty request, StreamObserver<GetMountPointsResult> observer) {
                observer.onNext(GetMountPointsResult.newBuilder().addMountPoints(MountPoint.newBuilder()
                        .setMountPoint("/mount/A").setSourceDir("/CloudA").setIsMounted(true)
                        .setUid(1000).setGid(1001).setPermissions("0755").setAutoMount(true)).build()); observer.onCompleted();
            }
            @Override public void canAddMoreCloudApis(Empty request, StreamObserver<FileOperationResult> observer) {
                observer.onNext(FileOperationResult.newBuilder().setSuccess(true).build()); observer.onCompleted();
            }
            @Override public void canAddMoreMountPoints(Empty request, StreamObserver<FileOperationResult> observer) {
                observer.onNext(FileOperationResult.newBuilder().setSuccess(false).setErrorMessage("private-error").build()); observer.onCompleted();
            }
        }).build().start();
        try {
            CloudDrive2Properties connection = new CloudDrive2Properties();
            connection.setHost("127.0.0.1"); connection.setPort(server.getPort()); connection.setApiToken("test-token");
            var resources = new CloudDriveAccountClient().readResources(connection);
            assertThat(resources.canAddCloud()).isTrue(); assertThat(resources.canAddMount()).isFalse();
            assertThat(resources.clouds().get(0).path()).isEqualTo("/CloudA");
            assertThat(resources.mounts().get(0).mounted()).isTrue();
            MountOption template = new CloudDriveAccountClient().readMountTemplate(connection, "/mount/A");
            assertThat(template.getUid()).isEqualTo(1000); assertThat(template.getGid()).isEqualTo(1001);
            assertThat(template.getPermissions()).isEqualTo("0755"); assertThat(template.getAutoMount()).isTrue();
            assertThat(json.writeValueAsString(resources)).doesNotContain("private", "test-token", "userName");
        } finally { server.shutdownNow(); }
    }

    @Test
    void appliesDownloadProfileOnlyToTheNamedCloudAndReadsItBack() throws Exception {
        var saved = new java.util.concurrent.atomic.AtomicReference<>(CloudAPIConfig.newBuilder().setMaxDownloadThreads(2).build());
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        Server server = ServerBuilder.forPort(0).addService(new CloudDriveFileSrvGrpc.CloudDriveFileSrvImplBase() {
            @Override public void getAllCloudApis(Empty request, StreamObserver<CloudAPIList> observer) {
                observer.onNext(CloudAPIList.newBuilder()
                        .addApis(CloudAPI.newBuilder().setName("WebDAV").setUserName("old-user").setPath("/old"))
                        .addApis(CloudAPI.newBuilder().setName("WebDAV").setUserName("new-user").setPath("/new")).build());
                observer.onCompleted();
            }
            @Override public void getCloudAPIConfig(GetCloudAPIConfigRequest request, StreamObserver<CloudAPIConfig> observer) {
                observer.onNext(saved.get()); observer.onCompleted();
            }
            @Override public void setCloudAPIConfig(SetCloudAPIConfigRequest request, StreamObserver<Empty> observer) {
                if (!request.getCloudName().equals("WebDAV") || !request.getUserName().equals("new-user")) {
                    observer.onError(io.grpc.Status.PERMISSION_DENIED.asRuntimeException()); return;
                }
                writes.incrementAndGet(); saved.set(request.getConfig());
                observer.onNext(Empty.getDefaultInstance()); observer.onCompleted();
            }
        }).build().start();
        try {
            var connection = new CloudDrive2Properties();
            connection.setHost("127.0.0.1"); connection.setPort(server.getPort()); connection.setApiToken("test-token");
            var client = new CloudDriveAccountClient();
            client.applyNewAccountDownloadProfile(connection, "/new");
            assertThat(saved.get()).isEqualTo(CloudAPIConfig.newBuilder().setMaxDownloadThreads(4)
                    .setMinReadLengthKB(64).setDefaultReadLengthKB(2048).setMaxBufferPoolSizeMB(128).build());
            client.applyNewAccountDownloadProfile(connection, "/new");
            assertThat(writes.get()).isEqualTo(1);
        } finally { server.shutdownNow(); }
    }

    @Test
    void grpcFailuresExposeStableSafeCategories() throws Exception {
        io.grpc.Status[] failures = {io.grpc.Status.UNAUTHENTICATED, io.grpc.Status.PERMISSION_DENIED, io.grpc.Status.UNIMPLEMENTED};
        int[] codes = {401, 403, 503};
        for (int i = 0; i < failures.length; i++) {
            io.grpc.Status failure = failures[i]; int expected = codes[i];
            Server server = ServerBuilder.forPort(0).addService(new CloudDriveFileSrvGrpc.CloudDriveFileSrvImplBase() {
                @Override public void getAllCloudApis(Empty request, StreamObserver<CloudAPIList> observer) {
                    observer.onError(failure.withDescription("private-token-callback").asRuntimeException());
                }
            }).build().start();
            try {
                var p = new CloudDrive2Properties(); p.setHost("127.0.0.1"); p.setPort(server.getPort()); p.setApiToken("test-token");
                assertThatThrownBy(() -> new CloudDriveAccountClient().readResources(p))
                        .isInstanceOfSatisfying(com.medianexus.orchestrator.common.exception.BusinessException.class,
                                exception -> {
                                    assertThat(exception.getCode()).isEqualTo(expected);
                                    assertThat(exception.getHttpStatus()).isEqualTo(expected==503
                                            ? org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE : org.springframework.http.HttpStatus.BAD_GATEWAY);
                                })
                        .hasMessageContaining("CD2_" + failure.getCode().name())
                        .hasMessageNotContaining("private-token-callback").hasNoCause();
            } finally { server.shutdownNow(); }
        }
    }

    @Test
    void upstreamAuthenticationKeepsInternalCodeButReturnsBadGateway() throws Exception {
        for(int status:new int[]{401,403}){
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange -> {
                byte[] body="private-token-callback".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status,body.length);
                exchange.getResponseBody().write(body);exchange.close();
            });
            server.start();
            try{
                var client=new OnboardingHttp("http://127.0.0.1:"+server.getAddress().getPort(),java.util.Map.of(),Duration.ofSeconds(2),json);
                assertThatThrownBy(() -> client.read("/"))
                        .isInstanceOfSatisfying(com.medianexus.orchestrator.common.exception.BusinessException.class, failure -> {
                            assertThat(failure.getCode()).isEqualTo(status);
                            assertThat(failure.getHttpStatus()).isEqualTo(org.springframework.http.HttpStatus.BAD_GATEWAY);
                        }).hasMessageNotContaining("private-token-callback").hasNoCause();
            }finally{server.stop(0);}
        }
    }

    @Test
    void asLostCreateResponseReadsActualUuidAndCompleteManualForm() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String uuid = "27a18cde-53b5-4ddc-8145-2d33da0047a5";
        AtomicBoolean created = new AtomicBoolean();
        ObjectNode form = json.createObjectNode().put("task_name", "new-target-MOVIE").put("media_dir", "/mount/新 盘/Movie")
                .put("symlink_dir", "/strm/new/Movie").put("num_threads", "4").put("symlink_size", "0")
                .put("symlink_ext", ".strm").put("metadata_ext", ".nfo");
        for (String key : new String[]{"symlink_creator", "metadata_copyer", "symlink_dir_checker", "symlink_checker", "metadata_checker"}) { form.put(key, true); }
        for (String key : AutoSymlinkAccountClient.CONFIG_STRINGS) { if (!form.has(key)) { form.put(key, ""); } }
        for (String key : AutoSymlinkAccountClient.CONFIG_BOOLEANS) { if (!form.has(key)) { form.put(key, false); } }
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath(); String body;
            if (path.equals("/openapi.json")) {
                ObjectNode schema = json.createObjectNode();
                var required = schema.putObject("components").putObject("schemas").putObject("SyncConfigFormData").putArray("required");
                AutoSymlinkAccountClient.CONFIG_STRINGS.forEach(required::add); AutoSymlinkAccountClient.CONFIG_BOOLEANS.forEach(required::add);
                body = schema.toString();
            } else if (path.equals("/add_sync") && exchange.getRequestMethod().equals("POST")) {
                assertThat(json.readTree(exchange.getRequestBody())).isEqualTo(form.deepCopy().put("isNew", true));
                assertThat(created.get()).isFalse();
                created.set(true); body = "{\"save_status\":true}";
                byte[] rejected = "private-callback-token".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(502, rejected.length); exchange.getResponseBody().write(rejected); exchange.close();
                return;
            } else if (path.equals("/sync_list/" + uuid)) {
                StringBuilder html = new StringBuilder("<form>");
                form.fields().forEachRemaining(field -> {
                    if (field.getValue().isBoolean()) { html.append("<input type='checkbox' ").append(field.getValue().asBoolean() ? "checked " : "")
                            .append("name='").append(field.getKey()).append("'>"); }
                    else { html.append("<input name='").append(field.getKey()).append("' value='").append(field.getValue().asText()).append("'>"); }
                }); body = html.append("</form>").toString();
            } else { body = "<a href='/add_sync'>new</a>" + (created.get() ? "<a href='/sync_list/" + uuid + "'>task</a>" : ""); }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            AutoSymlinkProperties connection = new AutoSymlinkProperties(); connection.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            connection.setCookie("test-session"); connection.setTimeout(Duration.ofSeconds(3));
            AutoSymlinkAccountClient client = new AutoSymlinkAccountClient(json);
            assertThat(client.createAndReadUuid(connection, form)).isEqualTo(uuid);
            assertThat(client.createAndReadUuid(connection, form)).isEqualTo(uuid);
            assertThat(client.readTasks(connection).get(0).mediaDir()).isEqualTo("/mount/新 盘/Movie");
            assertThat(client.manualRequest(client.readConfiguration(connection, uuid)).size()).isEqualTo(11);
        } finally { server.stop(0); }
    }

    @Test
    void asDirectoryPickerRequiresRealChildPathAndReadableDirectoryResponse() throws Exception {
        var parentBody = new java.util.concurrent.atomic.AtomicReference<String>(
                "{\"folder_name\":\"\",\"folder_path\":\"\",\"children\":[{\"folder_name\":\"新 盘\",\"folder_path\":\"/mounted/新 盘\",\"children\":[]}]}");
        var ownBody = new java.util.concurrent.atomic.AtomicReference<String>(
                "{\"folder_name\":\"\",\"folder_path\":\"\",\"children\":[]}");
        var requests = new java.util.concurrent.CopyOnWriteArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/get_sub_dir", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Cookie")).isEqualTo("session=test-only");
            String folder = json.readTree(exchange.getRequestBody()).path("folder_path").asText();
            requests.add(folder);
            assertThat(folder).isIn("/mounted", "/mounted/新 盘");
            byte[] response = (folder.equals("/mounted") ? parentBody.get() : ownBody.get()).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try {
            AutoSymlinkProperties p = new AutoSymlinkProperties();
            p.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            p.setCookie("session=test-only"); p.setTimeout(Duration.ofSeconds(2));
            var client = new AutoSymlinkAccountClient(json);
            client.verifyReadableDirectory(p, "/mounted/新 盘");
            assertThat(requests).containsExactly("/mounted", "/mounted/新 盘");
            parentBody.set("{\"folder_name\":\"\",\"folder_path\":\"\",\"children\":[]}");
            assertThatThrownBy(() -> client.verifyReadableDirectory(p, "/mounted/新 盘")).hasMessageContaining("未读回计划目录");
            parentBody.set("{\"children\":[{\"folder_path\":\"/mounted/新 盘\"}]}");
            ownBody.set("{\"unexpected\":[]}");
            assertThatThrownBy(() -> client.verifyReadableDirectory(p, "/mounted/新 盘")).hasMessageContaining("协议尚未核实");
        } finally { server.stop(0); }
    }

    @Test
    void newAsConfigurationChangesWholePathMappingAndPreservesGenericScanOptions() {
        ObjectNode source = json.createObjectNode();
        AutoSymlinkAccountClient.CONFIG_STRINGS.forEach(key -> source.put(key, ""));
        AutoSymlinkAccountClient.CONFIG_BOOLEANS.forEach(key -> source.put(key, false));
        source.put("task_name", "old").put("media_dir", "/old/Movie").put("symlink_dir", "/strm/old")
                .put("cloud_type", "cd2").put("symlink_mode", "strm").put("strm_mode", "local")
                .put("clouddrive2_path", "/old").put("cloud_url", "ip:19798")
                .put("num_threads", "8").put("observer_time", "10").put("symlink_size", "0");
        var client = new AutoSymlinkAccountClient(json);
        ObjectNode prepared = client.configurationForNewTarget(source,
                new AutoSymlinkAccountClient.TaskPaths("next", "/next/中文 空格/Movie", "/strm/next", "/next"));
        assertThat(prepared.path("clouddrive2_path").asText()).isEqualTo("/next");
        assertThat(prepared.path("media_dir").asText()).isEqualTo("/next/中文 空格/Movie");
        assertThat(prepared.path("num_threads").asText()).isEqualTo("8");
        assertThat(prepared.path("cloud_url").asText()).isEmpty();
        assertThat(source.path("cloud_url").asText()).isEqualTo("ip:19798");
        assertThat(source.path("media_dir").asText()).isEqualTo("/old/Movie");
        source.put("cloud_checker", true).put("sign_file", "/old/probe");
        assertThatThrownBy(() -> client.configurationForNewTarget(source,
                new AutoSymlinkAccountClient.TaskPaths("next", "/next/Movie", "/strm/next", "/next")))
                .hasMessageContaining("探针");
    }

    @Test
    void embyPathValidationUsesQueryPathAndNeverTestsWritingWithoutOpenApi() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/Environment/ValidatePath", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-Emby-Token")).isEqualTo("test-api-key");
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            String query = exchange.getRequestURI().getRawQuery();
            assertThat(query).startsWith("Path=");
            String path = java.net.URLDecoder.decode(query.substring(5), StandardCharsets.UTF_8);
            var request = json.readTree(exchange.getRequestBody());
            assertThat(request.size()).isEqualTo(2);
            assertThat(request.path("ValidateWriteable").booleanValue()).isFalse();
            assertThat(request.path("IsFile").booleanValue()).isFalse();
            calls.incrementAndGet();
            exchange.sendResponseHeaders(path.equals("/strm/新 盘") ? 200 : 404, -1); exchange.close();
        });
        server.start();
        try {
            var p = new EmbyProperties(); p.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            p.setApiKey("test-api-key"); p.setTimeout(Duration.ofSeconds(2));
            var client = new EmbyAccountLibraryClient(json);
            client.verifyReadableDirectory(p, "/strm/新 盘");
            assertThatThrownBy(() -> client.verifyReadableDirectory(p, "/missing")).hasMessageContaining("HTTP 404");
            assertThat(calls.get()).isEqualTo(2);
        } finally { server.stop(0); }
    }

    @Test
    void appendsEmbyPathWithoutResendingOptionsAndPreservesExistingPathAttributes() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ObjectNode library = (ObjectNode) json.readTree("""
                {"ItemId":"library-1","Name":"Movies","Locations":["/strm/old"],
                 "LibraryOptions":{"EnableRealtimeMonitor":true,"PathInfos":[{"Path":"/strm/old","NetworkPath":"network"}]}}
                """);
        server.createContext("/Library/VirtualFolders/Query", exchange -> {
            ObjectNode response = json.createObjectNode().put("TotalRecordCount", 1); response.putArray("Items").add(library);
            byte[] body = response.toString().getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/Library/VirtualFolders/Paths", exchange -> {
            var request = json.readTree(exchange.getRequestBody());
            assertThat(request.size()).isEqualTo(3); assertThat(request.has("LibraryOptions")).isFalse();
            assertThat(request.path("RefreshLibrary").asBoolean()).isFalse();
            library.withArray("Locations").add(request.path("Path").asText());
            ((ObjectNode) library.get("LibraryOptions")).withArray("PathInfos").addObject().put("Path", request.path("Path").asText());
            exchange.sendResponseHeaders(200, -1); exchange.close();
        });
        server.start();
        try {
            EmbyProperties connection = new EmbyProperties(); connection.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            connection.setApiKey("test-api-key"); connection.setTimeout(Duration.ofSeconds(3));
            EmbyAccountLibraryClient client = new EmbyAccountLibraryClient(json);
            assertThat(client.appendAndVerify(connection, "library-1", "/strm/new").locations())
                    .containsExactly("/strm/old", "/strm/new");
            assertThat(client.appendAndVerify(connection, "library-1", "/strm/new").locations()).hasSize(2);
            assertThat(library.path("LibraryOptions").path("EnableRealtimeMonitor").asBoolean()).isTrue();
        } finally { server.stop(0); }
    }
}
