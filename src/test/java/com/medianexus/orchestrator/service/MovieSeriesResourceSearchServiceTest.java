package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.RadarrProperties;
import com.medianexus.orchestrator.dto.resources.response.SeriesSearchResponse;
import com.medianexus.orchestrator.integration.radarr.RadarrClient;
import com.medianexus.orchestrator.model.User;
import com.medianexus.orchestrator.service.catalog.MediaCatalogSearch;
import com.medianexus.orchestrator.service.catalog.SeriesCatalogIdentity;
import com.medianexus.orchestrator.service.catalog.SeriesCatalogItem;
import com.medianexus.orchestrator.service.catalog.SeriesCatalogSeasons;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MovieSeriesResourceSearchServiceTest {

    private FakeMediaCatalogSearch mediaCatalogSearch;
    private MovieSeriesResourceSearchService service;

    @BeforeEach
    void setUp() {
        mediaCatalogSearch = new FakeMediaCatalogSearch();
        service = new MovieSeriesResourceSearchService(
                new FakeRadarrClient(),
                mediaCatalogSearch,
                new TestAuthService()
        );
    }

    @Test
    void mapsCatalogSeriesItemsToApiResponse() {
        mediaCatalogSearch.items = List.of(new SeriesCatalogItem(
                "tvdb:81189",
                "绝命毒师",
                "Breaking Bad",
                2008,
                "When Walter White...",
                "https://example.test/poster.jpg",
                81189,
                "tt0903747",
                1396,
                "ended",
                "AMC",
                "standard"
        ));

        SeriesSearchResponse response = service.searchSeries("绝命毒师");

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).title()).isEqualTo("绝命毒师");
        assertThat(response.items().get(0).originalTitle()).isEqualTo("Breaking Bad");
        assertThat(response.items().get(0).tvdbId()).isEqualTo(81189);
        assertThat(response.items().get(0).tmdbId()).isEqualTo(1396);
    }

    @Test
    void loadsSeriesSeasonsThroughCatalogIdentity() {
        mediaCatalogSearch.seasons = new SeriesCatalogSeasons(81189, null, "Breaking Bad", List.of(1, 2, 3));

        var response = service.getSeriesSeasons(81189, null);

        assertThat(response.tvdbId()).isEqualTo(81189);
        assertThat(response.tmdbId()).isNull();
        assertThat(response.title()).isEqualTo("Breaking Bad");
        assertThat(response.seasonCount()).isEqualTo(3);
        assertThat(response.seasonNumbers()).containsExactly(1, 2, 3);
        assertThat(mediaCatalogSearch.requestedIdentity.tvdbId()).isEqualTo(81189);
    }

    @Test
    void loadsSeriesSeasonsWithTmdbIdentityWhenTvdbIdIsMissing() {
        mediaCatalogSearch.seasons = new SeriesCatalogSeasons(null, 281449, "暗影蜘蛛侠", List.of(1));

        var response = service.getSeriesSeasons(null, 281449);

        assertThat(response.tvdbId()).isNull();
        assertThat(response.tmdbId()).isEqualTo(281449);
        assertThat(response.seasonNumbers()).containsExactly(1);
        assertThat(mediaCatalogSearch.requestedIdentity.tmdbId()).isEqualTo(281449);
    }

    private static class TestAuthService extends AuthService {
        TestAuthService() {
            super(null, (RegistrationCodeSettingsService) null, null);
        }

        @Override
        public User requireCurrentUser() {
            User user = new User();
            user.setId(1L);
            return user;
        }
    }

    private static class FakeRadarrClient extends RadarrClient {
        FakeRadarrClient() {
            super(radarrProperties(), new ObjectMapper());
        }

        private static RadarrProperties radarrProperties() {
            RadarrProperties properties = new RadarrProperties();
            properties.setTimeout(Duration.ofSeconds(1));
            return properties;
        }
    }

    private static class FakeMediaCatalogSearch implements MediaCatalogSearch {
        private List<SeriesCatalogItem> items = List.of();
        private SeriesCatalogSeasons seasons = new SeriesCatalogSeasons(null, null, "Unknown Title", List.of());
        private SeriesCatalogIdentity requestedIdentity;

        @Override
        public List<SeriesCatalogItem> searchSeries(String term) {
            return items;
        }

        @Override
        public SeriesCatalogSeasons getSeriesSeasons(SeriesCatalogIdentity identity) {
            requestedIdentity = identity;
            return seasons;
        }
    }
}
