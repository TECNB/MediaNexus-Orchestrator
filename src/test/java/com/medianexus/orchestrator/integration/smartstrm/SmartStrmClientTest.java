package com.medianexus.orchestrator.integration.smartstrm;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.SmartStrmProperties;
import com.medianexus.orchestrator.integration.autosymlink.AutoSymlinkClientException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SmartStrmClientTest {
    HttpServer server;
    SmartStrmClient client;
    final AtomicInteger logins = new AtomicInteger();
    final AtomicReference<String> form = new AtomicReference<>();
    @BeforeEach void setup() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/login", exchange -> {
            form.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("Set-Cookie", "session=" + logins.incrementAndGet() + "; Path=/; HttpOnly");
            exchange.getResponseHeaders().add("Location", "/");
            respond(exchange, 302, "");
        });
        server.createContext("/api/tasks", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Cookie")).contains("session=");
            respond(exchange, 200, "{}");
        });
        server.createContext("/api/settings", exchange -> respond(exchange, 200,
                "{\"webhook\":{\"url\":\"https://browser.invalid/webhook/test-token\"}}"));
        server.start();
        var p = new SmartStrmProperties(); p.setEnabled(true); p.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        p.setUsername("admin user"); p.setPassword("test&password"); p.setTimeout(Duration.ofSeconds(2));
        client = new SmartStrmClient(p, new ObjectMapper());
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void logsInNormallyAndRecoversReadOnceFromLoginHtml() {
        AtomicInteger reads = new AtomicInteger();
        server.createContext("/api/storages", exchange -> {
            if (reads.incrementAndGet() == 1) {
                exchange.getResponseHeaders().set("Content-Type", "text/html");
                respond(exchange, 200, "<html><form action='/login'></form></html>");
            } else respond(exchange, 200, "[]");
        });
        assertThat(client.storages().isArray()).isTrue();
        assertThat(form.get()).isEqualTo("username=admin%20user&password=test%26password");
        assertThat(logins.get()).isEqualTo(2);
        assertThat(reads.get()).isEqualTo(2);
    }

    @Test void doesNotReplayWritesAfter401OrUnknownResponse() {
        AtomicInteger writes = new AtomicInteger();
        server.createContext("/webhook/test-token", exchange -> {
            int count = writes.incrementAndGet();
            respond(exchange, count == 1 ? 401 : 500, "private upstream body");
        });
        assertThatThrownBy(() -> client.run("example", "/Film"))
                .isInstanceOf(AutoSymlinkClientException.class).hasMessageContaining("未重复提交");
        assertThat(writes.get()).isEqualTo(1);
        assertThat(logins.get()).isEqualTo(2);
        assertThatThrownBy(() -> client.run("example", "/Other"))
                .isInstanceOf(AutoSymlinkClientException.class).hasMessageNotContaining("private");
        assertThat(writes.get()).isEqualTo(2);
    }

    @Test void readsPlainTextLogAndEncodesSubdirectoryWithoutRewriting() {
        AtomicReference<String> path = new AtomicReference<>();
        server.createContext("/api/tasks/example/log", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/plain"); respond(exchange, 200, "finished\n");
        });
        server.createContext("/api/storage/example/browse", exchange -> {
            path.set(exchange.getRequestURI().getRawQuery()); respond(exchange, 200, "[]");
        });
        assertThat(client.taskLog("example")).isEqualTo("finished\n");
        client.browse("example", "/Film (2026)");
        assertThat(path.get()).isEqualTo("path=%2FFilm%20%282026%29");
    }

    @Test void embyCreatedNotificationKeepsPreciseOriginalPaths() throws Exception {
        AtomicReference<String> notification = new AtomicReference<>();
        server.createContext("/Library/Media/Updated", exchange -> {
            notification.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 204, "");
        });
        var p = new com.medianexus.orchestrator.config.EmbyProperties();
        p.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort()); p.setApiKey("test-key"); p.setTimeout(Duration.ofSeconds(2));
        var emby = new com.medianexus.orchestrator.integration.emby.EmbyClient(p, new ObjectMapper());
        String original = "/srv/media/STRM/Movies/Film (2026)/Film (2026) Extended.strm";
        emby.notifyMediaCreated(java.util.List.of(original, original));
        var updates = new ObjectMapper().readTree(notification.get()).path("Updates");
        assertThat(updates.size()).isEqualTo(1);
        assertThat(updates.get(0).path("Path").asText()).isEqualTo(original);
        assertThat(updates.get(0).path("UpdateType").asText()).isEqualTo("Created");
    }

    @Test void officialTaskWebhookUsesConfiguredOriginAndExactOverridesOnce() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        AtomicReference<String> payload = new AtomicReference<>();
        server.createContext("/webhook/test-token", exchange -> {
            writes.incrementAndGet(); payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"success\":true}");
        });
        var mapper = new ObjectMapper();
        var task = mapper.createObjectNode().put("name", "example").put("storage_path", "/Film")
                .put("incremental", false).put("keep_local_asset", true);
        task.putObject("plugins").putObject("scan_filter").put("copy_ext", "srt,ass");
        client.run(task);
        var expected = mapper.createObjectNode().put("event", "a_task").put("delay", 0).put("debounce", 0);
        expected.set("task", task);
        assertThat(mapper.readTree(payload.get())).isEqualTo(expected);
        assertThat(writes.get()).isEqualTo(1);
    }

    @Test void rejectsUnsafeWebhookPathBeforeAnyWrite() {
        server.removeContext("/api/settings");
        server.createContext("/api/settings", exchange -> respond(exchange, 200,
                "{\"webhook\":{\"url\":\"https://browser.invalid/api/tasks?secret=private\"}}"));
        assertThatThrownBy(() -> client.run("example", "/")).hasMessageContaining("Webhook 设置无效").hasMessageNotContaining("private");
    }

    @Test void rejectsJsonFailureForWritesAndBrowseWithoutSensitiveBody() {
        AtomicInteger writes = new AtomicInteger();
        server.createContext("/api/tasks/example", exchange -> {
            writes.incrementAndGet(); respond(exchange, 200, "{\"success\":false,\"message\":\"private secret\"}");
        });
        server.createContext("/api/storage/example/browse", exchange ->
                respond(exchange, 200, "{\"success\":false,\"files\":[],\"message\":\"private secret\"}"));
        assertThatThrownBy(() -> client.saveTask("example", new ObjectMapper().createObjectNode()))
                .hasMessageContaining("SmartStrm").hasMessageNotContaining("private");
        assertThatThrownBy(() -> client.browse("example", "/")).hasMessageContaining("SmartStrm");
        assertThat(writes.get()).isEqualTo(1);
    }

    static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
