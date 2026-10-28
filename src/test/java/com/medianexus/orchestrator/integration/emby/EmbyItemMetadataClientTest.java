package com.medianexus.orchestrator.integration.emby;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.EmbyProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class EmbyItemMetadataClientTest {
    @Test
    void classifiesRealItemPathsWithAdultPriorityAndKeepsUnknownLibrariesPrivate() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/Library/VirtualFolders", exchange -> {
            byte[] body = """
                    [{"ItemId":"movies","Name":"Movies","Locations":["/media"]},
                     {"ItemId":"private","Name":"Adult - Other","Locations":["/media/private"]},
                     {"ItemId":"anime","Name":"Anime","Locations":["/anime"]},
                     {"ItemId":"domestic","Name":"国产剧","Locations":["/domestic"]}]
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/Users/user/Items", exchange -> {
            String id = exchange.getRequestURI().getPath().replace("/Users/user/Items/", "");
            String path = switch (id) {
                case "private" -> "/media/private/video.mkv";
                case "animated-movie" -> "/anime/movie.mkv";
                case "episode" -> "/anime/show/episode.mkv";
                case "domestic" -> "/domestic/show/episode.mkv";
                default -> "/unmapped/movie.mkv";
            };
            String type = "private".equals(id) ? "Video" : java.util.Set.of("episode", "domestic").contains(id) ? "Episode" : "Movie";
            byte[] body = ("{\"Id\":\"" + id + "\",\"Type\":\"" + type
                    + "\",\"Path\":\"" + path + "\",\"Name\":\"Title\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            EmbyProperties properties = new EmbyProperties();
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setApiKey("test-token");
            properties.setTimeout(Duration.ofSeconds(2));
            ObjectMapper mapper = new ObjectMapper();
            EmbyItemMetadataClient client = new EmbyItemMetadataClient(properties, mapper, new EmbyClient(properties, mapper));

            var adult = client.findItemMetadata("user", "private").orElseThrow();
            assertThat(adult.watchCategory()).isEqualTo("adult");
            assertThat(adult.libraryId()).isEqualTo("private");
            assertThat(client.findItemMetadata("user", "animated-movie").orElseThrow().watchCategory()).isEqualTo("movie");
            assertThat(client.findItemMetadata("user", "episode").orElseThrow().watchCategory()).isEqualTo("series");
            var domestic = client.findItemMetadata("user", "domestic").orElseThrow();
            assertThat(domestic.watchCategory()).isEqualTo("series");
            assertThat(domestic.libraryName()).isEqualTo("国产剧");
            assertThat(client.findItemMetadata("user", "unknown").orElseThrow().watchCategory()).isEqualTo("unknown");
        } finally {
            server.stop(0);
        }
    }
}
