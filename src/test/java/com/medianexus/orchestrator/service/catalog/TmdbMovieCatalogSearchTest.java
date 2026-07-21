package com.medianexus.orchestrator.service.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.TmdbProperties;
import com.medianexus.orchestrator.integration.tmdb.TmdbClient;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TmdbMovieCatalogSearchTest {

    private FakeTmdbClient tmdbClient;
    private TmdbMovieCatalogSearch catalogSearch;

    @BeforeEach
    void setUp() {
        TmdbProperties properties = tmdbProperties();
        tmdbClient = new FakeTmdbClient(properties);
        catalogSearch = new TmdbMovieCatalogSearch(tmdbClient, properties);
    }

    @Test
    void mapsLocalizedTmdbMovieSearchResult() {
        tmdbClient.searchResponse("""
                [
                  {
                    "id": 36557,
                    "title": "007：大战皇家赌场",
                    "original_title": "Casino Royale",
                    "release_date": "2006-11-14",
                    "overview": "邦德首次执行 00 级任务。",
                    "poster_path": "/casino-royale.jpg"
                  }
                ]
                """);

        var items = catalogSearch.searchMovies("007");

        assertThat(items).hasSize(1);
        assertThat(items.get(0).id()).isEqualTo("tmdb:36557");
        assertThat(items.get(0).tmdbId()).isEqualTo(36557);
        assertThat(items.get(0).title()).isEqualTo("007：大战皇家赌场");
        assertThat(items.get(0).originalTitle()).isEqualTo("Casino Royale");
        assertThat(items.get(0).year()).isEqualTo(2006);
        assertThat(items.get(0).poster())
                .isEqualTo("https://image.tmdb.org/t/p/w500/casino-royale.jpg");
        assertThat(items.get(0).imdbId()).isNull();
        assertThat(items.get(0).status()).isEqualTo("released");
    }

    @Test
    void skipsSearchRowsWithoutAUsableTmdbId() {
        tmdbClient.searchResponse("""
                [
                  {"id": null, "title": "Missing"},
                  {"id": 0, "title": "Invalid"},
                  {"id": 603, "title": "黑客帝国", "release_date": "1999-03-30"}
                ]
                """);

        assertThat(catalogSearch.searchMovies("黑客帝国"))
                .extracting(MovieCatalogItem::tmdbId)
                .containsExactly(603);
    }

    private static class FakeTmdbClient extends TmdbClient {
        private final ObjectMapper objectMapper = new ObjectMapper();
        private JsonNode searchResponse;

        FakeTmdbClient(TmdbProperties properties) {
            super(properties, new ObjectMapper());
        }

        void searchResponse(String json) {
            try {
                searchResponse = objectMapper.readTree(json);
            } catch (Exception exception) {
                throw new IllegalArgumentException(exception);
            }
        }

        @Override
        public JsonNode searchMovies(String query, String language) {
            return searchResponse;
        }
    }

    private static TmdbProperties tmdbProperties() {
        TmdbProperties properties = new TmdbProperties();
        properties.setBaseUrl("https://api.themoviedb.org/3");
        properties.setApiToken("test-token");
        properties.setDefaultLanguage("zh-CN");
        properties.setFallbackLanguage("en-US");
        properties.setImageBaseUrl("https://image.tmdb.org/t/p/w500");
        properties.setTimeout(Duration.ofSeconds(5));
        return properties;
    }
}
