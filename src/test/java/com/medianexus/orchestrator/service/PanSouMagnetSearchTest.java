package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.PanSouProperties;
import com.medianexus.orchestrator.dto.resources.request.MovieReleaseRecommendationRequest;
import com.medianexus.orchestrator.dto.resources.request.MovieReleaseSearchRequest;
import com.medianexus.orchestrator.dto.resources.request.SeriesReleaseRecommendationRequest;
import com.medianexus.orchestrator.dto.resources.request.SeriesReleaseSearchRequest;
import com.medianexus.orchestrator.integration.pansou.PanSouClient;
import com.medianexus.orchestrator.integration.pansou.PanSouClientException;
import com.medianexus.orchestrator.integration.pansou.PanSouMagnetIdentity;
import com.medianexus.orchestrator.integration.pansou.PanSouSearchCommand;
import com.medianexus.orchestrator.integration.pansou.PanSouSearchEntry;
import com.medianexus.orchestrator.integration.pansou.PanSouSearchResult;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PanSouMagnetSearchTest {

    private static final String MAGNET = "magnet:?xt=urn:btih:" + "00".repeat(20);
    private static final String BASE32 = "magnet:?xt=urn:btih:" + "A".repeat(32);
    private final PanSouClient client = mock(PanSouClient.class);
    private final PanSouMagnetSearch search = new PanSouMagnetSearch(client, new ReleaseTitleTagParser());

    @Test
    void supplementsUnqualifiedResultsAndMergesBtihWithoutInventingMetrics() {
        List<PanSouSearchCommand> calls = new CopyOnWriteArrayList<>();
        when(client.searchMagnets(any())).thenAnswer(invocation -> {
            PanSouSearchCommand command = invocation.getArgument(0);
            calls.add(command);
            if (command.keyword().equals("蜘蛛侠 纵横宇宙")) {
                return new PanSouSearchResult(List.of(
                        entry(BASE32, "蜘蛛侠 纵横宇宙 1080p", "tg:channel"),
                        entry(MAGNET + "&tr=https%3A%2F%2Ftracker.test", "蜘蛛侠：纵横宇宙 2023 1080p", "clxiong")));
            }
            return new PanSouSearchResult(List.of(entry(MAGNET, "蜘蛛侠 2023 1080p OST", "nyaa")));
        });
        var response = search.refreshRecommendMovie(new MovieReleaseRecommendationRequest(
                1, null, "蜘蛛侠：纵横宇宙", "Spider-Man: Across the Spider-Verse", 2023, "2160p"));
        assertThat(response.selectedQuality()).isEqualTo("1080p");
        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.title()).isEqualTo("蜘蛛侠：纵横宇宙 2023 1080p");
            assertThat(item.downloadRef()).startsWith(MAGNET);
            assertThat(item.indexerId()).isNull();
            assertThat(item.size()).isNull();
            assertThat(item.seeders()).isNull();
            assertThat(item.indexer()).contains("tg:channel", "clxiong");
            assertThat(item.matchSource()).contains("未知", "同 hash 标题");
        });
        assertThat(calls).extracting(PanSouSearchCommand::keyword)
                .contains("蜘蛛侠：纵横宇宙", "Spider-Man: Across the Spider-Verse", "蜘蛛侠 纵横宇宙");
        assertThat(calls).allMatch(PanSouSearchCommand::refresh).hasSize(3);
        assertThat(PanSouMagnetIdentity.fromUrl(BASE32)).isEqualTo(PanSouMagnetIdentity.fromUrl(MAGNET));
        assertThat(PanSouMagnetIdentity.fromUrl("magnet:?xt=urn:btih:broken")).isEmpty();
    }

    @Test
    void keepsChineseAnimeBatchesForSelectionAndRejectsBooksAndMusic() {
        String[][] samples = {
                {"世界顶尖的暗杀者，转生为异世界贵族", "世界最高の暗殺者、異世界貴族に転生する",
                        "[DBD-Raws][世界顶尖的暗杀者转生为异世界贵族/世界最高の暗殺者、異世界貴族に転生する][01-12TV全集][1080P][BDRip]"},
                {"2.5次元的诱惑", "2.5次元の誘惑",
                        "[LoliHouse] 2.5次元的诱惑 / 2.5-jigen no Ririsa [01-24 合集][WebRip 1080p][简繁内封字幕][Fin]"},
                {"工作细胞BLACK", "はたらく細胞BLACK",
                        "【喵萌奶茶屋】[工作细胞BLACK / Hataraku Saibou Black][01-13][BDRip][1080p][简体][v2]"}
        };
        for (String[] sample : samples) {
            when(client.searchMagnets(any())).thenReturn(new PanSouSearchResult(List.of(
                    entry(magnet(1), sample[2], "plugin:nyaa"),
                    entry(magnet(2), "[Novel] " + sample[1] + " 第01-12巻 EPUB", "plugin:nyaa"),
                    entry(magnet(3), sample[1] + " ORIGINAL SOUNDTRACK MP3 320K", "plugin:nyaa"))));
            assertThatThrownBy(() -> search.recommendSeries(new SeriesReleaseRecommendationRequest(
                    null, null, null, sample[0], sample[1], 1, "1080p")))
                    .isInstanceOfSatisfying(ProwlarrReleaseIngestService.NoMatchingReleaseException.class, failure -> {
                        assertThat(failure.candidates()).singleElement().satisfies(item -> {
                            assertThat(item.title()).isEqualTo(sample[2]);
                            assertThat(item.resolutionTags()).contains("1080p");
                            assertThat(item.matchSource()).contains("整包线索");
                        });
                    });
        }
        assertThat(new ReleaseTitleTagParser().parse("Show BD 1920x1080p AVC").resolutionTags()).containsExactly("1080p");
        assertThat(new ReleaseTitleTagParser().parse("Show BD 1920x1080 AVC").resolutionTags()).containsExactly("1080p");
    }

    @Test
    void recommendsOnlyExplicitTargetSeasonPacksEvenWithUnknownSeeders() {
        when(client.searchMagnets(any())).thenReturn(new PanSouSearchResult(List.of(
                entry(magnet(1), "Show S02E01 1080p Complete", "nyaa"),
                entry(magnet(2), "Show S01 Batch 1080p", "nyaa"),
                entry(magnet(3), "Show Batch 1080p", "tg:test"),
                entry(magnet(4), "Show 2 Batch 1080p", "tg:test"),
                entry(magnet(5), "Show S01-03 Complete 1080p", "tg:test"),
                entry(magnet(7), "Show S02 - 24 [1080p][Fin]", "nyaa"),
                entry(magnet(8), "Show S02 [24][1080p][Complete]", "nyaa"),
                entry(magnet(6), "Show S02 01-12 1080p", "nyaa"))));
        List<ProwlarrReleaseIngestService.ReleaseSearchProgress> progress = new ArrayList<>();
        var recommendation = search.recommendSeries(series("1080p"), progress::add);
        assertThat(recommendation.items()).singleElement().satisfies(item -> {
            assertThat(item.downloadRef()).isEqualTo(magnet(6));
            assertThat(item.seeders()).isNull();
            assertThat(item.seasonTags()).containsExactly("S02");
        });
        assertThat(progress).extracting(ProwlarrReleaseIngestService.ReleaseSearchProgress::status)
                .contains("RUNNING", "SUCCEEDED");
        var manual = search.searchSeries(new SeriesReleaseSearchRequest(1, null, null, "Show", null, 2, "1080p"));
        assertThat(manual.items()).anySatisfy(item -> {
            assertThat(item.downloadRef()).isEqualTo(magnet(3));
            assertThat(item.seasonTags()).isEmpty();
            assertThat(item.matchSource()).contains("季度未知");
        });
    }

    @Test
    void separatesManualEvidenceNoMatchAndPartialFailure() {
        when(client.searchMagnets(any())).thenReturn(new PanSouSearchResult(List.of(
                entry(MAGNET, "Show S02E01 1080p", "nyaa"))));
        assertThatThrownBy(() -> search.recommendSeries(series("1080p")))
                .isInstanceOfSatisfying(ProwlarrReleaseIngestService.NoMatchingReleaseException.class,
                        failure -> assertThat(failure.reasonCode()).isEqualTo("SEASON_UNKNOWN"));
        when(client.searchMagnets(any())).thenReturn(new PanSouSearchResult(List.of()));
        assertThatThrownBy(() -> search.recommendSeries(series("1080p")))
                .isInstanceOfSatisfying(ProwlarrReleaseIngestService.NoMatchingReleaseException.class,
                        failure -> assertThat(failure.reasonCode()).isEqualTo("NO_RELEASE"));
        when(client.searchMagnets(any())).thenAnswer(invocation -> {
            PanSouSearchCommand command = invocation.getArgument(0);
            if (command.keyword().equals("原名")) {
                throw new PanSouClientException(PanSouClientException.Reason.TIMEOUT, "搜索超时");
            }
            return new PanSouSearchResult(List.of(entry(MAGNET, "Show Batch 1080p", "nyaa")));
        });
        assertThatThrownBy(() -> search.recommendSeries(new SeriesReleaseRecommendationRequest(
                1, null, null, "Show", "原名", 2, "1080p")))
                .isInstanceOfSatisfying(PanSouClientException.class,
                        failure -> assertThat(failure.getReason()).isEqualTo(PanSouClientException.Reason.TIMEOUT));
    }

    @Test
    void returnsQualifiedBatchWithoutWaitingForOtherTitleOrSupplement() {
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        List<PanSouSearchCommand> calls = new CopyOnWriteArrayList<>();
        when(client.searchMagnets(any())).thenAnswer(invocation -> {
            PanSouSearchCommand command = invocation.getArgument(0);
            calls.add(command);
            if (command.keyword().equals("原名")) {
                slowStarted.countDown();
                releaseSlow.await(5, TimeUnit.SECONDS);
                return new PanSouSearchResult(List.of());
            }
            assertThat(slowStarted.await(1, TimeUnit.SECONDS)).isTrue();
            return new PanSouSearchResult(List.of(entry(MAGNET, "Show S02 Complete 1080p", "nyaa")));
        });
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                var response = search.recommendSeries(new SeriesReleaseRecommendationRequest(
                        1, null, null, "Show", "原名", 2, "1080p"));
                assertThat(response.item().downloadRef()).isEqualTo(MAGNET);
            });
            assertThat(calls).extracting(PanSouSearchCommand::keyword).containsExactlyInAnyOrder("Show", "原名");
        } finally {
            releaseSlow.countDown();
        }
    }

    @Test
    void preservesQualifiedResultAfterOtherTitleFailedAndRejectsWrongMovieVersion() {
        when(client.searchMagnets(any())).thenAnswer(invocation -> {
            PanSouSearchCommand command = invocation.getArgument(0);
            if (command.keyword().equals("原名")) throw new PanSouClientException(PanSouClientException.Reason.UPSTREAM, "失败");
            return new PanSouSearchResult(List.of(
                    entry(magnet(1), "Movie 2 2023 1080p", "clxiong"),
                    entry(magnet(2), "Movie 1999 1080p", "clxiong"),
                    entry(magnet(3), "Movie 2023 1080p", "clxiong")));
        });
        var response = search.searchMovie(new MovieReleaseSearchRequest(1, null, "Movie", "原名", 2023, "1080p"));
        assertThat(response.items()).singleElement().satisfies(item -> assertThat(item.downloadRef()).isEqualTo(magnet(3)));
    }

    @Test
    void rejectsDatedSingleEpisodesAndDifferentChineseTitlesButKeepsNumbersInMovieNames() {
        when(client.searchMagnets(any())).thenReturn(new PanSouSearchResult(List.of(
                entry(magnet(1), "作品 S02E01 2026-01-12 1080p", "nyaa"),
                entry(magnet(2), "另一作品 S02 Complete 1080p", "nyaa"),
                entry(magnet(3), "作品 S02E01 01-12 1080p", "nyaa"))));
        assertThatThrownBy(() -> search.recommendSeries(new SeriesReleaseRecommendationRequest(
                1, null, null, "作品", null, 2, "1080p")))
                .isInstanceOf(ProwlarrReleaseIngestService.NoMatchingReleaseException.class);
        when(client.searchMagnets(any())).thenReturn(new PanSouSearchResult(List.of(
                entry(magnet(4), "Blade Runner 2049 2017 1080p", "clxiong"))));
        assertThat(search.recommendMovie(new MovieReleaseRecommendationRequest(
                1, null, "Blade Runner 2049", null, 2017, "1080p")).item().downloadRef()).isEqualTo(magnet(4));
    }

    @Test
    void clientDistinguishesValidEmptyMergeFromBadResponsesAndKeepsMagnetCategory() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<String> response = new AtomicReference<>("{\"data\":{\"merged_by_type\":{}}}");
        List<JsonNode> payloads = new CopyOnWriteArrayList<>();
        AtomicReference<String> originalTitleResponse = new AtomicReference<>();
        AtomicInteger logins = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/auth/login", exchange -> {
            logins.incrementAndGet();
            byte[] bytes = "{\"token\":\"test\",\"expires_at\":4102444800}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/api/search", exchange -> {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            payloads.add(request);
            String body = originalTitleResponse.get() != null && request.path("kw").asText().equals("世界最高の暗殺者、異世界貴族に転生する")
                    ? originalTitleResponse.get() : response.get();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        PanSouProperties properties = new PanSouProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setUsername("test");
        properties.setPassword("test");
        properties.setTimeout(Duration.ofSeconds(2));
        try (PanSouClient actualClient = new PanSouClient(properties, mapper)) {
            assertThat(actualClient.searchMagnets(new PanSouSearchCommand("Show", false)).entries()).isEmpty();
            response.set("{\"merged_by_type\":{\"magnet\":[]}}");
            assertThat(actualClient.searchMagnets(new PanSouSearchCommand("Show", true)).entries()).isEmpty();
            response.set(mapper.writeValueAsString(java.util.Map.of("data", java.util.Map.of("merged_by_type", java.util.Map.of(
                    "magnet", List.of(java.util.Map.of("url", MAGNET, "note", "Show S02 Batch 1080p", "source", "nyaa")),
                    "quark", List.of(java.util.Map.of("url", "https://pan.quark.cn/s/example")))))));
            assertThat(actualClient.searchMagnets(new PanSouSearchCommand("Show", false)).entries()).singleElement()
                    .satisfies(entry -> assertThat(entry.url()).isEqualTo(MAGNET));
            for (String invalid : List.of("{}", "null", "{\"data\":null}",
                    "{\"success\":false,\"merged_by_type\":{}}", "{\"merged_by_type\":{\"magnet\":{}}}",
                    "{\"merged_by_type\":{\"magnet\":[{\"type\":\"quark\",\"url\":\"" + MAGNET + "\"}]}}")) {
                response.set(invalid);
                assertThatThrownBy(() -> actualClient.searchMagnets(new PanSouSearchCommand("Show", false)))
                        .isInstanceOfSatisfying(PanSouClientException.class,
                                failure -> assertThat(failure.getReason()).isEqualTo(PanSouClientException.Reason.INVALID_RESPONSE));
            }
            assertThat(logins).hasValue(1);
            assertThat(payloads).allSatisfy(payload -> {
                assertThat(payload.path("cloud_types")).extracting(JsonNode::asText).containsExactly("magnet");
                assertThat(payload.path("res").asText()).isEqualTo("merge");
                assertThat(payload.path("plugins")).extracting(JsonNode::asText).containsExactly("nyaa", "clxiong");
            });
            assertThat(payloads.get(1).path("refresh").asBoolean()).isTrue();
            response.set("{\"code\":200,\"message\":\"success\",\"data\":{\"total\":0}}");
            assertThat(actualClient.searchMagnets(new PanSouSearchCommand("空查询", false)).entries()).isEmpty();
            response.set("{\"code\":200,\"data\":{\"total\":32}}");
            assertThatThrownBy(() -> actualClient.searchMagnets(new PanSouSearchCommand("坏响应", false)))
                    .isInstanceOfSatisfying(PanSouClientException.class,
                            failure -> assertThat(failure.getReason()).isEqualTo(PanSouClientException.Reason.INVALID_RESPONSE));
            response.set("{\"code\":200,\"message\":\"success\",\"data\":{\"total\":0}}");
            originalTitleResponse.set(mapper.writeValueAsString(java.util.Map.of("code", 200, "data", java.util.Map.of(
                    "total", 2, "merged_by_type", java.util.Map.of("magnet", List.of(
                            java.util.Map.of("url", magnet(1), "source", "plugin:nyaa", "note",
                                    "[DBD-Raws][世界顶尖的暗杀者转生为异世界贵族/世界最高の暗殺者、異世界貴族に転生する][01-12TV全集][1080P][BDRip]"),
                            java.util.Map.of("url", magnet(2), "source", "plugin:nyaa", "note",
                                    "[UCCUSS] 世界最高の暗殺者、異世界貴族に転生する 全12話 (BD 1920x1080p AVC FLAC)")))))));
            var realSearch = new PanSouMagnetSearch(actualClient, new ReleaseTitleTagParser());
            assertThatThrownBy(() -> realSearch.recommendSeries(new SeriesReleaseRecommendationRequest(
                    null, null, null, "世界顶尖的暗杀者，转生为异世界贵族", "世界最高の暗殺者、異世界貴族に転生する", 1, "1080p")))
                    .isInstanceOfSatisfying(ProwlarrReleaseIngestService.NoMatchingReleaseException.class, failure -> {
                        assertThat(failure.reasonCode()).isEqualTo("SEASON_UNKNOWN");
                        assertThat(failure.candidates()).hasSize(2);
                        assertThat(failure.candidates()).allSatisfy(item -> assertThat(item.matchSource()).contains("季度未知"));
                    });
        } finally {
            server.stop(0);
        }
    }

    private SeriesReleaseRecommendationRequest series(String quality) {
        return new SeriesReleaseRecommendationRequest(1, null, null, "Show", null, 2, quality);
    }

    private PanSouSearchEntry entry(String magnet, String title, String source) {
        return new PanSouSearchEntry(magnet, "", title, null, source);
    }

    private String magnet(int number) {
        return "magnet:?xt=urn:btih:" + String.format("%040x", number);
    }
}
