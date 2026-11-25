package com.medianexus.orchestrator.integration.openlist;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.OpenListProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OpenListAlternativeTargetTest {
    @Test void acceptsOnlyExplicitlyCompleteEmptyDirectory() throws Exception {
        var listing = new AtomicReference<>("{\"content\":null,\"total\":0}");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/fs/get", e -> {
            byte[] body = "{\"code\":200,\"data\":{}}".getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200, body.length); e.getResponseBody().write(body); e.close();
        });
        server.createContext("/api/fs/list", e -> {
            byte[] body = ("{\"code\":200,\"data\":" + listing.get() + "}").getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200, body.length); e.getResponseBody().write(body); e.close();
        });
        server.start();
        try {
            var properties = new OpenListProperties();
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setAuthorization("test"); properties.setTimeout(Duration.ofSeconds(2));
            var client = new OpenListClient(properties, new ObjectMapper());
            client.requireEmptyAlternativeTarget("/media/season");
            listing.set("{\"content\":[],\"total\":0}");
            client.requireEmptyAlternativeTarget("/media/season");
            for (String bad : new String[]{"{}", "{\"content\":null}", "{\"content\":null,\"total\":1}",
                    "{\"content\":[],\"total\":1}", "{\"content\":{},\"total\":0}",
                    "{\"content\":[{\"name\":\"old\",\"is_dir\":true}],\"total\":0}"}) {
                listing.set(bad);
                assertThatThrownBy(() -> client.requireEmptyAlternativeTarget("/media/season"))
                        .isInstanceOf(OpenListClientException.class);
            }
        } finally { server.stop(0); }
    }
}
