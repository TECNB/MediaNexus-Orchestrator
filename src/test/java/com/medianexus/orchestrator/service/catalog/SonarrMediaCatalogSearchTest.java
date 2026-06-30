package com.medianexus.orchestrator.service.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.SonarrProperties;
import com.medianexus.orchestrator.integration.sonarr.SonarrClient;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SonarrMediaCatalogSearchTest {

    private FakeSonarrClient sonarrClient;
    private SonarrMediaCatalogSearch catalogSearch;

    @BeforeEach
    void setUp() {
        sonarrClient = new FakeSonarrClient();
        catalogSearch = new SonarrMediaCatalogSearch(sonarrClient);
    }

    @Test
    void mapsSonarrLookupToSeriesCatalogItem() {
        sonarrClient.searchResponse("""
                [
                  {
                    "title": "Breaking Bad",
                    "originalTitle": null,
                    "year": 2008,
                    "overview": "When Walter White...",
                    "tvdbId": 81189,
                    "imdbId": "tt0903747",
                    "tmdbId": 1396,
                    "status": "ended",
                    "network": "AMC",
                    "seriesType": "standard",
                    "images": [
                      { "coverType": "poster", "remoteUrl": "https://example.test/poster.jpg" }
                    ],
                    "alternateTitles": [
                      { "title": "绝命毒师" },
                      { "title": "绝命毒师 第1季", "seasonNumber": 1 }
                    ]
                  }
                ]
                """);

        var items = catalogSearch.searchSeries("绝命毒师");

        assertThat(items).hasSize(1);
        assertThat(items.get(0).id()).isEqualTo("tvdb:81189");
        assertThat(items.get(0).title()).isEqualTo("绝命毒师");
        assertThat(items.get(0).originalTitle()).isEqualTo("Breaking Bad");
        assertThat(items.get(0).year()).isEqualTo(2008);
        assertThat(items.get(0).overview()).isEqualTo("When Walter White...");
        assertThat(items.get(0).poster()).isEqualTo("https://example.test/poster.jpg");
        assertThat(items.get(0).tvdbId()).isEqualTo(81189);
        assertThat(items.get(0).imdbId()).isEqualTo("tt0903747");
        assertThat(items.get(0).tmdbId()).isEqualTo(1396);
        assertThat(items.get(0).status()).isEqualTo("ended");
        assertThat(items.get(0).network()).isEqualTo("AMC");
        assertThat(items.get(0).seriesType()).isEqualTo("standard");
    }

    @Test
    void extractsSeasonNumbersFromSonarrSeries() {
        sonarrClient.seriesByTvdbIdResponse("""
                {
                  "title": "Breaking Bad",
                  "tvdbId": 81189,
                  "seasons": [
                    { "seasonNumber": 0 },
                    { "seasonNumber": 3 },
                    { "seasonNumber": 1 },
                    { "seasonNumber": 2 },
                    { "seasonNumber": 2 }
                  ]
                }
                """);

        var seasons = catalogSearch.getSeriesSeasons(SeriesCatalogIdentity.tvdb(81189));

        assertThat(seasons.tvdbId()).isEqualTo(81189);
        assertThat(seasons.title()).isEqualTo("Breaking Bad");
        assertThat(seasons.seasonCount()).isEqualTo(3);
        assertThat(seasons.seasonNumbers()).containsExactly(1, 2, 3);
    }

    private static class FakeSonarrClient extends SonarrClient {
        private final ObjectMapper objectMapper = new ObjectMapper();
        private JsonNode searchResponse = objectMapper.createArrayNode();
        private JsonNode seriesByTvdbIdResponse;

        FakeSonarrClient() {
            super(sonarrProperties(), new ObjectMapper());
        }

        @Override
        public JsonNode searchSeries(String term) {
            return searchResponse;
        }

        @Override
        public JsonNode getSeriesByTvdbId(Integer tvdbId) {
            return seriesByTvdbIdResponse;
        }

        void searchResponse(String payload) {
            searchResponse = readTree(payload);
        }

        void seriesByTvdbIdResponse(String payload) {
            seriesByTvdbIdResponse = readTree(payload);
        }

        private JsonNode readTree(String payload) {
            try {
                return objectMapper.readTree(payload);
            } catch (Exception exception) {
                throw new IllegalArgumentException(exception);
            }
        }

        private static SonarrProperties sonarrProperties() {
            SonarrProperties properties = new SonarrProperties();
            properties.setTimeout(Duration.ofSeconds(1));
            return properties;
        }
    }
}
