package com.medianexus.orchestrator.integration.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.TelegramWorkerProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class TelegramWorkerClientTest {

    private HttpServer server;
    private TelegramWorkerClient client;
    private final AtomicReference<String> requestBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/resolve-source", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("Bearer test-token", exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"sourceId\":-100123,\"title\":\"测试频道\",\"forwardsRestricted\":false}");
        });
        server.createContext("/forward-unread", exchange -> respond(
                exchange,
                429,
                "{\"error\":{\"message\":\"wait\",\"retryable\":true,\"retryAfterSeconds\":42}}"
        ));
        server.start();

        TelegramWorkerProperties properties = new TelegramWorkerProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setApiToken("test-token");
        properties.setTimeout(Duration.ofSeconds(2));
        client = new TelegramWorkerClient(properties, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void resolvesNumericSourceAndKeepsWorkerRetryContract() throws Exception {
        JsonNode resolved = client.resolveSource("-100123");
        assertEquals(-100123, resolved.path("sourceId").asLong());
        assertEquals(-100123, new ObjectMapper().readTree(requestBody.get()).path("source").asLong());

        TelegramWorkerClientException exception = assertThrows(
                TelegramWorkerClientException.class,
                () -> client.forwardUnread(new ObjectMapper().createObjectNode())
        );
        assertTrue(exception.isRetryable());
        assertEquals(42, exception.getRetryAfterSeconds());
    }

    @Test
    void springCanInstantiateClientWithItsProductionConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(TelegramWorkerProperties.class, () -> {
                TelegramWorkerProperties properties = new TelegramWorkerProperties();
                properties.setTimeout(Duration.ofSeconds(2));
                return properties;
            });
            context.registerBean(ObjectMapper.class);
            context.register(TelegramWorkerClient.class);
            context.refresh();

            assertTrue(context.containsBean("telegramWorkerClient"));
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
