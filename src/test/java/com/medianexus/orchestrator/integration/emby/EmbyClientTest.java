package com.medianexus.orchestrator.integration.emby;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.EmbyProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EmbyClientTest {

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
    void refreshesOnlyTheSelectedLibraryRecursively() {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> token = new AtomicReference<>();
        server.createContext("/Items/library-id/Refresh", exchange -> {
            method.set(exchange.getRequestMethod());
            query.set(exchange.getRequestURI().getRawQuery());
            token.set(exchange.getRequestHeaders().getFirst("X-Emby-Token"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        EmbyProperties properties = new EmbyProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setApiKey("emby-token");
        properties.setTimeout(Duration.ofSeconds(2));
        EmbyClient client = new EmbyClient(properties, new ObjectMapper());

        client.refreshLibrary("library-id");

        assertThat(method.get()).isEqualTo("POST");
        assertThat(token.get()).isEqualTo("emby-token");
        assertThat(queryParameters(query.get())).containsExactlyInAnyOrder(
                "Recursive=true",
                "MetadataRefreshMode=Default",
                "ImageRefreshMode=Default",
                "ReplaceAllMetadata=false",
                "ReplaceAllImages=false"
        );
    }

    private Set<String> queryParameters(String query) {
        return Arrays.stream(query.split("&")).collect(Collectors.toSet());
    }
}
