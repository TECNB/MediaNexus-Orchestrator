package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.config.ProwlarrProperties;
import com.medianexus.orchestrator.dto.resources.request.MovieReleaseRecommendationRequest;
import com.medianexus.orchestrator.dto.resources.request.MovieReleaseSearchRequest;
import com.medianexus.orchestrator.dto.resources.request.SeriesReleaseRecommendationRequest;
import com.medianexus.orchestrator.dto.resources.request.SeriesReleaseSearchRequest;
import com.medianexus.orchestrator.dto.resources.response.ProwlarrReleaseRecommendationResponse;
import com.medianexus.orchestrator.dto.resources.response.ProwlarrReleaseSearchResponse;
import com.medianexus.orchestrator.integration.prowlarr.ProwlarrClient;
import com.medianexus.orchestrator.integration.prowlarr.ProwlarrRelease;
import com.medianexus.orchestrator.model.User;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProwlarrReleaseIngestServiceTest {

    private FakeProwlarrClient prowlarrClient;

    private ProwlarrReleaseIngestService service;

    @BeforeEach
    void setUp() {
        prowlarrClient = new FakeProwlarrClient();
        service = new ProwlarrReleaseIngestService(
                new TestAuthService(),
                prowlarrClient,
                new ReleaseTitleTagParser(),
                new MagnetIngestService(null, null, null, null, null, null, null, null, null, null)
        );
    }

    @Test
    void recommendsDisplayTitleCandidatesBeforeIdentifierMatches() {
        prowlarrClient.respondWith("{TmdbId:129}", List.of(
                release("Spirited.Away.2001.1080p.BluRay.x264", 5, 20_000_000_000L)
        ));
        prowlarrClient.respondWith("{ImdbId:tt0245429}", List.of(
                release("Spirited.Away.2001.1080p.WEB-DL.x265", 4, 12_000_000_000L)
        ));
        prowlarrClient.respondWith("千与千寻 2001", List.of(
                release("千与千寻.2001.1080p.BluRay.x265", 6, 30_000_000_000L),
                release("千与千寻.2001.1080p.WEB-DL.x264", 30, 8_000_000_000L)
        ));

        ProwlarrReleaseRecommendationResponse response = service.recommendMovieRelease(
                request(129, "tt0245429", "千与千寻", "Spirited Away", 2001, "1080p")
        );

        assertThat(response.query()).isEqualTo("千与千寻 2001");
        assertThat(response.item().matchSource()).isEqualTo("展示标题");
        assertThat(response.item().title()).isEqualTo("千与千寻.2001.1080p.BluRay.x265");
        assertThat(response.items())
                .extracting("title")
                .containsExactly(
                        "千与千寻.2001.1080p.BluRay.x265",
                        "千与千寻.2001.1080p.WEB-DL.x264",
                        "Spirited.Away.2001.1080p.BluRay.x264",
                        "Spirited.Away.2001.1080p.WEB-DL.x265"
                );
        assertThat(prowlarrClient.calls()).containsExactlyInAnyOrder(
                "{ImdbId:tt0245429}",
                "{TmdbId:129}",
                "Spirited Away 2001",
                "千与千寻 2001"
        );
    }

    @Test
    void recommendsOriginalTitleCandidatesBySizeAndSeedersWhenDisplayTitleHasNoMatch() {
        prowlarrClient.respondWith("{TmdbId:129}", List.of(
                release("Spirited.Away.2001.1080p.BluRay.x264", 8, 8_000_000_000L)
        ));
        prowlarrClient.respondWith("{ImdbId:tt0245429}", List.of());
        prowlarrClient.respondWith("千与千寻 2001", List.of());
        prowlarrClient.respondWith("Spirited Away 2001", List.of(
                release("Spirited.Away.2001.1080p.BluRay.x265", 6, 14_000_000_000L)
        ));

        ProwlarrReleaseRecommendationResponse response = service.recommendMovieRelease(
                request(129, "tt0245429", "千与千寻", "Spirited Away", 2001, "1080p")
        );

        assertThat(response.query()).isEqualTo("Spirited Away 2001");
        assertThat(response.item().matchSource()).isEqualTo("原始标题");
        assertThat(response.item().resolutionTags()).containsExactly("1080p");
        assertThat(response.items())
                .extracting("title")
                .containsExactly(
                        "Spirited.Away.2001.1080p.BluRay.x265",
                        "Spirited.Away.2001.1080p.BluRay.x264"
                );
    }

    @Test
    void originalTitleCanFindReleaseWhenDisplayTitleCannot() {
        prowlarrClient.respondWith("千与千寻 2001", List.of());
        prowlarrClient.respondWith("Spirited Away 2001", List.of(
                release("Spirited.Away.2001.2160p.UHD.BluRay.x265", 4, 40_000_000_000L)
        ));

        ProwlarrReleaseRecommendationResponse response = service.recommendMovieRelease(
                request(null, null, "千与千寻", "Spirited Away", 2001, "2160p")
        );

        assertThat(response.query()).isEqualTo("Spirited Away 2001");
        assertThat(response.item().title()).contains("Spirited.Away");
    }

    @Test
    void recommendsDisplayTitleMatchBeforeOriginalTitleMatch() {
        prowlarrClient.respondWith("千与千寻 2001", List.of(
                release("千与千寻.2001.2160p.BluRay.x265", 30, 60_000_000_000L)
        ));
        prowlarrClient.respondWith("Spirited Away 2001", List.of(
                release("Spirited.Away.2001.2160p.BluRay.x265", 4, 20_000_000_000L)
        ));

        ProwlarrReleaseRecommendationResponse response = service.recommendMovieRelease(
                request(null, null, "千与千寻", "Spirited Away", 2001, "2160p")
        );

        assertThat(response.query()).isEqualTo("千与千寻 2001");
        assertThat(response.item().matchSource()).isEqualTo("展示标题");
        assertThat(response.items())
                .extracting("title")
                .containsExactly(
                        "千与千寻.2001.2160p.BluRay.x265",
                        "Spirited.Away.2001.2160p.BluRay.x265"
                );
    }

    @Test
    void originalTitleWithFrenchDiacriticsCanFindAsciiReleaseTitle() {
        prowlarrClient.respondWith("我住在凡尔赛的日子 2021", List.of());
        prowlarrClient.respondWith("La Vie de château : Mon enfance à Versailles 2021", List.of(
                release("La.Vie.de.Chateau.Mon.Enfance.a.Versailles.2021.1080p.WEB-DL.x264", 5, 9_000_000_000L)
        ));

        ProwlarrReleaseRecommendationResponse response = service.recommendMovieRelease(
                request(
                        null,
                        null,
                        "我住在凡尔赛的日子",
                        "La Vie de château : Mon enfance à Versailles",
                        2021,
                        "1080p"
                )
        );

        assertThat(response.query()).isEqualTo("La Vie de château : Mon enfance à Versailles 2021");
        assertThat(response.item().title()).contains("La.Vie.de.Chateau");
    }

    @Test
    void originalTitleCanFindReleaseWhenAsciiTitleOmitsStopWords() {
        prowlarrClient.respondWith("{TmdbId:1489456}", List.of());
        prowlarrClient.respondWith("{ImdbId:tt37436610}", List.of());
        prowlarrClient.respondWith("我住在凡尔赛的日子 2025", List.of());
        prowlarrClient.respondWith("La Vie de château : Mon enfance à Versailles 2025", List.of(
                release("Www UIndex org Vie chateau Mon enfance Versailles 2025 FRENCH 1080p WEB DL 264 Slay3R", 3, 4_300_000_000L)
        ));

        ProwlarrReleaseRecommendationResponse response = service.recommendMovieRelease(
                request(
                        1489456,
                        "tt37436610",
                        "我住在凡尔赛的日子",
                        "La Vie de château : Mon enfance à Versailles",
                        2025,
                        "1080p"
                )
        );

        assertThat(response.query()).isEqualTo("La Vie de château : Mon enfance à Versailles 2025");
        assertThat(response.item().title()).contains("Vie chateau");
    }

    @Test
    void movieReleaseSearchSortsLikeRecommendationAndDeduplicatesReleaseReferences() {
        ProwlarrRelease duplicateFromTmdb = release(
                "La.Vie.de.Chateau.Mon.Enfance.a.Versailles.2025.1080p.WEB-DL.x264",
                4,
                9_000_000_000L
        );
        ProwlarrRelease duplicateFromOriginalTitle = releaseWithDownloadRef(
                "La Vie de chateau Mon enfance a Versailles 2025 FRENCH 1080p WEB DL H 264 Slay3R",
                7,
                8_800_000_000L,
                duplicateFromTmdb.downloadRef()
        );
        prowlarrClient.respondWith("{TmdbId:1489456}", List.of(duplicateFromTmdb));
        prowlarrClient.respondWith("{ImdbId:tt37436610}", List.of());
        prowlarrClient.respondWith("我住在凡尔赛的日子 2025", List.of(
                release("我住在凡尔赛的日子.2025.720p.WEB-DL.x264", 2, 5_000_000_000L)
        ));
        prowlarrClient.respondWith("La Vie de château : Mon enfance à Versailles 2025", List.of(
                duplicateFromOriginalTitle,
                release("La.Vie.de.Chateau.Mon.Enfance.a.Versailles.2025.2160p.WEB-DL.x265", 3, 16_000_000_000L)
        ));

        ProwlarrReleaseSearchResponse response = service.searchMovieReleases(
                new MovieReleaseSearchRequest(
                        1489456,
                        "tt37436610",
                        "我住在凡尔赛的日子",
                        "La Vie de château : Mon enfance à Versailles",
                        2025,
                        "2160p"
                )
        );

        assertThat(prowlarrClient.calls()).containsExactlyInAnyOrder(
                "{TmdbId:1489456}",
                "{ImdbId:tt37436610}",
                "我住在凡尔赛的日子 2025",
                "La Vie de château : Mon enfance à Versailles 2025"
        );
        assertThat(response.query()).isEqualTo("电影发布搜索计划");
        assertThat(response.items()).hasSize(3);
        assertThat(response.items())
                .extracting("title")
                .containsExactly(
                        "La.Vie.de.Chateau.Mon.Enfance.a.Versailles.2025.2160p.WEB-DL.x265",
                        "我住在凡尔赛的日子.2025.720p.WEB-DL.x264",
                        "La Vie de chateau Mon enfance a Versailles 2025 FRENCH 1080p WEB DL H 264 Slay3R"
                );
        assertThat(response.items().get(0).matchSource()).isEqualTo("原始标题");
        assertThat(response.items().get(0).matchQuery())
                .isEqualTo("La Vie de château : Mon enfance à Versailles 2025");
        assertThat(response.items().get(2).matchSource()).isEqualTo("原始标题");
    }

    @Test
    void movieReleaseSearchOnlyUsesDisplayAndOriginalTitles() {
        prowlarrClient.respondWith("F1：狂飙飞车 2025", List.of());
        prowlarrClient.respondWith("F1 2025", List.of());

        assertThatThrownBy(() -> service.recommendMovieRelease(
                request(null, null, "F1：狂飙飞车", "F1", 2025, "2160p")
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessage("未找到匹配分辨率且有做种的电影发布资源");

        assertThat(prowlarrClient.calls()).containsExactlyInAnyOrder(
                "F1：狂飙飞车 2025",
                "F1 2025"
        );
    }

    @Test
    void recommendsSeriesOriginalTitleCandidatesWhenDisplayTitleHasNoMatch() {
        prowlarrClient.respondWith("{TvdbId:81189}", List.of());
        prowlarrClient.respondWith("{ImdbId:tt0903747}", List.of());
        prowlarrClient.respondWith("{TmdbId:1396}", List.of());
        prowlarrClient.respondWith("绝命毒师", List.of());
        prowlarrClient.respondWith("Breaking Bad", List.of(
                release("Breaking.Bad.S01.1080p.BluRay.x265", 7, 26_000_000_000L),
                release("Breaking.Bad.S01.1080p.WEB-DL.x264", 20, 9_000_000_000L)
        ));

        ProwlarrReleaseRecommendationResponse response = service.recommendSeriesRelease(
                seriesRequest(81189, 1396, "tt0903747", "绝命毒师", "Breaking Bad", 1, "1080p")
        );

        assertThat(response.query()).isEqualTo("Breaking Bad");
        assertThat(response.item().matchSource()).isEqualTo("原始标题");
        assertThat(response.item().title()).isEqualTo("Breaking.Bad.S01.1080p.BluRay.x265");
        assertThat(response.items())
                .extracting("title")
                .containsExactly(
                        "Breaking.Bad.S01.1080p.BluRay.x265",
                        "Breaking.Bad.S01.1080p.WEB-DL.x264"
                );
        assertThat(prowlarrClient.calls()).containsExactlyInAnyOrder(
                "{TvdbId:81189}",
                "{ImdbId:tt0903747}",
                "{TmdbId:1396}",
                "绝命毒师",
                "Breaking Bad"
        );
    }

    @Test
    void recommendsTmdbSeriesWithoutTvdbAndKeepsBothTitlesInSearchPlan() {
        prowlarrClient.respondWith("{ImdbId:tt27981453} S02", List.of());
        prowlarrClient.respondWith("{TmdbId:281449} S02", List.of(
                release("Spider-Noir.S02.1080p.WEB-DL.x265", 8, 12_000_000_000L)
        ));
        prowlarrClient.respondWith("暗影蜘蛛侠 S02", List.of());
        prowlarrClient.respondWith("Spider-Noir S02", List.of());

        ProwlarrReleaseRecommendationResponse response = service.recommendSeriesRelease(
                seriesRequest(null, 281449, "tt27981453", "暗影蜘蛛侠", "Spider-Noir", 2, "1080p")
        );

        assertThat(response.query()).isEqualTo("{TmdbId:281449} S02");
        assertThat(response.item().matchSource()).isEqualTo("TMDB ID");
        assertThat(response.item().matchQuery()).isEqualTo("{TmdbId:281449} S02");
        assertThat(prowlarrClient.calls()).containsExactlyInAnyOrder(
                "{ImdbId:tt27981453} S02",
                "{TmdbId:281449} S02",
                "暗影蜘蛛侠 S02",
                "Spider-Noir S02"
        );
        assertThat(prowlarrClient.calls()).noneMatch(query -> query.contains("TvdbId"));
    }

    @Test
    void firstSeasonSearchUsesUnqualifiedQueriesAndKeepsOnlyMatchingSeasonPacks() {
        prowlarrClient.respondWith("{TmdbId:231873}", List.of(
                release("失忆投捕.S02.1080p.WEB-DL.x265", 8, 8_000_000_000L)
        ));
        prowlarrClient.respondWith("失忆投捕", List.of(
                release(
                        "[LoliHouse] 失忆投捕 / Boukyaku Battery [01-12 合集] [1080p]",
                        25,
                        5_200_000_000L
                ),
                release("失忆投捕 [04] [1080p]", 4, 420_000_000L)
        ));
        prowlarrClient.respondWith("Boukyaku Battery", List.of());

        ProwlarrReleaseSearchResponse response = service.searchSeriesReleases(
                new SeriesReleaseSearchRequest(
                        null,
                        231873,
                        null,
                        "失忆投捕",
                        "Boukyaku Battery",
                        1,
                        "1080p"
                )
        );

        assertThat(response.items()).singleElement()
                .satisfies(release -> {
                    assertThat(release.title())
                            .isEqualTo("[LoliHouse] 失忆投捕 / Boukyaku Battery [01-12 合集] [1080p]");
                    assertThat(release.seasonTags()).containsExactly("S01");
                    assertThat(release.matchSource()).isEqualTo("展示标题");
                    assertThat(release.matchQuery()).isEqualTo("失忆投捕");
                });
        assertThat(prowlarrClient.calls()).containsExactlyInAnyOrder(
                "{TmdbId:231873}",
                "失忆投捕",
                "Boukyaku Battery"
        );
    }

    @Test
    void listsTmdbSeriesReleasesWithIdAndTitleMatchSourcesWhenTvdbIsMissing() {
        prowlarrClient.respondWith("{TmdbId:281449} S02", List.of(
                release("Spider-Noir.S02.2160p.WEB-DL.x265", 5, 24_000_000_000L)
        ));
        prowlarrClient.respondWith("暗影蜘蛛侠 S02", List.of(
                release("暗影蜘蛛侠.S02.1080p.WEB-DL.x265", 7, 12_000_000_000L)
        ));
        prowlarrClient.respondWith("Spider-Noir S02", List.of(
                release("Spider-Noir.S02.720p.WEB-DL.x264", 9, 6_000_000_000L)
        ));

        ProwlarrReleaseSearchResponse response = service.searchSeriesReleases(
                new SeriesReleaseSearchRequest(
                        null,
                        281449,
                        null,
                        "暗影蜘蛛侠",
                        "Spider-Noir",
                        2,
                        "1080p"
                )
        );

        assertThat(response.items()).hasSize(3);
        assertThat(response.items())
                .extracting("matchSource")
                .containsExactlyInAnyOrder("TMDB ID", "展示标题", "原始标题");
        assertThat(response.items())
                .extracting("matchQuery")
                .containsExactlyInAnyOrder(
                        "{TmdbId:281449} S02",
                        "暗影蜘蛛侠 S02",
                        "Spider-Noir S02"
                );
    }

    @Test
    void filtersUnrelatedSeriesResultsAndLabelsDuplicateTitleAsDisplayTitle() {
        prowlarrClient.respondWith("{TmdbId:93370}", List.of());
        prowlarrClient.respondWith("杀不死", List.of(
                release("Tunshi.Xingkong.S01-S04.2160p.UHDTV.H265", 10, 480_000_000_000L),
                release("The.Agency.S02.2160p.WEB-DL.H265", 15, 89_000_000_000L),
                release("Star.City.S01.2160p.WEB-DL.H265", 11, 65_000_000_000L),
                release("杀不死.S01.1080p.WEB-DL.x265", 6, 12_000_000_000L)
        ));

        ProwlarrReleaseSearchResponse response = service.searchSeriesReleases(
                new SeriesReleaseSearchRequest(
                        null,
                        93370,
                        null,
                        "杀不死",
                        "杀不死",
                        1,
                        "1080p"
                )
        );

        assertThat(response.items()).singleElement()
                .satisfies(release -> {
                    assertThat(release.title()).isEqualTo("杀不死.S01.1080p.WEB-DL.x265");
                    assertThat(release.matchSource()).isEqualTo("展示标题");
                    assertThat(release.matchQuery()).isEqualTo("杀不死");
                });
    }

    @Test
    void reportsClearSeriesRecommendationFailureAndLegalEmptySearch() {
        SeriesReleaseRecommendationRequest recommendationRequest = seriesRequest(
                null,
                281449,
                null,
                "暗影蜘蛛侠",
                "Spider-Noir",
                2,
                "1080p"
        );

        assertThatThrownBy(() -> service.recommendSeriesRelease(recommendationRequest))
                .isInstanceOf(BusinessException.class)
                .hasMessage("未找到匹配分辨率且有做种的剧集发布资源");

        ProwlarrReleaseSearchResponse response = service.searchSeriesReleases(
                new SeriesReleaseSearchRequest(
                        null,
                        281449,
                        null,
                        "暗影蜘蛛侠",
                        "Spider-Noir",
                        2,
                        "1080p"
                )
        );

        assertThat(response.query()).isEqualTo("剧集发布搜索计划");
        assertThat(response.items()).isEmpty();
    }

    @Test
    void seriesReleaseSearchSortsLikeRecommendationAndDeduplicatesReleaseReferences() {
        ProwlarrRelease duplicateFromTvdb = release(
                "Breaking.Bad.S01.1080p.WEB-DL.x264",
                4,
                12_000_000_000L
        );
        ProwlarrRelease duplicateFromOriginalTitle = releaseWithDownloadRef(
                "Breaking Bad Season 01 1080p WEB DL x264",
                8,
                14_000_000_000L,
                duplicateFromTvdb.downloadRef()
        );
        prowlarrClient.respondWith("{TvdbId:81189}", List.of(duplicateFromTvdb));
        prowlarrClient.respondWith("{ImdbId:tt0903747}", List.of());
        prowlarrClient.respondWith("{TmdbId:1396}", List.of());
        prowlarrClient.respondWith("绝命毒师", List.of(
                release("绝命毒师.S01.720p.WEB-DL.x264", 3, 7_000_000_000L)
        ));
        prowlarrClient.respondWith("Breaking Bad", List.of(
                duplicateFromOriginalTitle,
                release("Breaking.Bad.S01.2160p.WEB-DL.x265", 5, 40_000_000_000L)
        ));

        ProwlarrReleaseSearchResponse response = service.searchSeriesReleases(
                new SeriesReleaseSearchRequest(
                        81189,
                        1396,
                        "tt0903747",
                        "绝命毒师",
                        "Breaking Bad",
                        1,
                        "2160p"
                )
        );

        assertThat(response.query()).isEqualTo("剧集发布搜索计划");
        assertThat(response.items()).hasSize(3);
        assertThat(response.items())
                .extracting("title")
                .containsExactly(
                        "Breaking.Bad.S01.2160p.WEB-DL.x265",
                        "绝命毒师.S01.720p.WEB-DL.x264",
                        "Breaking Bad Season 01 1080p WEB DL x264"
                );
        assertThat(response.items().get(0).matchSource()).isEqualTo("原始标题");
        assertThat(response.items().get(0).matchQuery()).isEqualTo("Breaking Bad");
        assertThat(response.items().get(2).matchSource()).isEqualTo("原始标题");
    }

    @Test
    void reportsClearFailureWhenNoLayerHasSelectableRelease() {
        prowlarrClient.respondWith("{TmdbId:129}", List.of());
        prowlarrClient.respondWith("{ImdbId:tt0245429}", List.of());
        prowlarrClient.respondWith("千与千寻 2001", List.of());
        prowlarrClient.respondWith("Spirited Away 2001", List.of(
                release("Spirited.Away.2001.1080p.BluRay.x264", 0, 12_000_000_000L)
        ));

        assertThatThrownBy(() -> service.recommendMovieRelease(
                request(129, "tt0245429", "千与千寻", "Spirited Away", 2001, "1080p")
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessage("未找到匹配分辨率且有做种的电影发布资源");
    }

    private MovieReleaseRecommendationRequest request(
            Integer tmdbId,
            String imdbId,
            String title,
            String originalTitle,
            Integer year,
            String quality
    ) {
        return new MovieReleaseRecommendationRequest(tmdbId, imdbId, title, originalTitle, year, quality);
    }

    private SeriesReleaseRecommendationRequest seriesRequest(
            Integer tvdbId,
            Integer tmdbId,
            String imdbId,
            String title,
            String originalTitle,
            Integer seasonNumber,
            String quality
    ) {
        return new SeriesReleaseRecommendationRequest(
                tvdbId,
                tmdbId,
                imdbId,
                title,
                originalTitle,
                seasonNumber,
                quality
        );
    }

    private ProwlarrRelease release(String title, Integer seeders, Long size) {
        return releaseWithDownloadRef(title, seeders, size, "download-ref-" + title);
    }

    private ProwlarrRelease releaseWithDownloadRef(String title, Integer seeders, Long size, String downloadRef) {
        return new ProwlarrRelease(
                title,
                size,
                seeders,
                1,
                10,
                "Test Indexer",
                "2026-06-25T00:00:00Z",
                1,
                "guid-" + title,
                downloadRef
        );
    }

    private static class TestAuthService extends AuthService {
        TestAuthService() {
            super(null, null, null);
        }

        @Override
        public User requireCurrentUser() {
            User user = new User();
            user.setId(1L);
            return user;
        }
    }

    private static class FakeProwlarrClient extends ProwlarrClient {
        private final Map<String, List<ProwlarrRelease>> responses = new HashMap<>();
        private final List<String> calls = new ArrayList<>();

        FakeProwlarrClient() {
            super(prowlarrProperties(), new ObjectMapper());
        }

        @Override
        public List<ProwlarrRelease> search(String query) {
            calls.add(query);
            return responses.getOrDefault(query, List.of());
        }

        void respondWith(String query, List<ProwlarrRelease> releases) {
            responses.put(query, releases);
        }

        List<String> calls() {
            return calls;
        }

        private static ProwlarrProperties prowlarrProperties() {
            ProwlarrProperties properties = new ProwlarrProperties();
            properties.setTimeout(Duration.ofSeconds(1));
            return properties;
        }
    }
}
