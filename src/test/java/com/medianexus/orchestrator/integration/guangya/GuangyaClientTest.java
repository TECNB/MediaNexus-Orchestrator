package com.medianexus.orchestrator.integration.guangya;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.GuangyaProperties;
import com.medianexus.orchestrator.service.GuangyaCredentials;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;

class GuangyaClientTest {
    private static final String HASH = "43519d14444904f6015e1a0d92018068fb0a49fa";
    private static final String MAGNET = "magnet:?xt=urn:btih:" + HASH;
    private final ObjectMapper json = new ObjectMapper();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private GuangyaProperties properties;
    private GuangyaCredentials credentials;
    private GuangyaClient client;
    private volatile String response;
    private volatile int status = 200;
    private volatile long responseDelayMillis;

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer fixture-access");
            assertThat(exchange.getRequestHeaders().getFirst("Did")).isEqualTo("fixture-device");
            assertThat(exchange.getRequestHeaders().getFirst("Dt")).isEqualTo("4");
            assertThat(json.readTree(exchange.getRequestBody()).path("url").asText()).isEqualTo(MAGNET);
            if (responseDelayMillis > 0) try { Thread.sleep(responseDelayMillis); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        properties = new GuangyaProperties();
        properties.setApiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setAuthBaseUrl(properties.getApiBaseUrl());
        properties.setMaxResponseBytes(1024 * 1024); properties.setMaxNodes(20000);
        properties.setMaxDepth(32); properties.setCacheMaxEntries(100);
        properties.setCacheTtl(java.time.Duration.ofHours(1));
        credentials = mock(GuangyaCredentials.class);
        when(credentials.snapshot()).thenReturn(new GuangyaCredentials.Snapshot(1, "fixture-access", "fixture-refresh", "fixture-device"));
        client = new GuangyaClient(properties, credentials, json);
        response = envelope("{\"infoHash\":\"" + HASH + "\",\"fileName\":\"ubuntu.iso\",\"fileSize\":6250332160}");
    }
    @AfterEach void stop() { server.stop(0); }
    private String envelope(String tree) { return "{\"msg\":\"success\",\"data\":{\"resType\":1,\"btResInfo\":" + tree + "}}"; }

    @Test void resourceRejectionIsNotAnInvalidFileTableAndCanBeRetriedByAnotherSource() {
        response = "{\"code\":353,\"msg\":\"文件违规\"}";
        assertThatThrownBy(() -> client.resolve(MAGNET, ResolveBudget.start())).isInstanceOfSatisfying(GuangyaException.class,
                failure -> assertThat(failure.reason()).isEqualTo(GuangyaException.Reason.RESOURCE_REJECTED));
        assertThat(client.cached(MAGNET)).isEmpty();
    }

    @Test void resolvesSingleFileAndCachesByHashWithoutSpendingBudget() {
        ResolveBudget budget = ResolveBudget.start();
        var table = client.resolve(MAGNET, budget);
        assertThat(table.identity().btih()).isEqualTo(HASH);
        assertThat(table.rootName()).isEqualTo("ubuntu.iso");
        assertThat(table.files()).containsExactly(new TorrentFileTable.Entry("ubuntu.iso", 6250332160L));
        assertThat(table.totalBytes()).isEqualTo(6250332160L);
        assertThat(client.resolve(MAGNET + "&dn=other", new ResolveBudget(Instant.EPOCH, Instant.EPOCH))).isSameAs(table);
        assertThat(client.cached(MAGNET)).contains(table);
        assertThat(budget.resolveRequests()).isEqualTo(1);
        assertThat(requests).containsExactly("POST /cloudcollection/v1/resolve_res");
    }

    @Test void simultaneousSameHashSharesOneHttpRequestAcrossRounds() throws Exception {
        responseDelayMillis = 250;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        ResolveBudget first = ResolveBudget.start(), second = ResolveBudget.start();
        try {
            var one = pool.submit(() -> client.resolve(MAGNET, first));
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(1)).until(() -> requests.size() == 1);
            var two = pool.submit(() -> client.resolve(MAGNET, second));
            assertThat(two.get(2, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(one.get());
            assertThat(requests).hasSize(1);
            assertThat(first.resolveRequests() + second.resolveRequests()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test void flattensNestedTreeAndChecksAllLeafCountsAndSizes() {
        response = envelope("""
                {"infoHash":"%s","fileName":"root","fileSize":"5","subfilesNum":2,"subfiles":[
                  {"fileName":"S01","isDir":true,"fileSize":5,"subfilesNum":2,"subfiles":[
                    {"fileName":"E01.mkv","fileSize":2},{"fileName":"E02.mkv","fileSize":3}]}]}
                """.formatted(HASH));
        assertThat(client.resolve(MAGNET, ResolveBudget.start()).files()).containsExactly(
                new TorrentFileTable.Entry("S01/E01.mkv", 2), new TorrentFileTable.Entry("S01/E02.mkv", 3));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"../evil", "/absolute", "C:evil", "a/b", "a\\b", ".", "..", " spaced "})
    void rejectsUnsafePathWithoutCaching(String name) throws Exception {
        var tree = json.createObjectNode().put("infoHash", HASH).put("fileName", name).put("fileSize", 0);
        response = envelope(tree.toString());
        assertThatThrownBy(() -> client.resolve(MAGNET, ResolveBudget.start())).isInstanceOf(GuangyaException.class);
        assertThat(client.cached(MAGNET)).isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "{\"fileName\":\"root\",\"fileSize\":-1}",
            "{\"fileName\":\"root\",\"fileSize\":1.5}",
            "{\"fileName\":\"root\",\"fileSize\":9223372036854775808}",
            "{\"fileName\":\"root\"}",
            "{\"fileName\":\"root\",\"fileSize\":2,\"subfilesNum\":2,\"subfiles\":[{\"fileName\":\"x\",\"fileSize\":2}]}",
            "{\"fileName\":\"root\",\"fileSize\":3,\"subfilesNum\":1,\"subfiles\":[{\"fileName\":\"x\",\"fileSize\":2}]}",
            "{\"fileName\":\"root\",\"fileSize\":0,\"isDir\":true}",
            "{\"fileName\":\"root\",\"fileSize\":0,\"isDir\":true,\"subfiles\":[]}",
            "{\"fileName\":\"root\",\"fileSize\":4,\"subfilesNum\":2,\"subfiles\":[{\"fileName\":\"x\",\"fileSize\":2},{\"fileName\":\"x\",\"fileSize\":2}]}"
    })
    void rejectsMalformedSizeCountOrTree(String input) throws Exception {
        var tree = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(input);
        tree.put("infoHash", HASH); response = envelope(tree.toString());
        assertThatThrownBy(() -> client.resolve(MAGNET, ResolveBudget.start())).isInstanceOf(GuangyaException.class);
        assertThat(client.cached(MAGNET)).isEmpty();
    }

    @Test void rejectsDifferentHashAndResponseSizeLimitAndTreeLimits() {
        response = response.replace(HASH, "0000000000000000000000000000000000000000");
        assertThatThrownBy(() -> client.resolve(MAGNET, ResolveBudget.start())).isInstanceOf(GuangyaException.class);
        response = envelope("{\"infoHash\":\"" + HASH + "\",\"fileName\":\"root\",\"fileSize\":1,\"subfilesNum\":1,\"subfiles\":[{\"fileName\":\"dir\",\"isDir\":true,\"fileSize\":1,\"subfiles\":[{\"fileName\":\"x\",\"fileSize\":1}]}]}");
        properties.setMaxNodes(1);
        assertThatThrownBy(() -> client.resolve(MAGNET, ResolveBudget.start())).isInstanceOf(GuangyaException.class);
        properties.setMaxNodes(10); properties.setMaxDepth(1);
        assertThatThrownBy(() -> client.resolve(MAGNET, ResolveBudget.start())).isInstanceOf(GuangyaException.class);
        properties.setMaxDepth(32); properties.setMaxResponseBytes(10);
        assertThatThrownBy(() -> client.resolve(MAGNET, ResolveBudget.start())).isInstanceOf(GuangyaException.class);
        assertThat(client.cached(MAGNET)).isEmpty();
    }

    @Test void twentyHttpAttemptsAreSharedAndOrdinaryFailureNeverRetries() {
        status = 503; response = "{}";
        ResolveBudget budget = ResolveBudget.start();
        for (int i = 0; i < 20; i++) assertThatThrownBy(() -> client.resolve(MAGNET, budget)).isInstanceOf(GuangyaException.class);
        assertThatThrownBy(() -> client.resolve(MAGNET, budget)).isInstanceOfSatisfying(GuangyaException.class,
                e -> assertThat(e.reason()).isEqualTo(GuangyaException.Reason.BUDGET_EXHAUSTED));
        assertThat(budget.resolveRequests()).isEqualTo(20);
        assertThat(requests).hasSize(20);
    }

    @Test void expiredBudgetInvalidMagnetAndMissingCredentialsNeverSendHttp() {
        assertThatThrownBy(() -> client.resolve(MAGNET, new ResolveBudget(Instant.EPOCH, Instant.EPOCH))).isInstanceOf(GuangyaException.class);
        assertThatThrownBy(() -> client.resolve("https://fixture.invalid/x", ResolveBudget.start())).isInstanceOf(GuangyaException.class);
        when(credentials.snapshot()).thenReturn(new GuangyaCredentials.Snapshot(0, null, null, null));
        assertThat(client.isConfigured()).isFalse();
        assertThatThrownBy(() -> client.resolve(MAGNET, ResolveBudget.start())).isInstanceOfSatisfying(GuangyaException.class,
                e -> assertThat(e.reason()).isEqualTo(GuangyaException.Reason.NOT_CONFIGURED));
        assertThat(requests).isEmpty();
    }

    @Test void singleFileWithProtobufZeroSubfileCountIsNotAnEmptyTorrent() {
        response = response.replace("6250332160}", "6250332160,\"subfilesNum\":0}");
        assertThat(client.resolve(MAGNET, ResolveBudget.start()).files()).hasSize(1);
    }

    @Test void directoryAndFilePathCollisionIsRejected() {
        response = envelope("{\"infoHash\":\"" + HASH + "\",\"fileName\":\"root\",\"fileSize\":2,\"subfilesNum\":2,\"subfiles\":[{\"fileName\":\"dir\",\"isDir\":true,\"fileSize\":1,\"subfiles\":[{\"fileName\":\"x\",\"fileSize\":1}]},{\"fileName\":\"dir\",\"fileSize\":1}]}");
        assertThatThrownBy(() -> client.resolve(MAGNET, ResolveBudget.start())).isInstanceOf(GuangyaException.class);
    }

    @Test void multiFileRootWithoutDeclaredCountCannotBeProvenComplete() {
        response = envelope("{\"infoHash\":\"" + HASH + "\",\"fileName\":\"root\",\"fileSize\":1,\"subfiles\":[{\"fileName\":\"x\",\"fileSize\":1}]}");
        assertThatThrownBy(() -> client.resolve(MAGNET, ResolveBudget.start())).isInstanceOf(GuangyaException.class);
    }

    @Test void boundedCacheExpiresWithoutCachingFailures() throws Exception {
        properties.setCacheMaxEntries(1); properties.setCacheTtl(java.time.Duration.ofMillis(20));
        client.resolve(MAGNET, ResolveBudget.start());
        Thread.sleep(30);
        assertThat(client.cached(MAGNET)).isEmpty();
        properties.setCacheTtl(java.time.Duration.ofHours(1));
        client.resolve(MAGNET, ResolveBudget.start());
        assertThat(requests).hasSize(2);
    }

    @Test void responseHashIsCaseInsensitiveAndBase32InputUsesSameIdentity() {
        response = response.replace(HASH, HASH.toUpperCase(java.util.Locale.ROOT));
        assertThat(client.resolve(MAGNET, ResolveBudget.start()).identity().btih()).isEqualTo(HASH);
        assertThat(client.cached(MAGNET.replace(HASH, HASH.toUpperCase(java.util.Locale.ROOT)))).isPresent();
        assertThat(client.resolve("magnet:?xt=urn:btih:INIZ2FCEJECPMAK6DIGZEAMAND5QUSP2", ResolveBudget.start()).identity().btih()).isEqualTo(HASH);
        assertThat(requests).hasSize(1);
    }

    @Test void arithmeticOverflowIsRejectedRatherThanWrappedToSmallerPackage() {
        response = envelope("{\"infoHash\":\"" + HASH + "\",\"fileName\":\"root\",\"fileSize\":1,\"subfilesNum\":2,\"subfiles\":[{\"fileName\":\"x\",\"fileSize\":9223372036854775807},{\"fileName\":\"y\",\"fileSize\":1}]}");
        assertThatThrownBy(() -> client.resolve(MAGNET, ResolveBudget.start())).isInstanceOf(GuangyaException.class);
    }
}
