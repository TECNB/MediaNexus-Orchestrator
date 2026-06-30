package com.medianexus.orchestrator.service.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.SonarrProperties;
import com.medianexus.orchestrator.config.TmdbProperties;
import com.medianexus.orchestrator.integration.sonarr.SonarrClient;
import com.medianexus.orchestrator.integration.tmdb.TmdbClient;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class TmdbFirstMediaCatalogSearchTest {

    @Test
    void fallsBackToSonarrWhenTmdbFailsAndFallbackIsEnabled() {
        TmdbProperties properties = tmdbProperties(true);
        FakeTmdbSeriesCatalogSearch tmdbSearch = new FakeTmdbSeriesCatalogSearch(properties);
        FakeSonarrMediaCatalogSearch sonarrSearch = new FakeSonarrMediaCatalogSearch();
        sonarrSearch.items = List.of(new SeriesCatalogItem(
                "tvdb:81189",
                "绝命毒师",
                "Breaking Bad",
                2008,
                "When Walter White...",
                null,
                81189,
                "tt0903747",
                1396,
                "ended",
                "AMC",
                "standard"
        ));
        TmdbFirstMediaCatalogSearch catalogSearch = new TmdbFirstMediaCatalogSearch(
                tmdbSearch,
                sonarrSearch,
                properties
        );

        var items = catalogSearch.searchSeries("绝命毒师");

        assertThat(items).hasSize(1);
        assertThat(items.get(0).id()).isEqualTo("tvdb:81189");
        assertThat(sonarrSearch.requestedTerm).isEqualTo("绝命毒师");
    }

    @Test
    void propagatesTmdbFailureWhenFallbackIsDisabled() {
        TmdbProperties properties = tmdbProperties(false);
        TmdbFirstMediaCatalogSearch catalogSearch = new TmdbFirstMediaCatalogSearch(
                new FakeTmdbSeriesCatalogSearch(properties),
                new FakeSonarrMediaCatalogSearch(),
                properties
        );

        assertThatThrownBy(() -> catalogSearch.searchSeries("暗影蜘蛛侠"))
                .isInstanceOf(MediaCatalogSearchException.class)
                .hasMessageContaining("TMDB unavailable");
    }

    @Test
    void loadsSeasonsFromTmdbWhenTmdbIdentityIsAvailable() {
        TmdbProperties properties = tmdbProperties(true);
        FakeTmdbSeriesCatalogSearch tmdbSearch = new FakeTmdbSeriesCatalogSearch(properties);
        tmdbSearch.seasons = new SeriesCatalogSeasons(null, 281449, "暗影蜘蛛侠", List.of(1));
        FakeSonarrMediaCatalogSearch sonarrSearch = new FakeSonarrMediaCatalogSearch();
        TmdbFirstMediaCatalogSearch catalogSearch = new TmdbFirstMediaCatalogSearch(
                tmdbSearch,
                sonarrSearch,
                properties
        );

        var seasons = catalogSearch.getSeriesSeasons(SeriesCatalogIdentity.tmdb(281449));

        assertThat(seasons.tmdbId()).isEqualTo(281449);
        assertThat(seasons.seasonNumbers()).containsExactly(1);
        assertThat(sonarrSearch.requestedIdentity).isNull();
    }

    @Test
    void fallsBackToSonarrSeasonsWhenTmdbFailsAndTvdbIdentityIsAvailable() {
        TmdbProperties properties = tmdbProperties(true);
        FakeTmdbSeriesCatalogSearch tmdbSearch = new FakeTmdbSeriesCatalogSearch(properties);
        tmdbSearch.seasonsFailure = new MediaCatalogSearchException(
                MediaCatalogSearchException.Reason.UPSTREAM,
                "TMDB unavailable"
        );
        FakeSonarrMediaCatalogSearch sonarrSearch = new FakeSonarrMediaCatalogSearch();
        sonarrSearch.seasons = new SeriesCatalogSeasons(451234, 281449, "暗影蜘蛛侠", List.of(1));
        TmdbFirstMediaCatalogSearch catalogSearch = new TmdbFirstMediaCatalogSearch(
                tmdbSearch,
                sonarrSearch,
                properties
        );
        SeriesCatalogIdentity identity = new SeriesCatalogIdentity(451234, 281449, null);

        var seasons = catalogSearch.getSeriesSeasons(identity);

        assertThat(seasons.tvdbId()).isEqualTo(451234);
        assertThat(sonarrSearch.requestedIdentity).isEqualTo(identity);
    }

    @Test
    void propagatesTmdbSeasonFailureWhenNoTvdbIdentityIsAvailable() {
        TmdbProperties properties = tmdbProperties(true);
        FakeTmdbSeriesCatalogSearch tmdbSearch = new FakeTmdbSeriesCatalogSearch(properties);
        tmdbSearch.seasonsFailure = new MediaCatalogSearchException(
                MediaCatalogSearchException.Reason.UPSTREAM,
                "TMDB unavailable"
        );
        TmdbFirstMediaCatalogSearch catalogSearch = new TmdbFirstMediaCatalogSearch(
                tmdbSearch,
                new FakeSonarrMediaCatalogSearch(),
                properties
        );

        assertThatThrownBy(() -> catalogSearch.getSeriesSeasons(SeriesCatalogIdentity.tmdb(281449)))
                .isInstanceOf(MediaCatalogSearchException.class)
                .hasMessageContaining("TMDB unavailable");
    }

    private static class FakeTmdbSeriesCatalogSearch extends TmdbSeriesCatalogSearch {

        private SeriesCatalogSeasons seasons;
        private MediaCatalogSearchException seasonsFailure;

        FakeTmdbSeriesCatalogSearch(TmdbProperties properties) {
            super(new FakeTmdbClient(properties), properties);
        }

        @Override
        public List<SeriesCatalogItem> searchSeries(String term) {
            throw new MediaCatalogSearchException(
                    MediaCatalogSearchException.Reason.UPSTREAM,
                    "TMDB unavailable"
            );
        }

        @Override
        public SeriesCatalogSeasons getSeriesSeasons(SeriesCatalogIdentity identity) {
            if (seasonsFailure != null) {
                throw seasonsFailure;
            }
            return seasons;
        }
    }

    private static class FakeSonarrMediaCatalogSearch extends SonarrMediaCatalogSearch {
        private List<SeriesCatalogItem> items = List.of();
        private String requestedTerm;
        private SeriesCatalogSeasons seasons;
        private SeriesCatalogIdentity requestedIdentity;

        FakeSonarrMediaCatalogSearch() {
            super(new FakeSonarrClient());
        }

        @Override
        public List<SeriesCatalogItem> searchSeries(String term) {
            requestedTerm = term;
            return items;
        }

        @Override
        public SeriesCatalogSeasons getSeriesSeasons(SeriesCatalogIdentity identity) {
            requestedIdentity = identity;
            return seasons;
        }
    }

    private static class FakeTmdbClient extends TmdbClient {
        FakeTmdbClient(TmdbProperties properties) {
            super(properties, new ObjectMapper());
        }
    }

    private static class FakeSonarrClient extends SonarrClient {
        FakeSonarrClient() {
            super(sonarrProperties(), new ObjectMapper());
        }

        @Override
        public JsonNode searchSeries(String term) {
            return new ObjectMapper().createArrayNode();
        }
    }

    private static TmdbProperties tmdbProperties(boolean fallbackToSonarr) {
        TmdbProperties properties = new TmdbProperties();
        properties.setBaseUrl("https://api.themoviedb.org/3");
        properties.setApiToken("test-token");
        properties.setDefaultLanguage("zh-CN");
        properties.setFallbackLanguage("en-US");
        properties.setImageBaseUrl("https://image.tmdb.org/t/p/w500");
        properties.setFallbackToSonarr(fallbackToSonarr);
        properties.setTimeout(Duration.ofSeconds(1));
        return properties;
    }

    private static SonarrProperties sonarrProperties() {
        SonarrProperties properties = new SonarrProperties();
        properties.setTimeout(Duration.ofSeconds(1));
        return properties;
    }
}
