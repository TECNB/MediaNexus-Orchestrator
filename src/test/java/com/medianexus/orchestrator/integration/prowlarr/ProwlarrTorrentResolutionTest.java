package com.medianexus.orchestrator.integration.prowlarr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.ProwlarrProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProwlarrTorrentResolutionTest {
    private HttpServer fixture;

    @AfterEach
    void stopFixture() { if (fixture != null) fixture.stop(0); }

    @Test
    void fetchesOriginalTorrentDespiteKnownSearchMagnetAndHash() throws Exception {
        byte[] body = TorrentMetadataTest.torrent(TorrentMetadataTest.singleInfo("episode.mkv", 4));
        String hash = TorrentMetadata.parse(body, null).identity().btih();
        AtomicInteger requests = new AtomicInteger();
        ProwlarrClient client = serve(body, requests);
        ProwlarrRelease release = release("magnet:?xt=urn:btih:" + hash, hash, "backup-reference");
        assertThat(client.resolveMagnet(release)).contains(hash);
        assertThat(requests).hasValue(0);
        ResolvedTorrent resolved = client.resolveTorrent(release, Duration.ofSeconds(2));
        assertThat(resolved.table()).isPresent();
        assertThat(resolved.table().orElseThrow().files()).hasSize(1);
        assertThat(requests).hasValue(1);
    }

    @Test
    void directMagnetNeedsNeitherConfigurationNorHttpAndHasNoTable() {
        ProwlarrProperties properties = new ProwlarrProperties();
        properties.setTimeout(Duration.ofSeconds(2));
        String magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567";
        ResolvedTorrent resolved = new ProwlarrClient(properties, new ObjectMapper())
                .resolveTorrent(release(magnet, null, null), Duration.ofSeconds(1));
        assertThat(resolved.magnet()).isEqualTo(magnet);
        assertThat(resolved.table()).isEmpty();
    }

    @Test
    void searchRetainsHashAndBackupReferenceWithoutApiKeyAndKeepsDirectMagnets() throws Exception {
        fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String hash = "0123456789abcdef0123456789abcdef01234567";
        String json = new ObjectMapper().writeValueAsString(java.util.List.of(
                Map.of("title", "sample", "indexerId", 1, "infoHash", hash.toUpperCase(),
                        "magnetUrl", "magnet:?xt=urn:btih:" + hash,
                        "downloadUrl", "http://127.0.0.1:" + fixture.getAddress().getPort() + "/1/download?apikey=secret&link=backup-ref"),
                Map.of("title", "magnet-only", "magnetUrl", "magnet:?xt=urn:btih:" + hash)));
        fixture.createContext("/api/v1/search", exchange -> {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        fixture.start();
        var releases = client().search("query");
        assertThat(releases).hasSize(2);
        assertThat(releases.get(0).infoHash()).isEqualTo(hash);
        assertThat(releases.get(0).torrentRef()).isEqualTo("backup-ref");
        assertThat(releases.get(0).downloadRef()).startsWith("magnet:");
        assertThat(releases.toString()).doesNotContain("secret", "apikey");
    }

    @Test void searchUsesConfiguredDeadlineRatherThanTorrentEightSecondLimit() throws Exception {
        fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/api/v1/search", exchange -> {
            try { Thread.sleep(8500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            byte[] bytes = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); } catch (java.io.IOException ignored) { }
        });
        fixture.start();
        var properties = new ProwlarrProperties();
        properties.setBaseUrl("http://127.0.0.1:" + fixture.getAddress().getPort());
        properties.setApiKey("fixture-key");
        properties.setTimeout(Duration.ofSeconds(12));
        assertThat(new ProwlarrClient(properties, new ObjectMapper()).search("movie")).isEmpty();
    }

    @Test
    void redirectLoopSpendsAtMostSixRequests() throws Exception {
        fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        fixture.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().add("Location", "/again");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        fixture.start();
        assertThatThrownBy(() -> client().resolveTorrent(release("ref", null, null), Duration.ofSeconds(3)))
                .isInstanceOfSatisfying(ProwlarrClientException.class,
                        failure -> assertThat(failure.getReason()).isEqualTo(ProwlarrClientException.Reason.MAGNET_RESOLUTION));
        assertThat(requests).hasValue(6);
    }

    @Test
    void boundedSubscriberRejectsOversizeChunkedBodyAsSafetyFailure() throws Exception {
        ProwlarrClient client = serve(new byte[TorrentMetadata.MAX_BYTES + 1], new AtomicInteger());
        assertThatThrownBy(() -> client.resolveTorrent(release("ref", null, null), Duration.ofSeconds(3)))
                .isInstanceOfSatisfying(ProwlarrClientException.class,
                        failure -> assertThat(failure.getReason()).isEqualTo(ProwlarrClientException.Reason.INVALID_RESPONSE));
    }

    @Test
    void deadlineIncludesSlowResponseBodyAndNoRetry() throws Exception {
        fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        fixture.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write('d');
                output.flush();
                try { Thread.sleep(500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                output.write('e');
            } catch (java.io.IOException ignored) { }
        });
        fixture.start();
        long start = System.nanoTime();
        assertThatThrownBy(() -> client().resolveTorrent(release("ref", null, null), Duration.ofMillis(100)))
                .isInstanceOfSatisfying(ProwlarrClientException.class,
                        failure -> assertThat(failure.getReason()).isEqualTo(ProwlarrClientException.Reason.MAGNET_RESOLUTION));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(450));
        assertThat(requests.get()).isLessThanOrEqualTo(1);
    }

    @Test
    void returnedTorrentHashMismatchIsNotAnUnavailableSource() throws Exception {
        ProwlarrClient client = serve(TorrentMetadataTest.torrent(TorrentMetadataTest.singleInfo("a.mkv", 1)), new AtomicInteger());
        assertThatThrownBy(() -> client.resolveTorrent(release("ref", "0000000000000000000000000000000000000000", null), Duration.ofSeconds(2)))
                .isInstanceOfSatisfying(ProwlarrClientException.class,
                        failure -> assertThat(failure.getReason()).isEqualTo(ProwlarrClientException.Reason.INVALID_RESPONSE));
    }

    @Test
    void unavailableBodyCanFallBackAndConflictingMagnetCannot() throws Exception {
        String hash = "0123456789abcdef0123456789abcdef01234567";
        ProwlarrClient client = serve("unavailable".getBytes(StandardCharsets.UTF_8), new AtomicInteger());
        assertThatThrownBy(() -> client.resolveTorrent(release("ref", hash, null), Duration.ofSeconds(2)))
                .isInstanceOfSatisfying(ProwlarrClientException.class,
                        failure -> assertThat(failure.getReason()).isEqualTo(ProwlarrClientException.Reason.MAGNET_RESOLUTION));
        assertThatThrownBy(() -> client.resolveTorrent(release("magnet:?xt=urn:btih:" + hash,
                "0000000000000000000000000000000000000000", null), Duration.ofSeconds(2)))
                .isInstanceOfSatisfying(ProwlarrClientException.class,
                        failure -> assertThat(failure.getReason()).isEqualTo(ProwlarrClientException.Reason.INVALID_RESPONSE));
    }

    @Test
    void rejectsMultipleConflictingXtInsteadOfTreatingIdentityAsUnknown() {
        ProwlarrProperties properties = new ProwlarrProperties();
        properties.setTimeout(Duration.ofSeconds(2));
        ProwlarrClient client = new ProwlarrClient(properties, new ObjectMapper());
        String magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"
                + "&xt=urn:btih:0000000000000000000000000000000000000000";
        assertThatThrownBy(() -> client.resolveTorrent(release(magnet, null, null), Duration.ofSeconds(1)))
                .isInstanceOfSatisfying(ProwlarrClientException.class,
                        failure -> assertThat(failure.getReason()).isEqualTo(ProwlarrClientException.Reason.INVALID_RESPONSE));
    }

    @Test
    void redirectToMagnetReturnsNoTableAndPureV2MagnetRemainsCompatible() throws Exception {
        fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567";
        fixture.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Location", magnet);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        fixture.start();
        ResolvedTorrent resolved = client().resolveTorrent(release("ref", null, null), Duration.ofSeconds(2));
        assertThat(resolved.magnet()).isEqualTo(magnet);
        assertThat(resolved.table()).isEmpty();
        String v2 = "magnet:?xt=urn:btmh:1220" + "a".repeat(64);
        assertThat(client().resolveMagnet(1, v2, "v2")).isEqualTo(v2);
    }

    @Test void externalTorrentUrlKeepsItsOwnAuthenticationAndIsNotWrappedAgain() throws Exception {
        fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] torrent = TorrentMetadataTest.torrent(TorrentMetadataTest.singleInfo("episode.mkv", 4));
        String url = "http://127.0.0.1:" + fixture.getAddress().getPort() + "/external-download?id=7&apikey=tracker-token";
        String json = new ObjectMapper().writeValueAsString(java.util.List.of(Map.of(
                "title", "sample", "indexerId", 1, "downloadUrl", url)));
        fixture.createContext("/api/v1/search", exchange -> {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        fixture.createContext("/external-download", exchange -> {
            assertThat(exchange.getRequestURI().getRawQuery()).contains("apikey=tracker-token");
            exchange.sendResponseHeaders(200, torrent.length);
            try (var out = exchange.getResponseBody()) { out.write(torrent); }
        });
        fixture.start();
        var release = client().search("query").get(0);
        assertThat(release.downloadRef()).startsWith("torrent-ref:").doesNotContain("tracker-token");
        assertThat(release.torrentRef()).isEqualTo(url);
        assertThat(release.toString()).doesNotContain("tracker-token");
        assertThat(client().resolveTorrent(release, Duration.ofSeconds(2)).table()).isPresent();
    }

    private ProwlarrClient serve(byte[] body, AtomicInteger requests) throws Exception {
        fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/1/download", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) { output.write(body); }
            catch (java.io.IOException ignored) { }
        });
        fixture.start();
        return client();
    }

    private ProwlarrClient client() {
        ProwlarrProperties properties = new ProwlarrProperties();
        properties.setBaseUrl("http://127.0.0.1:" + fixture.getAddress().getPort());
        properties.setApiKey("test-only");
        properties.setTimeout(Duration.ofSeconds(2));
        return new ProwlarrClient(properties, new ObjectMapper());
    }

    private ProwlarrRelease release(String reference, String hash, String backup) {
        return new ProwlarrRelease("sample", null, null, null, null, "indexer", null, 1, null, reference, hash, backup);
    }

    @Test
    void rejectsTrailingGarbageAsSafetyFailureThroughPublicResolution() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/1/download", exchange -> {
            byte[] body = "d4:infod6:lengthi1e4:name5:a.mkveeGARBAGE".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/x-bittorrent");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            ProwlarrProperties properties = new ProwlarrProperties();
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setApiKey("test-only");
            properties.setTimeout(Duration.ofSeconds(2));
            ProwlarrClient client = new ProwlarrClient(properties, new ObjectMapper());
            assertThatThrownBy(() -> client.resolveMagnet(1, "reference", "sample"))
                    .isInstanceOfSatisfying(ProwlarrClientException.class,
                            exception -> org.assertj.core.api.Assertions.assertThat(exception.getReason())
                                    .isEqualTo(ProwlarrClientException.Reason.INVALID_RESPONSE));
        } finally {
            server.stop(0);
        }
    }
}
