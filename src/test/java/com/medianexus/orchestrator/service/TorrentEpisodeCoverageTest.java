package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.common.exception.GlobalExceptionHandler;
import com.medianexus.orchestrator.controller.ReleaseEpisodeCoverageController;
import com.medianexus.orchestrator.dto.resources.response.ReleaseEpisodeCoverageResponse;
import com.medianexus.orchestrator.integration.pansou.PanSouMagnetIdentity;
import com.medianexus.orchestrator.integration.tmdb.TmdbClient;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TorrentEpisodeCoverageTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TmdbClient tmdb = mock(TmdbClient.class);
    private final HttpClient http = mock(HttpClient.class);
    private static final String INFO = "d4:name15:Show.S01E01.mkv6:lengthi100ee";

    @Test
    void hashesExactInfoBytesAndParsesMultiAndSingleFileMetadata() throws Exception {
        // 故意保留非排序字典，确保哈希针对原字节而非 Map 重编码。
        assertThat(TorrentEpisodeCoverage.readTorrent(torrent(INFO), identity(INFO)))
                .containsExactly(new TorrentEpisodeCoverage.TorrentFile("Show.S01E01.mkv", 100));
        String multi = "d4:name4:Show5:filesld6:lengthi100e4:pathl6:Season15:Show.S01E01.mkveeee";
        assertThat(TorrentEpisodeCoverage.readTorrent(torrent(multi), identity(multi)))
                .containsExactly(new TorrentEpisodeCoverage.TorrentFile("Show/Season/Show.S01E01.mkv", 100));
        assertThatThrownBy(() -> TorrentEpisodeCoverage.readTorrent(torrent(INFO), identity(multi)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("身份");
        assertThatThrownBy(() -> TorrentEpisodeCoverage.readTorrent(torrent(INFO + "x"), identity(INFO)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TorrentEpisodeCoverage.readTorrent(new byte[TorrentEpisodeCoverage.MAX_TORRENT_BYTES + 1], identity(INFO)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void countsUniqueMainEpisodesAndRejectsSpecialsUnknownAndConflictingSeasons() {
        try (TorrentEpisodeCoverage coverage = coverage()) {
            List<TorrentEpisodeCoverage.TorrentFile> files = new ArrayList<>();
            for (int i = 1; i <= 12; i++) files.add(file("Show/Show - %02d (BD 1080P).mkv".formatted(i)));
            files.addAll(List.of(file("Show/Show.S01E01.mp4"), file("Show/SPs/Show.S01E13.mkv"),
                    file("Show/extras/Show - 14.mkv"), file("Show/NCOP1.mkv"), file("Show/Show - 13 (OVA).mkv"),
                    file("Show/Show - 02.5.mkv"), file("Show/sample/Show.S01E14.mkv"), file("Show/Show - 14.srt")));
            assertThat(coverage.countEpisodes(files, 1, 12)).isEqualTo(new ReleaseEpisodeCoverageResponse("VERIFIED", 12, 12));
            assertThat(coverage.countEpisodes(List.of(file("Show.S01E01.mkv")), 1, 12).matched()).isEqualTo(1);
            assertThat(coverage.countEpisodes(List.of(file("Show.S01E13.mkv")), 1, 12).matched()).isZero();
            assertThat(coverage.countEpisodes(List.of(file("unidentifiable.mkv")), 1, 12).matched()).isNull();
            assertThat(coverage.countEpisodes(List.of(), 1, 12).status()).isEqualTo("UNVERIFIED");
            files.add(file("Show/Season 2/Show - 01.mkv"));
            assertThat(coverage.countEpisodes(files, 1, 12).status()).isEqualTo("UNVERIFIED");
        }
    }

    @Test
    void returnsImmediatelyDeduplicatesPendingAndCachesVerifiedWithoutMoreNetwork() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(tmdb.getTvSeasonDetails(1, 1, "zh-CN")).thenAnswer(call -> {
            started.countDown();
            assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
            return mapper.readTree("{\"season_number\":1,\"episodes\":[{\"episode_number\":1},{\"episode_number\":2}]}");
        });
        respond(torrent(INFO));
        try (TorrentEpisodeCoverage coverage = coverage()) {
            register(coverage, INFO, "nyaa-123");
            assertThat(coverage.check(magnet(INFO), 1, 1).status()).isEqualTo("CHECKING");
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(coverage.check(magnet(INFO), 1, 1).status()).isEqualTo("CHECKING");
            verifyNoInteractions(http);
            release.countDown();
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(coverage.check(magnet(INFO), 1, 1)).isEqualTo(new ReleaseEpisodeCoverageResponse("VERIFIED", 1, 2)));
            assertThat(coverage.check(magnet(INFO), 1, 1).matched()).isEqualTo(1);
            verify(http, times(1)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
            verify(tmdb, times(1)).getTvSeasonDetails(1, 1, "zh-CN");
        } finally {
            release.countDown();
        }
    }

    @Test
    void onlyRegisteredNyaaSourcesCanScheduleAndQueueIsBounded() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(tmdb.getTvSeasonDetails(any(Integer.class), eq(1), eq("zh-CN"))).thenAnswer(call -> {
            release.await(2, TimeUnit.SECONDS);
            throw new IllegalArgumentException("离线测试");
        });
        try (TorrentEpisodeCoverage coverage = coverage()) {
            register(coverage, INFO, "https://elsewhere.invalid/1");
            assertThat(coverage.check(magnet(INFO), 1, 1).status()).isEqualTo("UNVERIFIED");
            assertThat(coverage.check("https://nyaa.si/download/123.torrent", 1, 1).status()).isEqualTo("UNVERIFIED");
            verifyNoInteractions(tmdb, http);
            register(coverage, INFO, "nyaa-123");
            for (int i = 1; i <= 34; i++) assertThat(coverage.check(magnet(INFO), i, 1).status()).isEqualTo("CHECKING");
            assertThat(coverage.check(magnet(INFO), 35, 1).status()).isEqualTo("UNVERIFIED");
        } finally {
            release.countDown();
        }
    }

    @Test
    void oversizedStreamingBodyCancelsAndCachesUnverifiedRatherThanZero() throws Exception {
        when(tmdb.getTvSeasonDetails(1, 1, "zh-CN")).thenReturn(mapper.readTree("{\"season_number\":1,\"episodes\":[{\"episode_number\":1}]}"));
        AtomicBoolean cancelled = respond(new byte[TorrentEpisodeCoverage.MAX_TORRENT_BYTES + 1]);
        try (TorrentEpisodeCoverage coverage = coverage()) {
            register(coverage, INFO, "nyaa-123");
            assertThat(coverage.check(magnet(INFO), 1, 1).status()).isEqualTo("CHECKING");
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(coverage.check(magnet(INFO), 1, 1)).isEqualTo(new ReleaseEpisodeCoverageResponse("UNVERIFIED", null, null)));
            assertThat(cancelled).isTrue();
            coverage.check(magnet(INFO), 1, 1);
            verify(http, times(1)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        }
    }

    @Test
    void slowBodyTimesOutAndCancelsWithoutReportingZero() throws Exception {
        when(tmdb.getTvSeasonDetails(1, 1, "zh-CN")).thenReturn(mapper.readTree("{\"season_number\":1,\"episodes\":[{\"episode_number\":1}]}"));
        var stalled = new java.util.concurrent.CompletableFuture<HttpResponse<byte[]>>();
        when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(stalled);
        try (TorrentEpisodeCoverage coverage = coverage()) {
            register(coverage, INFO, "nyaa-123");
            assertThat(coverage.check(magnet(INFO), 1, 1).status()).isEqualTo("CHECKING");
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(coverage.check(magnet(INFO), 1, 1)).isEqualTo(new ReleaseEpisodeCoverageResponse("UNVERIFIED", null, null)));
            assertThat(stalled.isCancelled()).isTrue();
        }
    }

    @Test
    void missingSeasonEpisodesFallsBackOnlyToMatchingSeasonCount() throws Exception {
        when(tmdb.getTvSeasonDetails(1, 1, "zh-CN")).thenReturn(mapper.readTree("{\"season_number\":2,\"episodes\":[{\"episode_number\":1}]}"));
        when(tmdb.getTvDetails(1, "zh-CN")).thenReturn(mapper.readTree("{\"seasons\":[{\"season_number\":2,\"episode_count\":99},{\"season_number\":1,\"episode_count\":12}]}"));
        respond(torrent(INFO));
        try (TorrentEpisodeCoverage coverage = coverage()) {
            register(coverage, INFO, "nyaa-123");
            coverage.check(magnet(INFO), 1, 1);
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(coverage.check(magnet(INFO), 1, 1).expected()).isEqualTo(12));
        }
    }

    @Test
    void endpointAuthenticatesAndValidatesPositiveIdsWithinApiResponse() throws Exception {
        AuthService auth = mock(AuthService.class);
        TorrentEpisodeCoverage coverage = mock(TorrentEpisodeCoverage.class);
        when(coverage.check(any(), eq(1), eq(1))).thenReturn(new ReleaseEpisodeCoverageResponse("CHECKING", null, null));
        var mvc = MockMvcBuilders.standaloneSetup(new ReleaseEpisodeCoverageController(auth, coverage))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/api/v1/resources/releases/episode-coverage").contentType("application/json")
                        .content("{\"download_ref\":\"magnet:test\",\"tmdb_id\":1,\"season_number\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CHECKING"));
        verify(auth).requireCurrentUser();
        mvc.perform(post("/api/v1/resources/releases/episode-coverage").contentType("application/json")
                        .content("{\"download_ref\":\"magnet:test\",\"tmdb_id\":0,\"season_number\":1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").exists());
    }

    private TorrentEpisodeCoverage coverage() { return new TorrentEpisodeCoverage(tmdb, new MovieSeriesFileRenameService(), http); }
    private static TorrentEpisodeCoverage.TorrentFile file(String path) { return new TorrentEpisodeCoverage.TorrentFile(path, 100); }
    private static byte[] torrent(String info) { return ("d4:info" + info + "e").getBytes(StandardCharsets.UTF_8); }
    private static PanSouMagnetIdentity identity(String info) throws Exception {
        return new PanSouMagnetIdentity(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(info.getBytes(StandardCharsets.UTF_8))));
    }
    private static String magnet(String info) throws Exception { return "magnet:?xt=urn:btih:" + identity(info).btih(); }
    private void register(TorrentEpisodeCoverage coverage, String info, String id) throws Exception {
        coverage.registerSources(mapper.readTree("[{\"unique_id\":\"" + id + "\",\"links\":[{\"type\":\"magnet\",\"url\":\"" + magnet(info) + "\"}]}]"));
    }

    @SuppressWarnings("unchecked")
    private AtomicBoolean respond(byte[] bytes) {
        AtomicBoolean cancelled = new AtomicBoolean();
        when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
            HttpRequest request = call.getArgument(0);
            assertThat(request.uri().toString()).isEqualTo("https://nyaa.si/download/123.torrent");
            assertThat(request.timeout()).contains(Duration.ofSeconds(3));
            HttpResponse.BodyHandler<byte[]> handler = call.getArgument(1);
            HttpResponse.ResponseInfo headers = mock(HttpResponse.ResponseInfo.class);
            when(headers.statusCode()).thenReturn(200);
            when(headers.headers()).thenReturn(HttpHeaders.of(Map.of(), (key, value) -> true));
            HttpResponse.BodySubscriber<byte[]> body = handler.apply(headers);
            body.onSubscribe(new Flow.Subscription() {
                public void request(long count) { }
                public void cancel() { cancelled.set(true); }
            });
            body.onNext(List.of(ByteBuffer.wrap(bytes)));
            if (!cancelled.get()) body.onComplete();
            return body.getBody().thenApply(content -> {
                HttpResponse<byte[]> response = mock(HttpResponse.class);
                when(response.statusCode()).thenReturn(200);
                when(response.body()).thenReturn(content);
                return response;
            }).toCompletableFuture();
        });
        return cancelled;
    }
}
