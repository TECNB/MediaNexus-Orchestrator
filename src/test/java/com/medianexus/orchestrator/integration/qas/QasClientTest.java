package com.medianexus.orchestrator.integration.qas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.QasProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QasClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<JsonNode> executionPayloads = new CopyOnWriteArrayList<>();
    private final List<JsonNode> createPayloads = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private QasClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/get_share_detail", this::handleShareDetail);
        server.createContext("/run_script_now", exchange -> {
            executionPayloads.add(readJson(exchange));
            respond(exchange, 200, "data: done\n\n");
        });
        server.createContext("/api/add_task", exchange -> {
            JsonNode payload = readJson(exchange);
            createPayloads.add(payload);
            respond(exchange, 200, "{\"success\":true,\"data\":" + payload + "}");
        });
        server.start();

        QasProperties properties = new QasProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setApiToken("test-token");
        properties.setTimeout(Duration.ofSeconds(3));
        client = new QasClient(properties, objectMapper);
    }

    @AfterEach
    void tearDown() {
        client.close();
        server.stop(0);
    }

    @Test
    void recursivelyInspectsShareWithoutExposingTemporaryStoken() {
        QasShareTree tree = client.inspectShare("https://pan.quark.cn/s/share-id?pwd=1234");

        assertThat(tree.entries()).singleElement().satisfies(wrapper -> {
            assertThat(wrapper.name()).isEqualTo("wrapper");
            assertThat(wrapper.children()).extracting(QasShareNode::name)
                    .containsExactly("4K", "4K高码率");
            assertThat(wrapper.children()).allSatisfy(version ->
                    assertThat(version.children()).extracting(QasShareNode::name)
                            .containsExactly("01.mkv", "02.mkv")
            );
        });
        assertThat(tree.toString()).doesNotContain("temporary-secret");
    }

    @Test
    void rejectsShareTreesOverNodeLimit() {
        server.removeContext("/get_share_detail");
        server.createContext("/get_share_detail", exchange -> {
            List<String> files = new ArrayList<>();
            for (int index = 0; index < 501; index++) {
                files.add("{\"fid\":\"f" + index + "\",\"file_name\":\"" + index
                        + ".mkv\",\"dir\":false,\"obj_category\":\"video\"}");
            }
            respond(exchange, 200, "{\"success\":true,\"data\":{\"stoken\":\"temporary-secret\",\"list\":["
                    + String.join(",", files) + "]}}");
        });

        assertThatThrownBy(() -> client.inspectShare("https://pan.quark.cn/s/share-id"))
                .isInstanceOf(QasShareInspectionException.class)
                .hasMessageContaining("500");
    }

    @Test
    void submitsAllCreatedTasksInOneImmediateExecutionRequest() throws Exception {
        JsonNode first = objectMapper.readTree("{\"taskname\":\"A\",\"pattern\":\"^(\\\\d+)\\\\.mkv$\"}");
        JsonNode second = objectMapper.readTree("{\"taskname\":\"B\",\"replace\":\"S01E\\\\1\"}");

        client.triggerTasksNow(List.of(
                new QasCreatedTask("A", "/TV/A", first),
                new QasCreatedTask("B", "/TV/A", second)
        ));

        assertThat(executionPayloads).singleElement().satisfies(payload -> {
            assertThat(payload.path("tasklist")).hasSize(2);
            assertThat(payload.path("tasklist").get(0).path("pattern").asText()).isEqualTo("^(\\d+)\\.mkv$");
            assertThat(payload.path("tasklist").get(1).path("replace").asText()).isEqualTo("S01E\\1");
        });
    }

    @Test
    void serializesPatternAndReplacementWithoutChangingBackreferences() {
        QasCreatedTask task = client.createTask(new QasTaskCreateCommand(
                "我的阿勒泰 S01 [4K]",
                "https://pan.quark.cn/s/share/fid",
                "/TV/我的阿勒泰/Season 01",
                "^(\\d{2,3})\\.(mkv|mp4)$",
                "我的阿勒泰 - S01E\\1 - 4K.\\2"
        ));

        assertThat(task.taskName()).isEqualTo("我的阿勒泰 S01 [4K]");
        assertThat(createPayloads).singleElement().satisfies(payload -> {
            assertThat(payload.path("pattern").asText()).isEqualTo("^(\\d{2,3})\\.(mkv|mp4)$");
            assertThat(payload.path("replace").asText()).isEqualTo("我的阿勒泰 - S01E\\1 - 4K.\\2");
        });
    }

    @Test
    void redactsUrlsAndCredentialsBeforeForwardingExecutionOutput() {
        String sanitized = client.sanitizeExecutionOutput(
                "任务 https://pan.quark.cn/s/share?pwd=1234 token=secret stoken:temporary cookie=abc"
        );

        assertThat(sanitized)
                .contains("[链接已隐藏]", "token=***", "stoken=***", "cookie=***")
                .doesNotContain("pwd=1234", "secret", "temporary", "abc");
    }

    private void handleShareDetail(HttpExchange exchange) throws IOException {
        String shareUrl = readJson(exchange).path("shareurl").asText();
        String body;
        if (shareUrl.endsWith("/wrapper-fid?pwd=1234")) {
            body = detail(dir("4k-fid", "4K"), dir("high-fid", "4K高码率"));
        } else if (shareUrl.contains("4k-fid") || shareUrl.contains("high-fid")) {
            body = detail(file("01-fid", "01.mkv"), file("02-fid", "02.mkv"));
        } else {
            body = detail(dir("wrapper-fid", "wrapper"));
        }
        respond(exchange, 200, body);
    }

    private String detail(String... entries) {
        return "{\"success\":true,\"data\":{\"stoken\":\"temporary-secret\",\"list\":["
                + String.join(",", entries) + "]}}";
    }

    private String dir(String fid, String name) {
        return "{\"fid\":\"" + fid + "\",\"file_name\":\"" + name + "\",\"dir\":true}";
    }

    private String file(String fid, String name) {
        return "{\"fid\":\"" + fid + "\",\"file_name\":\"" + name
                + "\",\"dir\":false,\"obj_category\":\"video\",\"size\":1}";
    }

    private JsonNode readJson(HttpExchange exchange) throws IOException {
        return objectMapper.readTree(exchange.getRequestBody());
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
