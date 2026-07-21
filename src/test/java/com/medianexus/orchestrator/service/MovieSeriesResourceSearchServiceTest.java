package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.medianexus.orchestrator.config.TmdbProperties;
import com.medianexus.orchestrator.dto.resources.response.MovieSearchResponse;
import com.medianexus.orchestrator.dto.resources.response.SeriesSearchResponse;
import com.medianexus.orchestrator.model.User;
import com.medianexus.orchestrator.service.catalog.MediaCatalogSearch;
import com.medianexus.orchestrator.service.catalog.MovieCatalogItem;
import com.medianexus.orchestrator.service.catalog.SeriesCatalogItem;
import com.medianexus.orchestrator.service.catalog.SeriesCatalogSeasons;
import com.medianexus.orchestrator.service.catalog.TmdbMovieCatalogSearch;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MovieSeriesResourceSearchServiceTest {

    private FakeMediaCatalogSearch mediaCatalogSearch;
    private FakeTmdbMovieCatalogSearch movieCatalogSearch;
    private MovieSeriesResourceSearchService service;

    @BeforeEach
    void setUp() {
        mediaCatalogSearch = new FakeMediaCatalogSearch();
        movieCatalogSearch = new FakeTmdbMovieCatalogSearch();
        service = new MovieSeriesResourceSearchService(
                movieCatalogSearch,
                mediaCatalogSearch,
                new TestAuthService()
        );
    }

    @Test
    void mapsTmdbMovieCatalogItemsToApiResponse() {
        movieCatalogSearch.items = List.of(new MovieCatalogItem(
                "tmdb:36557",
                "007：大战皇家赌场",
                "Casino Royale",
                2006,
                "邦德首次执行 00 级任务。",
                "https://image.tmdb.org/t/p/w500/casino-royale.jpg",
                36557,
                null,
                List.of(),
                "released"
        ));

        MovieSearchResponse response = service.searchMovies("007");

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).id()).isEqualTo("tmdb:36557");
        assertThat(response.items().get(0).tmdbId()).isEqualTo(36557);
        assertThat(response.items().get(0).title()).isEqualTo("007：大战皇家赌场");
        assertThat(response.items().get(0).originalTitle()).isEqualTo("Casino Royale");
    }

    @Test
    void mapsCatalogSeriesItemsToApiResponse() {
        mediaCatalogSearch.items = List.of(new SeriesCatalogItem(
                "tmdb:1396",
                "绝命毒师",
                "Breaking Bad",
                2008,
                "When Walter White...",
                "https://example.test/poster.jpg",
                null,
                null,
                1396,
                "ended",
                "AMC",
                "standard"
        ));

        SeriesSearchResponse response = service.searchSeries("绝命毒师");

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).title()).isEqualTo("绝命毒师");
        assertThat(response.items().get(0).originalTitle()).isEqualTo("Breaking Bad");
        assertThat(response.items().get(0).tvdbId()).isNull();
        assertThat(response.items().get(0).tmdbId()).isEqualTo(1396);
    }

    @Test
    void loadsSeriesSeasonsWithTmdbIdentity() {
        mediaCatalogSearch.seasons = new SeriesCatalogSeasons(281449, "暗影蜘蛛侠", List.of(1));

        var response = service.getSeriesSeasons(281449);

        assertThat(response.tmdbId()).isEqualTo(281449);
        assertThat(response.seasonNumbers()).containsExactly(1);
        assertThat(mediaCatalogSearch.requestedTmdbId).isEqualTo(281449);
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

    private static class FakeTmdbMovieCatalogSearch extends TmdbMovieCatalogSearch {
        private List<MovieCatalogItem> items = List.of();

        FakeTmdbMovieCatalogSearch() {
            super(null, new TmdbProperties());
        }

        @Override
        public List<MovieCatalogItem> searchMovies(String term) {
            return items;
        }
    }

    private static class FakeMediaCatalogSearch implements MediaCatalogSearch {
        private List<SeriesCatalogItem> items = List.of();
        private SeriesCatalogSeasons seasons = new SeriesCatalogSeasons(null, "Unknown Title", List.of());
        private Integer requestedTmdbId;

        @Override
        public List<SeriesCatalogItem> searchSeries(String term) {
            return items;
        }

        @Override
        public SeriesCatalogSeasons getSeriesSeasons(Integer tmdbId) {
            requestedTmdbId = tmdbId;
            return seasons;
        }
    }
}
