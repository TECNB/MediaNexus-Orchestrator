package com.medianexus.orchestrator.integration.pansou;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.PanSouProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PanSouClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger loginCount = new AtomicInteger();
    private final List<JsonNode> searchPayloads = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> magnetResponse = new AtomicReference<>();
    private HttpServer server;
    private PanSouClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/auth/login", exchange -> {
            loginCount.incrementAndGet();
            respond(exchange, 200, "{\"token\":\"jwt-secret\",\"expires_at\":4102444800000,\"username\":\"TEC\"}");
        });
        server.createContext("/api/search", exchange -> {
            searchPayloads.add(readJson(exchange));
            if (magnetResponse.get() != null) {
                respond(exchange, 200, magnetResponse.get());
                return;
            }
            respond(exchange, 200, """
                    {"data":{"results":[
                      {"title":"测试资源","channel":"tg:test","datetime":"2026-08-23","links":[
                        {"type":"quark","url":"https://pan.quark.cn/s/abc123?entry=source","password":"7788","work_title":"测试资源"},
                        {"type":"xunlei","url":"https://pan.xunlei.com/s/not-quark","work_title":"测试资源"}
                      ]}
                    ],"merged_by_type":{"quark":[]}}}
                    """);
        });
        server.createContext("/api/check/links", exchange -> respond(exchange, 200, """
                {"results":[{"disk_type":"quark","url":"https://pan.quark.cn/s/abc123?pwd=7788","normalized_url":"https://pan.quark.cn/s/abc123?pwd=7788","state":"ok","summary":"链接有效"}]}
                """));
        server.start();

        PanSouProperties properties = new PanSouProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setUsername("TEC");
        properties.setPassword("secret");
        properties.setTimeout(Duration.ofSeconds(3));
        client = new PanSouClient(properties, objectMapper);
    }

    @AfterEach
    void tearDown() {
        client.close();
        server.stop(0);
    }

    @Test
    void reusesLoginTokenAndFlattensAllQuarkSearchResults() {
        PanSouSearchResult search = client.search(new PanSouSearchCommand("热辣滚烫", false));
        List<PanSouLinkCheckResult> checks = client.checkLinks(List.of(
                new PanSouLinkCheckRequest("https://pan.quark.cn/s/abc123?pwd=7788", "")
        ));

        assertThat(loginCount).hasValue(1);
        assertThat(search.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.note()).isEqualTo("测试资源");
            assertThat(entry.password()).isEqualTo("7788");
            assertThat(entry.source()).isEqualTo("tg:test");
        });
        assertThat(checks).singleElement().satisfies(check -> {
            assertThat(check.state()).isEqualTo("ok");
            assertThat(check.summary()).isEqualTo("链接有效");
        });
        assertThat(searchPayloads).singleElement().satisfies(payload -> {
            assertThat(payload.path("kw").asText()).isEqualTo("热辣滚烫");
            assertThat(payload.path("cloud_types")).extracting(JsonNode::asText).containsExactly("quark");
            assertThat(payload.path("res").asText()).isEqualTo("all");
            assertThat(payload.path("src").asText()).isEqualTo("all");
            assertThat(payload.path("refresh").asBoolean()).isFalse();
        });
    }

    @Test
    void magnetSearchUsesDefaultSourcesWithoutOverridingPluginsOrChannels() {
        magnetResponse.set("{\"merged_by_type\":{\"magnet\":[]}}");

        assertThat(client.searchMagnets(new PanSouSearchCommand("Show", false)).entries()).isEmpty();

        assertThat(searchPayloads).singleElement().satisfies(payload -> {
            assertThat(payload.path("cloud_types")).extracting(JsonNode::asText).containsExactly("magnet");
            assertThat(payload.path("res").asText()).isEqualTo("all");
            assertThat(payload.path("src").asText("all")).isEqualTo("all");
            assertThat(payload.has("plugins")).isFalse();
            assertThat(payload.has("channels")).isFalse();
        });
    }

    @Test
    void magnetSearchSkipsInvalidLinksAndRetainsValidCandidateSize() {
        String validMagnet = "magnet:?xt=urn:btih:" + "01".repeat(20);
        magnetResponse.set("""
                {"data":{"merged_by_type":{"magnet":[
                  {"url":"magnet:?xt=urn:btih:broken"},
                  {"url":"%s","note":"Show Batch 1080p","source":"tg:test"},
                  {"url":"https://example.test/not-a-magnet"}
                ]},"results":[{"content":"大小: 1 GiB","links":[{"type":"magnet","url":"%s"}]}]}}
                """.formatted(validMagnet, validMagnet));

        assertThat(client.searchMagnets(new PanSouSearchCommand("Show", false)).entries())
                .singleElement().satisfies(entry -> {
                    assertThat(entry.url()).isEqualTo(validMagnet);
                    assertThat(entry.source()).isEqualTo("tg:test");
                    assertThat(entry.sizeBytes()).isEqualTo(1_073_741_824L);
                });
    }

    @Test
    void magnetSearchReadsSingleTorrentTitleSizes() throws IOException {
        String[] titles = {
                "傲慢与偏见-Pride and Prejudice (2005) [1080p][1.8G]",
                "傲慢与偏见-Pride.And.Prejudice.2005.1080p.BluRay.x264-FLHD[8.7G]",
                "切尔诺贝利-Chernobyl.S01.INTERNAL.1080p.BluRay.X264-REWARD[rartv][47.7G]",
                "死亡诗社.Dead.Poets.Society.1989.1080p.BluRay.x264-RARBG.2.46GB",
                "Show 1080p [1 GiB]"
        };
        Long[] bytes = {1_800_000_000L, 8_700_000_000L, 47_700_000_000L, 2_460_000_000L, 1_073_741_824L};
        for (int index = 0; index < titles.length; index++) {
            String magnet = "magnet:?xt=urn:btih:" + "01".repeat(20);
            magnetResponse.set(objectMapper.writeValueAsString(java.util.Map.of("data", java.util.Map.of(
                    "results", List.of(java.util.Map.of("title", titles[index], "content", "评分：8.7 年份：2005",
                            "links", List.of(java.util.Map.of("type", "magnet", "url", magnet)))),
                    "merged_by_type", java.util.Map.of("magnet", List.of(java.util.Map.of("url", magnet, "note", titles[index])))))));
            assertThat(client.searchMagnets(new PanSouSearchCommand("Show", false)).entries())
                    .extracting(PanSouSearchEntry::sizeBytes).as(titles[index]).containsExactly(bytes[index]);
        }
    }

    @Test
    void titleSizesPreserveAmbiguityConflictsAndUnknownValues() throws IOException {
        String magnet = "magnet:?xt=urn:btih:" + "01".repeat(20);
        String other = "magnet:?xt=urn:btih:" + "02".repeat(20);
        for (String title : List.of("Show 1080p", "Show AAC 5.1", "Show MP3 320K", "Show [-1G]",
                "Show [.87G]", "Show [ .87G]", "Show .87G", "Show [1.2.3GB]", "Show [999999999999999999999 TB]")) {
            setMagnetResponse(magnet, List.of(java.util.Map.of("title", title,
                    "links", List.of(java.util.Map.of("type", "magnet", "url", magnet)))));
            assertThat(client.searchMagnets(new PanSouSearchCommand("Show", false)).entries())
                    .extracting(PanSouSearchEntry::sizeBytes).as(title).containsExactly((Long) null);
        }
        for (String content : List.of("大小: -1 GiB", "Size: 2 GiB; Size: 3 GiB", "其他磁力 " + other)) {
            setMagnetResponse(magnet, List.of(java.util.Map.of("title", "Show [8.7G]", "content", content,
                    "links", List.of(java.util.Map.of("type", "magnet", "url", magnet)))));
            assertThat(client.searchMagnets(new PanSouSearchCommand("Show", false)).entries())
                    .extracting(PanSouSearchEntry::sizeBytes).as(content).containsExactly((Long) null);
        }
        setMagnetResponse(magnet, List.of(java.util.Map.of("title", "Show [8.7G]", "links", List.of(
                java.util.Map.of("type", "magnet", "url", magnet), java.util.Map.of("type", "magnet", "url", other)))));
        assertThat(client.searchMagnets(new PanSouSearchCommand("Show", false)).entries())
                .extracting(PanSouSearchEntry::sizeBytes).containsExactly((Long) null);
        for (boolean reverse : List.of(false, true)) {
            var raw = new java.util.ArrayList<>(List.of(
                    java.util.Map.of("title", "Show [8.7G]", "links", List.of(java.util.Map.of("type", "magnet", "url", magnet))),
                    java.util.Map.of("title", "Show [1.8G]", "links", List.of(java.util.Map.of("type", "magnet", "url", magnet))),
                    java.util.Map.of("content", "大小: 8.7 GB", "links", List.of(java.util.Map.of("type", "magnet", "url", magnet)))));
            if (reverse) java.util.Collections.reverse(raw);
            setMagnetResponse(magnet, raw);
            assertThat(client.searchMagnets(new PanSouSearchCommand("Show", false)).entries()).singleElement().satisfies(entry -> {
                assertThat(entry.sizeBytes()).isNull();
                assertThat(entry.sizeConflict()).isTrue();
            });
        }
        for (String content : List.of("Size: -1 GiB", "Size: 2 GB; Size: 3 GB")) {
            for (boolean reverse : List.of(false, true)) {
                var raw = new java.util.ArrayList<>(List.of(
                        java.util.Map.of("content", content, "links", List.of(java.util.Map.of("type", "magnet", "url", magnet))),
                        java.util.Map.of("title", "Show [8.7G]", "links", List.of(java.util.Map.of("type", "magnet", "url", magnet)))));
                if (reverse) java.util.Collections.reverse(raw);
                setMagnetResponse(magnet, raw);
                assertThat(client.searchMagnets(new PanSouSearchCommand("Show", false)).entries()).singleElement().satisfies(entry -> {
                    assertThat(entry.sizeBytes()).isNull();
                    assertThat(entry.sizeConflict()).isTrue();
                });
            }
        }
        setMagnetResponse(magnet, List.of(java.util.Map.of("title", "Show [8.7G]", "content", "Size: 1 GiB",
                "links", List.of(java.util.Map.of("type", "magnet", "url", magnet)))));
        assertThat(client.searchMagnets(new PanSouSearchCommand("Show", false)).entries())
                .extracting(PanSouSearchEntry::sizeBytes).containsExactly(1_073_741_824L);
        setMagnetResponse(magnet, List.of(java.util.Map.of("title", "Show", "links", List.of(
                java.util.Map.of("type", "magnet", "url", magnet, "work_title", "Show [8.7G]")))));
        assertThat(client.searchMagnets(new PanSouSearchCommand("Show", false)).entries())
                .extracting(PanSouSearchEntry::sizeBytes).containsExactly(8_700_000_000L);
        setMagnetResponse(magnet, List.of(java.util.Map.of("title", "Show [1.8G]", "links", List.of(
                java.util.Map.of("type", "magnet", "url", magnet, "work_title", "Show [8.7G]")))));
        assertThat(client.searchMagnets(new PanSouSearchCommand("Show", false)).entries()).singleElement().satisfies(entry -> {
            assertThat(entry.sizeBytes()).isNull();
            assertThat(entry.sizeConflict()).isTrue();
        });
    }

    private void setMagnetResponse(String magnet, Object raw) throws IOException {
        magnetResponse.set(objectMapper.writeValueAsString(java.util.Map.of("data", java.util.Map.of(
                "results", raw, "merged_by_type", java.util.Map.of("magnet", List.of(
                        java.util.Map.of("url", magnet, "note", "Show [8.7G]")))))));
    }

    @Test
    void magnetSearchRejectsNonEmptyListOfOnlyInvalidLinks() {
        magnetResponse.set("""
                {"merged_by_type":{"magnet":[
                  {"url":"magnet:?xt=urn:btih:broken"},
                  {"url":"https://example.test/not-a-magnet"}
                ]}}
                """);

        assertThatThrownBy(() -> client.searchMagnets(new PanSouSearchCommand("Show", false)))
                .isInstanceOfSatisfying(PanSouClientException.class,
                        failure -> assertThat(failure.getReason()).isEqualTo(PanSouClientException.Reason.INVALID_RESPONSE));
    }

    @Test
    void magnetSearchAcceptsEmptyResultsButRejectsMalformedStructure() {
        for (String empty : List.of("{\"merged_by_type\":{\"magnet\":[]}}",
                "{\"data\":{\"merged_by_type\":{}}}", "{\"data\":{\"total\":0}}")) {
            magnetResponse.set(empty);
            assertThat(client.searchMagnets(new PanSouSearchCommand("Show", false)).entries()).isEmpty();
        }
        String validMagnet = "magnet:?xt=urn:btih:" + "01".repeat(20);
        for (String malformed : List.of("{\"merged_by_type\":{\"magnet\":{}}}",
                "{\"data\":null}", "{\"merged_by_type\":{\"magnet\":[null,{\"url\":\"" + validMagnet + "\"}]}}",
                "{\"merged_by_type\":{\"magnet\":[{\"type\":\"quark\",\"url\":\"" + validMagnet + "\"}]}}")) {
            magnetResponse.set(malformed);
            assertThatThrownBy(() -> client.searchMagnets(new PanSouSearchCommand("Show", false)))
                    .isInstanceOfSatisfying(PanSouClientException.class,
                            failure -> assertThat(failure.getReason()).isEqualTo(PanSouClientException.Reason.INVALID_RESPONSE));
        }
    }

    @Test
    void magnetSearchAcceptsOtherCloudRawHitsWithoutMagnetClassification() {
        magnetResponse.set("""
                {"data":{"total":1,"results":[{"links":[{"type":"quark","url":"https://pan.quark.cn/s/abc"}]}]}}
                """);
        assertThat(client.searchMagnets(new PanSouSearchCommand("Show", false)).entries()).isEmpty();

        magnetResponse.set("""
                {"data":{"total":1,"results":[{"links":[{"type":"magnet","url":"magnet:?xt=urn:btih:broken"}]}]}}
                """);
        assertThatThrownBy(() -> client.searchMagnets(new PanSouSearchCommand("Show", false)))
                .isInstanceOfSatisfying(PanSouClientException.class,
                        failure -> assertThat(failure.getReason()).isEqualTo(PanSouClientException.Reason.INVALID_RESPONSE));
        for (String raw : List.of("[null]", "[{\"links\":\"broken\"}]", "[{\"links\":[null]}]",
                "[{\"links\":[{\"url\":\"https://pan.quark.cn/s/abc\"}]}]")) {
            magnetResponse.set("{\"data\":{\"total\":1,\"results\":" + raw + "}}");
            assertThatThrownBy(() -> client.searchMagnets(new PanSouSearchCommand("Show", false)))
                    .isInstanceOfSatisfying(PanSouClientException.class,
                            failure -> assertThat(failure.getReason()).isEqualTo(PanSouClientException.Reason.INVALID_RESPONSE));
        }
    }

    private JsonNode readJson(HttpExchange exchange) throws IOException {
        return objectMapper.readTree(exchange.getRequestBody());
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
