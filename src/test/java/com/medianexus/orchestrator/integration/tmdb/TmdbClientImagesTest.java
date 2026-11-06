package com.medianexus.orchestrator.integration.tmdb;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.TmdbProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class TmdbClientImagesTest {

    @Test
    void requestsImagesByMediaIdentityWithoutFilteringOtherLanguages() throws Exception {
        List<String> paths = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/3/", exchange -> {
            paths.add(exchange.getRequestURI().toString());
            byte[] response = "{\"backdrops\":[{\"file_path\":\"/cover.jpg\",\"iso_639_1\":null}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            TmdbProperties properties = new TmdbProperties();
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/3");
            properties.setApiToken("demo-token");
            properties.setTimeout(Duration.ofSeconds(2));
            TmdbClient client = new TmdbClient(properties, new ObjectMapper());

            assertThat(client.getMovieImages(157336).path("backdrops").size()).isEqualTo(1);
            assertThat(client.getTvImages(209867).path("backdrops").size()).isEqualTo(1);
            assertThat(paths).containsExactly("/3/movie/157336/images", "/3/tv/209867/images");
        } finally {
            server.stop(0);
        }
    }
}
