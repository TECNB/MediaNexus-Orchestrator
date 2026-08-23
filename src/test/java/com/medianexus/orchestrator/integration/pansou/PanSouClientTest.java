package com.medianexus.orchestrator.integration.pansou;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.PanSouProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PanSouClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger loginCount = new AtomicInteger();
    private final List<JsonNode> searchPayloads = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private PanSouClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/auth/login", exchange -> {
            loginCount.incrementAndGet();
            respond(exchange, 200, "{\"token\":\"jwt-secret\",\"expires_at\":4102444800000,\"username\":\"TEC\"}");
        });
        server.createContext("/api/search", exchange -> {
            searchPayloads.add(readJson(exchange));
            respond(exchange, 200, """
                    {"data":{"merged_by_type":{"quark":[
                      {"url":"https://pan.quark.cn/s/abc123?entry=source","password":"7788","note":"测试资源","datetime":"2026-08-23","source":"tg:test"}
                    ]}}}
                    """);
        });
        server.createContext("/api/check/links", exchange -> respond(exchange, 200, """
                {"results":[{"disk_type":"quark","url":"https://pan.quark.cn/s/abc123?pwd=7788","normalized_url":"https://pan.quark.cn/s/abc123?pwd=7788","state":"ok","summary":"链接有效"}]}
                """));
        server.start();

        PanSouProperties properties = new PanSouProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setUsername("TEC");
        properties.setPassword("secret");
        properties.setTimeout(Duration.ofSeconds(3));
        client = new PanSouClient(properties, objectMapper);
    }

    @AfterEach
    void tearDown() {
        client.close();
        server.stop(0);
    }

    @Test
    void reusesLoginTokenAndSendsMergedQuarkSearchContract() {
        PanSouSearchResult search = client.search(new PanSouSearchCommand("热辣滚烫", false));
        List<PanSouLinkCheckResult> checks = client.checkLinks(List.of(
                new PanSouLinkCheckRequest("https://pan.quark.cn/s/abc123?pwd=7788", "")
        ));

        assertThat(loginCount).hasValue(1);
        assertThat(search.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.note()).isEqualTo("测试资源");
            assertThat(entry.password()).isEqualTo("7788");
            assertThat(entry.source()).isEqualTo("tg:test");
        });
        assertThat(checks).singleElement().satisfies(check -> {
            assertThat(check.state()).isEqualTo("ok");
            assertThat(check.summary()).isEqualTo("链接有效");
        });
        assertThat(searchPayloads).singleElement().satisfies(payload -> {
            assertThat(payload.path("kw").asText()).isEqualTo("热辣滚烫");
            assertThat(payload.path("cloud_types")).extracting(JsonNode::asText).containsExactly("quark");
            assertThat(payload.path("res").asText()).isEqualTo("merge");
            assertThat(payload.path("src").asText()).isEqualTo("all");
            assertThat(payload.path("refresh").asBoolean()).isFalse();
        });
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
