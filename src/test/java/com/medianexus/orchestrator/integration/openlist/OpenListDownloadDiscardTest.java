package com.medianexus.orchestrator.integration.openlist;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.OpenListProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenListDownloadDiscardTest {
    @Test void cancelFailureStillRemovesOnlyTheIsolatedEd2kDirectoryOnce() throws Exception {
        var requests = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var mapper = new ObjectMapper();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/", exchange -> {
            requests.add(exchange.getRequestURI().getPath());
            String payload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean cancel = exchange.getRequestURI().getPath().endsWith("/cancel");
            if (!cancel) {
                var body = mapper.readTree(payload);
                assertThat(body.path("dir").asText()).isEqualTo("/adult/JAV/11.28");
                assertThat(body.path("names").get(0).asText()).isEqualTo("adult-task-12345678-01-ed2kfa6e");
                assertThat(body.path("names").size()).isEqualTo(1);
            }
            byte[] body = (cancel ? "{\"code\":500,\"message\":\"test cancellation error\"}" : "{\"code\":200}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var properties = new OpenListProperties();
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setAuthorization("test"); properties.setTimeout(Duration.ofSeconds(120));
            var client = new OpenListClient(properties, mapper);
            String result = client.discardTimedOutDownload("original", "/adult/JAV/11.28",
                    "/adult/JAV/11.28/adult-task-12345678-01-ed2kfa6e");
            assertThat(result).contains("cancel=FAILED", "test cancellation error", "cleanup=ACKNOWLEDGED");
            assertThat(requests).containsExactly("/api/task/offline_download/cancel", "/api/fs/remove");
            for (String path : List.of("/adult/JAV/11.28", "/adult/JAV/11.27/adult-task-12345678-01-ed2kfa6e",
                    "/adult/JAV/11.28/finished-video", "/adult/JAV/11.28/../11.27/adult-task-12345678-01-ed2kfa6e")) {
                assertThatThrownBy(() -> client.discardTimedOutDownload("original", "/adult/JAV/11.28", path))
                        .isInstanceOf(OpenListClientException.class);
            }
            assertThat(requests).hasSize(2);
        } finally { server.stop(0); }
    }
    @Test void unresponsiveCancelAndCleanupReturnWithinOneBudgetWithoutRetry() throws Exception {
        var requests = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var stopped = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/api/", exchange -> {
            requests.add(exchange.getRequestURI().getPath());
            try { stopped.await(8, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var properties = new OpenListProperties();
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setAuthorization("test"); properties.setTimeout(Duration.ofSeconds(120));
            long started = System.nanoTime();
            String result = new OpenListClient(properties, new ObjectMapper()).discardTimedOutDownload("original",
                    "/adult/JAV/11.28", "/adult/JAV/11.28/adult-task-12345678-01-ed2kfa6e");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(6));
            assertThat(result).contains("cancel=FAILED", "cleanup=FAILED", "请求超时");
            assertThat(requests).containsExactly("/api/task/offline_download/cancel", "/api/fs/remove");
        } finally { stopped.countDown(); server.stop(0); executor.shutdownNow(); }
    }
}
