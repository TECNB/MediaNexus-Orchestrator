package com.medianexus.orchestrator.integration.openlist;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.OpenListProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenListClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Set<String> visibleDirectoryNames = ConcurrentHashMap.newKeySet();
    private final AtomicInteger getRequests = new AtomicInteger();
    private final AtomicInteger listRequests = new AtomicInteger();
    private final AtomicInteger mkdirRequests = new AtomicInteger();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/fs/get", exchange -> {
            getRequests.incrementAndGet();
            respond(exchange, "{\"code\":200,\"message\":\"success\",\"data\":{}}");
        });
        server.createContext("/api/fs/mkdir", exchange -> {
            mkdirRequests.incrementAndGet();
            JsonNode body = objectMapper.readTree(exchange.getRequestBody());
            String path = body.path("path").asText();
            visibleDirectoryNames.add(path.substring(path.lastIndexOf('/') + 1));
            respond(exchange, "{\"code\":200,\"message\":\"success\",\"data\":{}}");
        });
        server.createContext("/api/fs/list", exchange -> {
            listRequests.incrementAndGet();
            String content = visibleDirectoryNames.stream()
                    .sorted()
                    .map(name -> "{\"name\":\"" + name + "\",\"size\":0,\"is_dir\":true}")
                    .collect(java.util.stream.Collectors.joining(","));
            respond(exchange, "{\"code\":200,\"message\":\"success\",\"data\":{\"content\":[" + content + "]}}");
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void preparesSiblingDirectoriesWithOneAggregateParentConfirmation() {
        OpenListProperties properties = new OpenListProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setAuthorization("test-token");
        properties.setTimeout(Duration.ofSeconds(2));
        OpenListClient client = new OpenListClient(properties, objectMapper);

        client.ensureChildDirectoriesReady(
                "/pikpak/Media/Adult/JAV/7.15",
                List.of("adult-task-01", "adult-task-02", "adult-task-03", "adult-task-04")
        );

        assertThat(getRequests).hasValue(0);
        assertThat(mkdirRequests).hasValue(4);
        assertThat(listRequests).hasValue(1);
        assertThat(visibleDirectoryNames).containsExactlyInAnyOrder(
                "adult-task-01",
                "adult-task-02",
                "adult-task-03",
                "adult-task-04"
        );
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
