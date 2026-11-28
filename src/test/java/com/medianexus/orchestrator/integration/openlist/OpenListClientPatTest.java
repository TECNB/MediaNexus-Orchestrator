package com.medianexus.orchestrator.integration.openlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.OpenListProperties;
import com.medianexus.orchestrator.config.PikPakPatProperties;
import com.medianexus.orchestrator.integration.pikpak.PikPakPatClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenListClientPatTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<String, List<Node>> folders = new ConcurrentHashMap<>();
    private final List<JsonNode> offlineBodies = new CopyOnWriteArrayList<>();
    private final List<String> offlineAuths = new CopyOnWriteArrayList<>();
    private final List<String> createdNames = new CopyOnWriteArrayList<>();
    private final List<String> deleteQueries = new CopyOnWriteArrayList<>();
    private final List<String> fileQueries = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, String> phases = new ConcurrentHashMap<>();
    private final AtomicInteger fileLists = new AtomicInteger();
    private final AtomicInteger folderCreates = new AtomicInteger();
    private final AtomicInteger openListSubmits = new AtomicInteger();
    private final AtomicInteger storageLists = new AtomicInteger();
    private final AtomicInteger deletes = new AtomicInteger();
    private volatile int taskListStatus = 200;
    private volatile boolean transferFails;
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        folders.put("", new CopyOnWriteArrayList<>(List.of(new Node("id-media", "Media", "drive#folder"))));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void keepsOpenListWhenTokenOrPrefixDoesNotMatch() {
        OpenListClient client = client("", "");

        assertThat(client.addOfflineDownload("/pikpak/Media/Adult", "magnet:?xt=openlist")).isEqualTo("ol-task");
        assertThat(openListSubmits).hasValue(1);
        assertThat(fileLists).hasValue(0);

        OpenListClient configured = client("media-b-token", "adult-token");
        assertThat(configured.addOfflineDownload("/pikpak-extra/Media", "magnet:?xt=openlist")).isEqualTo("ol-task");
        assertThat(configured.addOfflineDownload("/quark/Movies", "magnet:?xt=quark")).isEqualTo("ol-task");
        assertThat(fileLists).hasValue(0);
        assertThat(openListSubmits).hasValue(3);
    }

    @Test
    void routesLongestPrefixAndCreatesMissingFolders() {
        OpenListClient client = client("media-b-token", "adult-token");
        String mediaB = "/pikpak-282b8752-5946-422a-8e52-d17a2be03fff";

        String first = client.addOfflineDownload("/pikpak/Media/Adult/JAV", "ed2k://wrapped");
        String second = client.addOfflineDownload("/pikpak/Media/Adult/JAV", "ed2k://wrapped");
        String raw = client.addOfflineDownload("/pikpak/Media", "ed2k://raw");
        client.addOfflineDownload("/pikpak/media", "ed2k://case");
        String movie = client.addOfflineDownload(mediaB + "/Media/Movie", "magnet:?xt=movie");

        assertThat(first).isEqualTo("pat:adult:wrapped-1");
        assertThat(second).isEqualTo(first);
        assertThat(raw).isEqualTo("pat:adult:raw-1");
        assertThat(movie).isEqualTo("pat:media-b:wrapped-1");
        assertThat(offlineBodies).hasSize(5);
        assertThat(offlineBodies.get(0).path("parent_id").asText()).isEqualTo("folder-2");
        assertThat(offlineBodies.get(1).path("parent_id").asText()).isEqualTo("folder-2");
        assertThat(offlineBodies.get(2).path("parent_id").asText()).isEqualTo("id-media");
        assertThat(offlineBodies.get(3).path("parent_id").asText()).isEqualTo("folder-3");
        assertThat(offlineBodies.get(4).path("parent_id").asText()).isEqualTo("folder-4");
        assertThat(offlineBodies.get(0).path("upload_type").asText()).isEqualTo("UPLOAD_TYPE_URL");
        assertThat(offlineBodies.get(0).path("url").path("url").asText()).isEqualTo("ed2k://wrapped");
        assertThat(offlineBodies.get(4).path("url").path("url").asText()).isEqualTo("magnet:?xt=movie");
        assertThat(offlineAuths.get(0)).isEqualTo("Bearer adult-token");
        assertThat(offlineAuths.get(4)).isEqualTo("Bearer media-b-token");
        assertThat(createdNames).containsExactly("Adult", "JAV", "media", "Movie");
        assertThat(fileLists).hasValue(6);
        assertThat(folderCreates).hasValue(4);
        assertThat(fileQueries.get(0)).contains("PHASE_TYPE_COMPLETE");
        assertThat(openListSubmits).hasValue(0);

        PikPakPatProperties conflict = properties("adult-token", "adult-token");
        conflict.setMediaBPrefix("/pikpak");
        conflict.setAdultPrefix("/pikpak");
        OpenListClient ambiguous = openListClient(conflict);
        assertThatThrownBy(() -> ambiguous.addOfflineDownload("/pikpak/Media", "magnet:?xt=1"))
                .isInstanceOf(OpenListClientException.class)
                .hasMessageContaining("前缀冲突");
    }

    @Test
    void mapsPhasesCancelsAndRefusesUnverifiedRetry() {
        OpenListClient client = client("media-b-token", "adult-token");
        phases.put("c", "PHASE_TYPE_COMPLETE");
        phases.put("r", "PHASE_TYPE_RUNNING");
        phases.put("p", "PHASE_TYPE_PENDING");
        phases.put("e", "PHASE_TYPE_ERROR");
        phases.put("w", "PHASE_TYPE_WEIRD");

        OpenListOfflineTaskInfo complete = client.offlineTaskInfo("pat:adult:c");
        OpenListOfflineTaskInfo running = client.offlineTaskInfo("pat:adult:r");
        OpenListOfflineTaskInfo pending = client.offlineTaskInfo("pat:adult:p");
        OpenListOfflineTaskInfo failed = client.offlineTaskInfo("pat:adult:e");
        OpenListOfflineTaskInfo weird = client.offlineTaskInfo("pat:adult:w");
        OpenListOfflineTaskInfo missing = client.offlineTaskInfo("pat:adult:gone");
        OpenListOfflineTaskInfo openList = client.offlineTaskInfo("ol-1");

        assertThat(complete).isEqualTo(new OpenListOfflineTaskInfo("pat:adult:c", 2, "PHASE_TYPE_COMPLETE", 100, 42L, ""));
        assertThat(running).isEqualTo(new OpenListOfflineTaskInfo("pat:adult:r", 1, "PHASE_TYPE_RUNNING", 40, 42L, ""));
        assertThat(pending).isEqualTo(new OpenListOfflineTaskInfo("pat:adult:p", 0, "PHASE_TYPE_PENDING", 0, 42L, ""));
        assertThat(failed).isEqualTo(new OpenListOfflineTaskInfo("pat:adult:e", 7, "PHASE_TYPE_ERROR", 3, 42L, "disk full"));
        assertThat(weird.state()).isEqualTo(1);
        assertThat(weird.progress()).isEqualTo(100);
        assertThat(missing).isEqualTo(new OpenListOfflineTaskInfo("pat:adult:gone", 4, "missing", 0, null, ""));
        assertThat(openList.state()).isEqualTo(2);
        assertThat(openList.id()).isEqualTo("ol-1");

        client.cancelOfflineTask("pat:adult:c");
        client.deleteOfflineTask("pat:adult:c");
        assertThat(deletes).hasValue(2);
        assertThat(deleteQueries).allSatisfy(query -> {
            assertThat(query).contains("task_ids=c");
            assertThat(query).contains("delete_files=false");
            assertThat(query).doesNotContain("pat%3A");
        });
        assertThatThrownBy(() -> client.retryOfflineTask("pat:adult:c"))
                .isInstanceOf(OpenListClientException.class)
                .hasMessageContaining("重试");
        assertThat(offlineBodies).isEmpty();
    }

    @Test
    void sumsUnfinishedTasksAndFailsClosed() {
        OpenListClient client = client("media-b-token", "adult-token");

        assertThat(client.unfinishedOfflineTaskCount()).isEqualTo(6);

        taskListStatus = 500;
        assertThatThrownBy(client::unfinishedOfflineTaskCount)
                .isInstanceOf(OpenListClientException.class)
                .hasMessageContaining("PAT");

        taskListStatus = 200;
        transferFails = true;
        assertThatThrownBy(client::unfinishedOfflineTaskCount)
                .isInstanceOf(OpenListClientException.class)
                .hasMessageContaining("OpenList");

        transferFails = false;
        OpenListStorageStatus pat = client.storageStatusForPath(
                "/pikpak-282b8752-5946-422a-8e52-d17a2be03fff/Media/Movie");
        assertThat(storageLists).hasValue(0);
        assertThat(pat.provider()).isEqualTo("PikPak");
        assertThat(pat.online()).isTrue();
        assertThat(pat.usedBytes()).isEqualTo(250L);
        assertThat(pat.totalBytes()).isEqualTo(1000L);
        assertThat(pat.freeBytes()).isEqualTo(750L);

        OpenListStorageStatus quark = client.storageStatusForPath("/quark/Movies");
        assertThat(storageLists).hasValue(1);
        assertThat(quark.usedBytes()).isEqualTo(1L);
        assertThat(quark.totalBytes()).isEqualTo(2L);
    }

    private OpenListClient client(String mediaBToken, String adultToken) {
        return openListClient(properties(mediaBToken, adultToken));
    }

    private OpenListClient openListClient(PikPakPatProperties patProperties) {
        OpenListProperties openList = new OpenListProperties();
        openList.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        openList.setAuthorization("openlist-token");
        openList.setTimeout(Duration.ofSeconds(2));
        patProperties.setApiBase(openList.getBaseUrl());
        return new OpenListClient(openList, objectMapper, new PikPakPatClient(patProperties, openList, objectMapper));
    }

    private PikPakPatProperties properties(String mediaBToken, String adultToken) {
        PikPakPatProperties properties = new PikPakPatProperties();
        properties.setMediaBPrefix("/pikpak-282b8752-5946-422a-8e52-d17a2be03fff");
        properties.setMediaBToken(mediaBToken);
        properties.setAdultPrefix("/pikpak/");
        properties.setAdultToken(adultToken);
        return properties;
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String query = exchange.getRequestURI().getRawQuery();
        byte[] request = exchange.getRequestBody().readAllBytes();
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if ("/api/fs/add_offline_download".equals(path)) {
            openListSubmits.incrementAndGet();
            respond(exchange, 200, "{\"code\":200,\"data\":{\"tasks\":[{\"id\":\"ol-task\"}]}}");
        } else if (path.startsWith("/api/task/offline_download/info")) {
            respond(exchange, 200, "{\"code\":200,\"data\":{\"id\":\"ol-1\",\"state\":2,\"status\":\"ok\",\"progress\":100,\"total_bytes\":5}}");
        } else if ("/api/task/offline_download/undone".equals(path)) {
            respond(exchange, 200, "{\"code\":200,\"data\":[{\"id\":\"download\"}]}");
        } else if ("/api/task/offline_download_transfer/undone".equals(path)) {
            respond(exchange, 200, transferFails ? "{\"code\":403}" : "{\"code\":200,\"data\":[{\"id\":\"transfer\"}]}");
        } else if (path.startsWith("/api/admin/storage/list")) {
            storageLists.incrementAndGet();
            respond(exchange, 200, "{\"code\":200,\"data\":{\"content\":[{\"driver\":\"PikPak\",\"mount_path\":\"/quark\","
                    + "\"status\":\"work\",\"disabled\":false,\"mount_details\":{\"used_space\":1,\"total_space\":2,\"free_space\":1}}]}}");
        } else if ("/drive/v1/about".equals(path)) {
            respond(exchange, 200, "{\"quota\":{\"limit\":\"1000\",\"usage\":\"250\"}}");
        } else if ("/drive/v1/tasks".equals(path) && "DELETE".equals(method)) {
            deleteQueries.add(query);
            respond(exchange, deletes.incrementAndGet() == 1 ? 200 : 404, "");
        } else if ("/drive/v1/tasks".equals(path)) {
            tasks(exchange, auth, query);
        } else if (path.startsWith("/drive/v1/tasks/")) {
            task(exchange, path);
        } else if ("/drive/v1/files".equals(path) && "GET".equals(method)) {
            listFiles(exchange, query);
        } else if ("/drive/v1/files".equals(path) && "POST".equals(method)) {
            create(exchange, auth, request);
        } else {
            respond(exchange, 404, "");
        }
    }

    private void tasks(HttpExchange exchange, String auth, String query) throws IOException {
        if (taskListStatus != 200) {
            respond(exchange, taskListStatus, "{\"error\":\"nope\"}");
            return;
        }
        if (auth != null && auth.endsWith("media-b-token")) {
            respond(exchange, 200, "{\"tasks\":["
                    + "{\"phase\":\"PHASE_TYPE_RUNNING\"},{\"phase\":\"PHASE_TYPE_ERROR\"},"
                    + "{\"phase\":\"PHASE_TYPE_COMPLETE\"},{\"phase\":\"PHASE_TYPE_PENDING\"}],\"next_page_token\":\"\"}");
            return;
        }
        String page = queryParam(query, "page_token");
        if (page.isEmpty()) {
            respond(exchange, 200, "{\"tasks\":[{\"phase\":\"PHASE_TYPE_PENDING\"}],\"next_page_token\":\"p2\"}");
        } else {
            respond(exchange, 200, "{\"tasks\":[{\"phase\":\"PHASE_TYPE_RUNNING\"},{\"phase\":\"PHASE_TYPE_ERROR\"}],\"next_page_token\":\"\"}");
        }
    }

    private void task(HttpExchange exchange, String path) throws IOException {
        String id = URLDecoder.decode(path.substring("/drive/v1/tasks/".length()), StandardCharsets.UTF_8);
        String phase = phases.get(id);
        if (phase == null) {
            respond(exchange, 404, "");
            return;
        }
        int progress = switch (phase) {
            case "PHASE_TYPE_RUNNING" -> 40;
            case "PHASE_TYPE_COMPLETE" -> 40;
            case "PHASE_TYPE_PENDING" -> 0;
            case "PHASE_TYPE_ERROR" -> 3;
            default -> 150;
        };
        String message = "PHASE_TYPE_ERROR".equals(phase) ? "disk full" : "";
        respond(exchange, 200, "{\"id\":\"" + id + "\",\"phase\":\"" + phase + "\",\"progress\":" + progress
                + ",\"file_size\":\"42\",\"message\":\"" + message + "\"}");
    }

    private void listFiles(HttpExchange exchange, String query) throws IOException {
        fileLists.incrementAndGet();
        fileQueries.add(query);
        String parent = queryParam(query, "parent_id");
        StringBuilder files = new StringBuilder();
        for (Node node : folders.getOrDefault(parent, List.of())) {
            if (!files.isEmpty()) {
                files.append(',');
            }
            files.append("{\"id\":\"").append(node.id).append("\",\"name\":\"").append(node.name)
                    .append("\",\"kind\":\"").append(node.kind).append("\"}");
        }
        respond(exchange, 200, "{\"files\":[" + files + "],\"next_page_token\":\"\"}");
    }

    private void create(HttpExchange exchange, String auth, byte[] request) throws IOException {
        JsonNode body = objectMapper.readTree(request);
        if (!"Bearer media-b-token".equals(auth) && !"Bearer adult-token".equals(auth)) {
            respond(exchange, 401, "{\"error\":\"unauthorized\"}");
            return;
        }
        if ("drive#folder".equals(body.path("kind").asText())) {
            String id = "folder-" + folderCreates.incrementAndGet();
            String name = body.path("name").asText();
            createdNames.add(name);
            folders.computeIfAbsent(body.path("parent_id").asText(), key -> new CopyOnWriteArrayList<>())
                    .add(new Node(id, name, "drive#folder"));
            respond(exchange, 200, "{\"id\":\"" + id + "\",\"kind\":\"drive#folder\",\"name\":\"" + name + "\"}");
            return;
        }
        offlineBodies.add(body);
        offlineAuths.add(auth);
        String url = body.path("url").path("url").asText();
        String taskId = url.contains("raw") ? "raw-1" : "wrapped-1";
        if (url.contains("raw")) {
            respond(exchange, 200, "{\"id\":\"" + taskId + "\",\"phase\":\"PHASE_TYPE_PENDING\",\"file_size\":\"11\"}");
        } else {
            respond(exchange, 200, "{\"task\":{\"id\":\"" + taskId + "\",\"phase\":\"PHASE_TYPE_RUNNING\",\"file_size\":\"11\"}}");
        }
    }

    private String queryParam(String raw, String key) {
        if (raw == null) {
            return "";
        }
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            if (eq < 0) {
                continue;
            }
            if (key.equals(URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8))) {
                return URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private record Node(String id, String name, String kind) {
    }
}
