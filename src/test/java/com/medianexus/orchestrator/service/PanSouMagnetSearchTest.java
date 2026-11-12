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
    private final PanSouMagnetSearch search = new PanSouMagnetSearch(client, new ReleaseTitleTagParser(), packProperties());

    private static com.medianexus.orchestrator.config.PanSouProperties packProperties() {
        var properties = new com.medianexus.orchestrator.config.PanSouProperties();
        properties.setSeasonPack720MinBytes(1610612736L);
        properties.setSeasonPack1080MinBytes(3221225472L);
        properties.setSeasonPack2160MinBytes(6442450944L);
        return properties;
    }

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
            assertThat(search.recommendSeries(new SeriesReleaseRecommendationRequest(
                    null, null, null, sample[0], sample[1], 1, "1080p")).items()).singleElement().satisfies(item -> {
                        assertThat(item.title()).isEqualTo(sample[2]);
                        assertThat(item.resolutionTags()).contains("1080p");
                        assertThat(item.reviewFlags()).doesNotContain("SEASON_UNKNOWN");
                    });
        }
        assertThat(new ReleaseTitleTagParser().parse("Show BD 1920x1080p AVC").resolutionTags()).containsExactly("1080p");
        assertThat(new ReleaseTitleTagParser().parse("Show BD 1920x1080 AVC").resolutionTags()).containsExactly("1080p");
    }

    @Test
    void ranksLargerOnlyAfterEvidenceAndUsesBangumiTitleOnlyAsFallback() {
        List<String> calls = new CopyOnWriteArrayList<>();
        when(client.searchMagnets(any())).thenAnswer(call -> {
            String query = call.<PanSouSearchCommand>getArgument(0).keyword(); calls.add(query);
            if (!query.equals("雾尾粉丝后援会")) return new PanSouSearchResult(List.of());
            return new PanSouSearchResult(List.of(
                    new PanSouSearchEntry(magnet(1), "", "雾尾粉丝后援会 [01-12 合集][1080p][简繁字幕]", null, "nyaa", 100L),
                    new PanSouSearchEntry(magnet(2), "", "雾尾粉丝后援会 [01-12 合集][1080p][简繁字幕]", null, "nyaa", 300L),
                    new PanSouSearchEntry(magnet(3), "", "雾尾粉丝后援会 [01-12 合集][720p][简繁字幕]", null, "nyaa", 900L),
                    entry(magnet(4), "雾尾粉丝后援会 [01-12 合集][1080p][简繁字幕]", "nyaa")));
        });
        var request = new SeriesReleaseRecommendationRequest(null, null, null, "雾尾粉丝俱乐部", "霧尾ファンクラブ", 1, "1080p");
        var fallback = search.recommendSeries(request, "雾尾粉丝后援会", progress -> {});
        assertThat(fallback.items()).extracting("downloadRef").containsExactly(magnet(2), magnet(1), magnet(4));
        assertThat(fallback.item().size()).isEqualTo(300L);
        assertThat(fallback.item().matchQuery()).contains("雾尾粉丝后援会");
        assertThat(calls.subList(0, 2)).containsExactlyInAnyOrder("雾尾粉丝俱乐部", "霧尾ファンクラブ");
        assertThat(calls).filteredOn("雾尾粉丝后援会"::equals).hasSize(1);
        calls.clear();
        org.mockito.Mockito.reset(client);
        when(client.searchMagnets(any())).thenAnswer(call -> {
            calls.add(call.<PanSouSearchCommand>getArgument(0).keyword());
            return new PanSouSearchResult(List.of(entry(magnet(1), "雾尾粉丝俱乐部 S01 Complete 1080p", "nyaa")));
        });
        assertThat(search.recommendSeries(request, "雾尾粉丝后援会", progress -> {}).item().downloadRef()).isEqualTo(magnet(1));
        assertThat(calls).doesNotContain("雾尾粉丝后援会");
    }

    @Test
    void preservesSizeConflictAcrossQueryBatches() {
        when(client.searchMagnets(any())).thenAnswer(call -> {
            String query = call.<PanSouSearchCommand>getArgument(0).keyword();
            return new PanSouSearchResult(List.of(new PanSouSearchEntry(magnet(1), "", "Show Batch 1080p", null,
                    "nyaa", query.equals("Show") ? null : 400L, query.equals("Show"))));
        });
        assertThatThrownBy(() -> search.recommendSeries(new SeriesReleaseRecommendationRequest(
                null, null, null, "Show", "Original", 2, "1080p")))
                .isInstanceOfSatisfying(ProwlarrReleaseIngestService.NoMatchingReleaseException.class,
                        failure -> assertThat(failure.candidates()).singleElement().satisfies(item -> assertThat(item.size()).isNull()));
    }

    @Test
    void allowsCapacityHeuristicAndQualityUpgradeWithoutConsultingFileCoverage() {
        when(client.searchMagnets(any())).thenReturn(new PanSouSearchResult(List.of(
                new PanSouSearchEntry(magnet(1), "", "Show S1 [BDRip 1080p x265 OPUS]", null, "nyaa", 4_831_838_208L),
                new PanSouSearchEntry(magnet(2), "", "Show S1 - 01 [BDRip 1080p]", null, "nyaa", 12_000_000_000L),
                new PanSouSearchEntry(magnet(3), "", "Show S1 [BDRip 1080p]", null, "nyaa", 500_000_000L))));
        var first = new SeriesReleaseRecommendationRequest(null, null, null, "Show", null, 1, "1080p");
        assertThat(search.recommendSeries(first).items()).singleElement().satisfies(item -> {
            assertThat(item.downloadRef()).isEqualTo(magnet(1));
            assertThat(item.matchSource()).contains("容量符合整包特征");
        });
        when(client.searchMagnets(any())).thenReturn(new PanSouSearchResult(List.of(
                entry(magnet(4), "Show [01-24 Fin][4K]", "nyaa"))));
        assertThat(search.recommendSeries(first).selectedQuality()).isEqualTo("2160p");
        when(client.searchMagnets(any())).thenReturn(new PanSouSearchResult(List.of(
                entry(magnet(5), "Show [01-24 Fin][4K]", "nyaa"),
                entry(magnet(6), "Show [01-12 合集][1080p]", "nyaa"))));
        assertThat(search.recommendSeries(first).selectedQuality()).isEqualTo("1080p");
        when(client.searchMagnets(any())).thenReturn(new PanSouSearchResult(List.of(
                entry(magnet(7), "Movie BluRay 1080p", "nyaa"))));
        assertThatThrownBy(() -> search.recommendMovie(new MovieReleaseRecommendationRequest(
                null, null, "Movie", null, 2025, "1080p")))
                .isInstanceOf(ProwlarrReleaseIngestService.NoMatchingReleaseException.class);
    }

    @Test
    void rejectsExplicitWrongCourAndLargeNxEpisode() {
        var request = new SeriesReleaseRecommendationRequest(null, null, null, "Show", null, 1, "1080p");
        for (String title : List.of("Show 第2期 [01-12][1080p]", "Show 1x01 [BDRip 1080p]")) {
            when(client.searchMagnets(any())).thenReturn(new PanSouSearchResult(List.of(
                    new PanSouSearchEntry(magnet(1), "", title, null, "nyaa", 4_294_967_296L))));
            assertThatThrownBy(() -> search.recommendSeries(request))
                    .isInstanceOf(ProwlarrReleaseIngestService.NoMatchingReleaseException.class);
        }
    }

    @Test
    void waitsForStartedTitleQueryBeforeUpgradingQuality() throws Exception {
        CountDownLatch upgradeAccepted = new CountDownLatch(1);
        CountDownLatch releaseExact = new CountDownLatch(1);
        when(client.searchMagnets(any())).thenAnswer(call -> {
            String query = call.<PanSouSearchCommand>getArgument(0).keyword();
            if (query.equals("Original")) {
                assertThat(releaseExact.await(2, TimeUnit.SECONDS)).isTrue();
                return new PanSouSearchResult(List.of(entry(magnet(2), "Show Batch 1080p", "nyaa")));
            }
            return new PanSouSearchResult(List.of(entry(magnet(1), "Show Batch 4K", "nyaa")));
        });
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> search.recommendSeries(new SeriesReleaseRecommendationRequest(
                    null, null, null, "Show", "Original", 1, "1080p"), progress -> {
                        if (progress.query().equals("Show") && progress.status().equals("SUCCEEDED")) upgradeAccepted.countDown();
                    }));
            assertThat(upgradeAccepted.await(1, TimeUnit.SECONDS)).isTrue();
            releaseExact.countDown();
            assertThat(future.get(2, TimeUnit.SECONDS).selectedQuality()).isEqualTo("1080p");
        } finally {
            releaseExact.countDown();
            executor.shutdownNow();
        }
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
            assertThat(item.reviewFlags()).containsExactly("SUBTITLES_UNMARKED");
        });
        assertThat(progress).extracting(ProwlarrReleaseIngestService.ReleaseSearchProgress::status)
                .contains("RUNNING", "SUCCEEDED");
        var manual = search.searchSeries(new SeriesReleaseSearchRequest(1, null, null, "Show", null, 2, "1080p"));
        assertThat(manual.items()).anySatisfy(item -> {
            assertThat(item.downloadRef()).isEqualTo(magnet(3));
            assertThat(item.seasonTags()).isEmpty();
            assertThat(item.matchSource()).contains("季度未知");
            assertThat(item.reviewFlags()).containsExactly("SEASON_UNKNOWN", "SUBTITLES_UNMARKED");
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
    void weComWaitsForAnotherTitleBeforeFallingBackTo1080p() {
        var first = new CountDownLatch(1);
        when(client.searchMagnets(any())).thenAnswer(invocation -> {
            PanSouSearchCommand query = invocation.getArgument(0);
            if (query.keyword().equals("Show")) {
                first.countDown();
                return new PanSouSearchResult(List.of(entry(magnet(1), "Show S02 Complete 1080p", "nyaa")));
            }
            assertThat(first.await(1, TimeUnit.SECONDS)).isTrue();
            return new PanSouSearchResult(List.of(entry(magnet(2), "Show S02 Complete 2160p", "nyaa")));
        });
        var result = search.recommendPreferredSeries(new SeriesReleaseRecommendationRequest(
                1, null, null, "Show", "原名", 2, "2160p"), ignored -> {});
        assertThat(result.item().downloadRef()).isEqualTo(magnet(2));
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
            List<java.util.Map<String, Object>> raw = new ArrayList<>();
            List<java.util.Map<String, Object>> merged = new ArrayList<>();
            String[] values = {"大小: 1 GiB", "Size: 1 GB", "大小: -1 GiB", "Size: 999999999999999999999 TB", "大小: 0 B"};
            for (int i = 0; i < values.length; i++) {
                raw.add(java.util.Map.of("content", values[i], "links", List.of(java.util.Map.of("type", "magnet", "url", magnet(i+1)))));
                merged.add(java.util.Map.of("url", magnet(i+1), "note", "Show S02 Batch 1080p"));
            }
            raw.add(java.util.Map.of("content", "大小: 2 GiB", "links", List.of(java.util.Map.of("type", "magnet", "url", magnet(6)))));
            raw.add(java.util.Map.of("content", "大小: 3 GiB", "links", List.of(java.util.Map.of("type", "magnet", "url", magnet(6)))));
            merged.add(java.util.Map.of("url", magnet(6), "note", "Show S02 Batch 1080p"));
            raw.add(java.util.Map.of("content", "大小: 4 GiB", "links", List.of(
                    java.util.Map.of("type", "magnet", "url", magnet(7)), java.util.Map.of("type", "magnet", "url", magnet(8)))));
            merged.add(java.util.Map.of("url", magnet(7), "note", "Show S02 Batch 1080p"));
            java.util.Collections.reverse(raw);
            response.set(mapper.writeValueAsString(java.util.Map.of("data", java.util.Map.of("results", raw,
                    "merged_by_type", java.util.Map.of("magnet", merged)))));
            int previousRequests = payloads.size();
            var sized = actualClient.searchMagnets(new PanSouSearchCommand("Show", false)).entries();
            assertThat(sized).extracting(PanSouSearchEntry::sizeBytes).containsExactly(1_073_741_824L, 1_000_000_000L, null, null, 0L, null, null);
            assertThat(payloads).hasSize(previousRequests + 1);
            assertThat(sized.get(5).sizeConflict()).isTrue();
            assertThat(logins).hasValue(1);
            assertThat(payloads).allSatisfy(payload -> {
                assertThat(payload.path("cloud_types")).extracting(JsonNode::asText).containsExactly("magnet");
                assertThat(payload.path("res").asText()).isEqualTo("all");
                assertThat(payload.path("src").asText("all")).isEqualTo("all");
                assertThat(payload.has("plugins")).isFalse();
                assertThat(payload.has("channels")).isFalse();
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
            var realSearch = new PanSouMagnetSearch(actualClient, new ReleaseTitleTagParser(), packProperties());
            var batch = realSearch.recommendSeries(new SeriesReleaseRecommendationRequest(
                    null, null, null, "世界顶尖的暗杀者，转生为异世界贵族", "世界最高の暗殺者、異世界貴族に転生する", 1, "1080p"));
            assertThat(batch.items()).hasSize(2);
            assertThat(batch.items()).allSatisfy(item -> assertThat(item.reviewFlags()).doesNotContain("SEASON_UNKNOWN"));
            String[][] titles = {
                    {"傲慢与偏见-Pride and Prejudice (2005) [1080p][1.8G]",
                            "傲慢与偏见-Pride.And.Prejudice.2005.sdr.bluray.movie.1080p.av1.5.1.opus-Rosy[4.0G]",
                            "傲慢与偏见-Pride.And.Prejudice.2005.1080p.BluRay.x264-FLHD[8.7G]"},
                    {"切尔诺贝利-Chernobyl.2019.S01.COMPLETE.1080p.AMZN.WEBRip.x265-PGW[1.8G]",
                            "切尔诺贝利-Chernobyl.S01.INTERNAL.1080p.BluRay.X264-REWARD[rartv][47.7G]",
                            "切尔诺贝利-Chernobyl (2019) Season 1 - HEVC 1080p 7RIP[1.22G]"},
                    {"死亡诗社.Dead.Poets.Society.1989.1080p.BluRay.x264-RARBG.2.46GB",
                            "死亡诗社.Dead.Poets.Society.1989.1080p.BluRay.x264.17.32GB"}
            };
            Long[] expectedSizes = {8_700_000_000L, 47_700_000_000L, 17_320_000_000L};
            for (int work = 0; work < titles.length; work++) {
                raw.clear();
                merged.clear();
                for (int index = 0; index < titles[work].length; index++) {
                    raw.add(java.util.Map.of("title", titles[work][index], "content", "评分：8.7",
                            "links", List.of(java.util.Map.of("type", "magnet", "url", magnet(index + 1)))));
                    merged.add(java.util.Map.of("url", magnet(index + 1), "note", titles[work][index], "source", "plugin:clxiong"));
                }
                response.set(mapper.writeValueAsString(java.util.Map.of("data", java.util.Map.of("results", raw,
                        "merged_by_type", java.util.Map.of("magnet", merged)))));
                if (work == 1) {
                    var request = new SeriesReleaseRecommendationRequest(null, null, null, "切尔诺贝利", null, 1, "2160p");
                    assertThat(realSearch.recommendSeries(request).item().size()).isEqualTo(expectedSizes[work]);
                    assertThat(realSearch.recommendPreferredSeries(request, ignored -> {}).item().size()).isEqualTo(expectedSizes[work]);
                } else {
                    var request = new MovieReleaseRecommendationRequest(null, null,
                            work == 0 ? "傲慢与偏见" : "死亡诗社", null, work == 0 ? 2005 : 1989, "2160p");
                    assertThat(realSearch.recommendMovie(request).item().size()).isEqualTo(expectedSizes[work]);
                    assertThat(realSearch.recommendPreferredMovie(request, ignored -> {}).item().size()).isEqualTo(expectedSizes[work]);
                }
                java.util.Collections.reverse(raw);
                java.util.Collections.reverse(merged);
                response.set(mapper.writeValueAsString(java.util.Map.of("data", java.util.Map.of("results", raw,
                        "merged_by_type", java.util.Map.of("magnet", merged)))));
                if (work == 1) {
                    assertThat(realSearch.recommendSeries(new SeriesReleaseRecommendationRequest(
                            null, null, null, "切尔诺贝利", null, 1, "2160p")).item().size()).isEqualTo(expectedSizes[work]);
                } else {
                    assertThat(realSearch.recommendMovie(new MovieReleaseRecommendationRequest(null, null,
                            work == 0 ? "傲慢与偏见" : "死亡诗社", null, work == 0 ? 2005 : 1989, "2160p"))
                            .item().size()).isEqualTo(expectedSizes[work]);
                }
            }
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
