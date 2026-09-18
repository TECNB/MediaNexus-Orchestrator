package com.medianexus.orchestrator.integration.quark;

import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.QasProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class QuarkDirectClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void treatsAnAlreadyDeletedFileAsIdempotentSuccess() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/1/clouddrive/file/delete", exchange -> {
            byte[] body = "{\"code\":23004,\"message\":\"[文件已经删除,请稍后重试]\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        QasProperties properties = new QasProperties();
        properties.setQuarkCookie("cookie=value");
        properties.setQuarkApiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setTimeout(Duration.ofSeconds(2));
        QuarkDirectClient client = new QuarkDirectClient(properties, new ObjectMapper());

        assertThatCode(() -> client.deleteOwnedFiles(List.of("8a98133a4d99445096bf722f8c628dd1")))
                .doesNotThrowAnyException();
    }
}
