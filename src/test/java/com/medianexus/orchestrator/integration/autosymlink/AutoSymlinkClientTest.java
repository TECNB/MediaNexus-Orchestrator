package com.medianexus.orchestrator.integration.autosymlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.AutoSymlinkProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AutoSymlinkClientTest {

    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void acceptsNonJsonSuccessResponse() {
        AtomicReference<String> cookie = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/common_tools/add_sync_task/task-id", exchange -> {
            cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "queued".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(202, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        AutoSymlinkProperties properties = new AutoSymlinkProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setCookie("authenticated=true");
        properties.setTimeout(Duration.ofSeconds(2));
        AutoSymlinkClient client = new AutoSymlinkClient(properties, new ObjectMapper());

        assertThatCode(() -> client.submitSyncTask("task-id", new ObjectMapper().createObjectNode()
                        .put("mode", "manual")))
                .doesNotThrowAnyException();
        assertThat(cookie.get()).isEqualTo("authenticated=true");
        assertThat(requestBody.get()).isEqualTo("{\"mode\":\"manual\"}");
    }
}
