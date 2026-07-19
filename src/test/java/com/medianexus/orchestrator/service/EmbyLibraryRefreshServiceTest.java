package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.dto.emby.response.EmbyLibraryListResponse;
import com.medianexus.orchestrator.dto.emby.response.EmbyLibraryRefreshResponse;
import com.medianexus.orchestrator.integration.emby.EmbyClient;
import com.medianexus.orchestrator.integration.emby.EmbyLibrary;
import java.util.List;
import org.junit.jupiter.api.Test;

class EmbyLibraryRefreshServiceTest {

    @Test
    void listsOnlyAnimeMoviesAndTvLibraries() {
        EmbyClient embyClient = mock(EmbyClient.class);
        when(embyClient.listLibraries()).thenReturn(List.of(
                new EmbyLibrary("anime-library", "Anime", List.of("/private/media/anime")),
                new EmbyLibrary("movie-library", "Movies", List.of("/private/media/movie")),
                new EmbyLibrary("tv-library", "TV", List.of("/private/media/tv")),
                new EmbyLibrary("adult-library", "Adult", List.of("/private/media/adult"))
        ));
        EmbyLibraryRefreshService service = new EmbyLibraryRefreshService(embyClient);

        EmbyLibraryListResponse response = service.listLibraries();

        assertThat(response.items())
                .extracting(library -> library.name())
                .containsExactly("Anime", "Movies", "TV");
    }

    @Test
    void refreshesTheSelectedEmbyLibrary() {
        EmbyClient embyClient = mock(EmbyClient.class);
        when(embyClient.listLibraries()).thenReturn(List.of(
                new EmbyLibrary("movie-library", "Movies", List.of("/media/movie")),
                new EmbyLibrary("series-library", "TV", List.of("/media/series"))
        ));
        EmbyLibraryRefreshService service = new EmbyLibraryRefreshService(embyClient);

        EmbyLibraryRefreshResponse response = service.refreshLibrary("series-library");

        assertThat(response.libraryId()).isEqualTo("series-library");
        assertThat(response.libraryName()).isEqualTo("TV");
        verify(embyClient).refreshLibrary("series-library");
        verify(embyClient, never()).refreshLibrary("movie-library");
    }

    @Test
    void rejectsARealEmbyLibraryOutsideTheSubtitleTargets() {
        EmbyClient embyClient = mock(EmbyClient.class);
        when(embyClient.listLibraries()).thenReturn(List.of(
                new EmbyLibrary("adult-library", "Adult", List.of("/media/adult"))
        ));
        EmbyLibraryRefreshService service = new EmbyLibraryRefreshService(embyClient);

        assertThatThrownBy(() -> service.refreshLibrary("adult-library"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("未找到指定的 Emby 媒体库");
        verify(embyClient, never()).refreshLibrary("adult-library");
    }

    @Test
    void rejectsAnItemIdThatIsNotAnEmbyLibrary() {
        EmbyClient embyClient = mock(EmbyClient.class);
        when(embyClient.listLibraries()).thenReturn(List.of(
                new EmbyLibrary("movie-library", "Movies", List.of("/media/movie"))
        ));
        EmbyLibraryRefreshService service = new EmbyLibraryRefreshService(embyClient);

        assertThatThrownBy(() -> service.refreshLibrary("arbitrary-item-id"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("未找到指定的 Emby 媒体库");
        verify(embyClient, never()).refreshLibrary("arbitrary-item-id");
    }
}
