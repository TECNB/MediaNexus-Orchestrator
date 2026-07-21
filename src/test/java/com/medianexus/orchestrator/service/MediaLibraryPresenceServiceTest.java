package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.dto.resources.response.MediaLibraryPresenceResponse;
import com.medianexus.orchestrator.integration.emby.EmbyCatalogItem;
import com.medianexus.orchestrator.integration.emby.EmbyClient;
import com.medianexus.orchestrator.integration.emby.EmbyClientException;
import com.medianexus.orchestrator.model.User;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class MediaLibraryPresenceServiceTest {

    private final EmbyClient embyClient = mock(EmbyClient.class);
    private final AnimeMagnetSearchService animeSearchService = mock(AnimeMagnetSearchService.class);
    private final AuthService authService = mock(AuthService.class);
    private final MediaLibraryPresenceService service = new MediaLibraryPresenceService(
            embyClient,
            animeSearchService,
            authService
    );

    @BeforeEach
    void setUp() {
        when(authService.requireCurrentUser()).thenReturn(new User());
    }

    @Test
    void reportsMoviePresentByTmdbId() {
        when(embyClient.findMoviesByTmdbId(36557)).thenReturn(List.of(
                new EmbyCatalogItem("movie-1", "007：大战皇家赌场", "Movie", "/movies/Casino Royale", null)
        ));

        MediaLibraryPresenceResponse response = service.check("movie", 36557, null, null);

        assertThat(response.checkAvailable()).isTrue();
        assertThat(response.exists()).isTrue();
        assertThat(response.matchedTitle()).isEqualTo("007：大战皇家赌场");
    }

    @Test
    void reportsOnlyTheSelectedSeriesSeasonPresent() {
        when(embyClient.findSeriesByTmdbId(93370)).thenReturn(List.of(
                new EmbyCatalogItem("series-1", "杀不死", "Series", "/tv/Sha bu si", null)
        ));
        when(embyClient.listSeriesSeasons("series-1")).thenReturn(List.of(
                new EmbyCatalogItem("season-1", "Season 1", "Season", "/tv/Sha bu si/Season 1", 1)
        ));

        assertThat(service.check("series", 93370, null, 1).exists()).isTrue();
        assertThat(service.check("series", 93370, null, 2).exists()).isFalse();
    }

    @Test
    void resolvesManualAnimeBangumiIdentityBeforeCheckingSeason() {
        when(animeSearchService.resolveTmdbId("400602")).thenReturn(209867);
        when(embyClient.findSeriesByTmdbId(209867)).thenReturn(List.of(
                new EmbyCatalogItem("series-2", "葬送的芙莉莲", "Series", "/anime/Frieren", null)
        ));
        when(embyClient.listSeriesSeasons("series-2")).thenReturn(List.of(
                new EmbyCatalogItem("season-2", "Season 2", "Season", "/anime/Frieren/Season 2", 2)
        ));

        MediaLibraryPresenceResponse response = service.check("series", null, "400602", 2);

        assertThat(response.tmdbId()).isEqualTo(209867);
        assertThat(response.exists()).isTrue();
    }

    @Test
    void keepsPresenceCheckNonBlockingWhenEmbyIsUnavailable() {
        when(embyClient.findMoviesByTmdbId(36557)).thenThrow(new EmbyClientException("offline"));

        MediaLibraryPresenceResponse response = service.check("movie", 36557, null, null);

        assertThat(response.checkAvailable()).isFalse();
        assertThat(response.exists()).isFalse();
    }

    @Test
    void rejectsMovieIngestWhenMovieAlreadyExists() {
        when(embyClient.findMoviesByTmdbId(36557)).thenReturn(List.of(
                new EmbyCatalogItem("movie-1", "007：大战皇家赌场", "Movie", "/movies/Casino Royale", null)
        ));

        assertThatThrownBy(() -> service.requireMovieAbsent(36557))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getMessage())
                            .isEqualTo("Emby 媒体库中已存在《007：大战皇家赌场》，禁止重复入库");
                });
    }

    @Test
    void rejectsOnlyTheExistingSeriesSeason() {
        when(embyClient.findSeriesByTmdbId(93370)).thenReturn(List.of(
                new EmbyCatalogItem("series-1", "杀不死", "Series", "/tv/Sha bu si", null)
        ));
        when(embyClient.listSeriesSeasons("series-1")).thenReturn(List.of(
                new EmbyCatalogItem("season-1", "Season 1", "Season", "/tv/Sha bu si/Season 1", 1)
        ));

        assertThatThrownBy(() -> service.requireSeriesSeasonAbsent(93370, 1))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getMessage())
                            .isEqualTo("Emby 媒体库中已存在《杀不死》第 1 季，禁止重复入库");
                });
        service.requireSeriesSeasonAbsent(93370, 2);
    }
}
