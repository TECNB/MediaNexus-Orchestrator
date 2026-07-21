package com.medianexus.orchestrator.service.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.TmdbProperties;
import com.medianexus.orchestrator.integration.tmdb.TmdbClient;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TmdbSeriesCatalogSearchTest {

    private FakeTmdbClient tmdbClient;
    private TmdbSeriesCatalogSearch catalogSearch;

    @BeforeEach
    void setUp() {
        TmdbProperties properties = tmdbProperties();
        tmdbClient = new FakeTmdbClient(properties);
        catalogSearch = new TmdbSeriesCatalogSearch(tmdbClient, properties);
    }

    @Test
    void mapsChineseTmdbSeriesSearchResultToCatalogItem() {
        tmdbClient.searchResponse("""
                {
                  "results": [
                    {
                      "id": 281449,
                      "name": "暗影蜘蛛侠",
                      "original_name": "Spider-Noir",
                      "first_air_date": "2026-01-01",
                      "overview": "一位年迈私家侦探重回纽约街头。",
                      "poster_path": "/spider-noir.jpg"
                    }
                  ]
                }
                """);
        tmdbClient.detailResponse(281449, "zh-CN", """
                {
                  "id": 281449,
                  "name": "暗影蜘蛛侠",
                  "original_name": "Spider-Noir",
                  "first_air_date": "2026-01-01",
                  "overview": "一位年迈私家侦探重回纽约街头。",
                  "poster_path": "/spider-noir-detail.jpg",
                  "status": "Returning Series",
                  "type": "Scripted",
                  "networks": [{ "name": "MGM+" }]
                }
                """);
        var items = catalogSearch.searchSeries("暗影蜘蛛侠");

        assertThat(items).hasSize(1);
        assertThat(items.get(0).id()).isEqualTo("tmdb:281449");
        assertThat(items.get(0).title()).isEqualTo("暗影蜘蛛侠");
        assertThat(items.get(0).originalTitle()).isEqualTo("Spider-Noir");
        assertThat(items.get(0).year()).isEqualTo(2026);
        assertThat(items.get(0).overview()).isEqualTo("一位年迈私家侦探重回纽约街头。");
        assertThat(items.get(0).poster()).isEqualTo("https://image.tmdb.org/t/p/w500/spider-noir-detail.jpg");
        assertThat(items.get(0).tmdbId()).isEqualTo(281449);
        assertThat(items.get(0).tvdbId()).isNull();
        assertThat(items.get(0).imdbId()).isNull();
        assertThat(items.get(0).status()).isEqualTo("returning series");
        assertThat(items.get(0).network()).isEqualTo("MGM+");
        assertThat(items.get(0).seriesType()).isEqualTo("Scripted");
    }

    @Test
    void fallsBackToEnglishOverviewWhenLocalizedOverviewIsMissing() {
        tmdbClient.searchResponse("""
                {
                  "results": [
                    {
                      "id": 1396,
                      "name": "Breaking Bad",
                      "original_name": "Breaking Bad",
                      "first_air_date": "2008-01-20",
                      "overview": "",
                      "poster_path": null
                    }
                  ]
                }
                """);
        tmdbClient.detailResponse(1396, "zh-CN", """
                {
                  "id": 1396,
                  "name": "Breaking Bad",
                  "original_name": "Breaking Bad",
                  "first_air_date": "2008-01-20",
                  "overview": "",
                  "poster_path": null,
                  "status": "Ended",
                  "networks": []
                }
                """);
        tmdbClient.detailResponse(1396, "en-US", """
                {
                  "id": 1396,
                  "overview": "A chemistry teacher starts cooking meth."
                }
                """);
        var items = catalogSearch.searchSeries("绝命毒师");

        assertThat(items).hasSize(1);
        assertThat(items.get(0).title()).isEqualTo("Breaking Bad");
        assertThat(items.get(0).overview()).isEqualTo("A chemistry teacher starts cooking meth.");
        assertThat(items.get(0).poster()).isNull();
        assertThat(items.get(0).tvdbId()).isNull();
        assertThat(items.get(0).imdbId()).isNull();
    }

    @Test
    void extractsSortedPositiveSeasonNumbersFromTmdbDetail() {
        tmdbClient.detailResponse(281449, "zh-CN", """
                {
                  "id": 281449,
                  "name": "暗影蜘蛛侠",
                  "seasons": [
                    { "season_number": 2 },
                    { "season_number": 0 },
                    { "season_number": 1 },
                    { "season_number": 2 },
                    { "season_number": -1 }
                  ]
                }
                """);

        var seasons = catalogSearch.getSeriesSeasons(281449);

        assertThat(seasons.tmdbId()).isEqualTo(281449);
        assertThat(seasons.title()).isEqualTo("暗影蜘蛛侠");
        assertThat(seasons.seasonNumbers()).containsExactly(1, 2);
    }

    @Test
    void returnsEmptySeasonNumbersWhenTmdbDetailHasNoSeasons() {
        tmdbClient.detailResponse(281449, "zh-CN", """
                {
                  "id": 281449,
                  "name": "暗影蜘蛛侠"
                }
                """);

        var seasons = catalogSearch.getSeriesSeasons(281449);

        assertThat(seasons.seasonCount()).isZero();
        assertThat(seasons.seasonNumbers()).isEmpty();
    }

    private static class FakeTmdbClient extends TmdbClient {
        private final ObjectMapper objectMapper = new ObjectMapper();
        private JsonNode searchResponse;
        private final java.util.Map<String, JsonNode> detailResponses = new java.util.HashMap<>();

        FakeTmdbClient(TmdbProperties properties) {
            super(properties, new ObjectMapper());
        }

        @Override
        public JsonNode searchTv(String query, String language) {
            return searchResponse.path("results");
        }

        @Override
        public JsonNode getTvDetails(int seriesId, String language) {
            return detailResponses.get(seriesId + ":" + language);
        }

        void searchResponse(String payload) {
            searchResponse = readTree(payload);
        }

        void detailResponse(int seriesId, String language, String payload) {
            detailResponses.put(seriesId + ":" + language, readTree(payload));
        }

        private JsonNode readTree(String payload) {
            try {
                return objectMapper.readTree(payload);
            } catch (Exception exception) {
                throw new IllegalArgumentException(exception);
            }
        }
    }

    private static TmdbProperties tmdbProperties() {
        TmdbProperties properties = new TmdbProperties();
        properties.setBaseUrl("https://api.themoviedb.org/3");
        properties.setApiToken("test-token");
        properties.setDefaultLanguage("zh-CN");
        properties.setFallbackLanguage("en-US");
        properties.setImageBaseUrl("https://image.tmdb.org/t/p/w500");
        properties.setTimeout(Duration.ofSeconds(1));
        return properties;
    }
}
