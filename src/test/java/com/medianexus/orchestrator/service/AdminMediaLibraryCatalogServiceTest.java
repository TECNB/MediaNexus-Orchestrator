package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.integration.emby.EmbyClient;
import com.medianexus.orchestrator.integration.emby.EmbyClientException;
import com.medianexus.orchestrator.integration.emby.EmbyLibrary;
import com.medianexus.orchestrator.integration.emby.EmbyMediaLibraryItem;
import com.medianexus.orchestrator.integration.emby.EmbyMediaLibraryPage;
import com.medianexus.orchestrator.integration.emby.EmbyPrimaryImage;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AdminMediaLibraryCatalogServiceTest {

    private final AuthService authService = mock(AuthService.class);
    private final EmbyClient embyClient = mock(EmbyClient.class);
    private final AdminMediaLibraryCatalogService service = new AdminMediaLibraryCatalogService(
            authService,
            embyClient
    );

    @Test
    void listsOnlyTheRequestedAllowedLibraryAndMapsPagingMetadata() {
        when(embyClient.listLibraries()).thenReturn(List.of(
                new EmbyLibrary("movies-id", "Movies", List.of("/movies")),
                new EmbyLibrary("adult-id", "Adult", List.of("/adult"))
        ));
        when(embyClient.listTopLevelMediaItems("movies-id", "Movie", 24, 24, "matrix"))
                .thenReturn(new EmbyMediaLibraryPage(List.of(new EmbyMediaLibraryItem(
                        "item-1",
                        "The Matrix",
                        "Movie",
                        1999,
                        "2026-07-20T12:00:00Z",
                        "tag"
                )), 25));

        var response = service.listItems("MOVIES", 2, 24, "matrix");

        verify(authService).requireAdminUser();
        assertThat(response.page()).isEqualTo(2);
        assertThat(response.pageSize()).isEqualTo(24);
        assertThat(response.total()).isEqualTo(25);
        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.itemId()).isEqualTo("item-1");
            assertThat(item.libraryId()).isEqualTo("movies-id");
            assertThat(item.libraryName()).isEqualTo("Movies");
            assertThat(item.hasPrimaryImage()).isTrue();
        });
    }

    @Test
    void listsAdultJavFromItsExactVirtualLibrary() {
        when(embyClient.listLibraries()).thenReturn(List.of(
                new EmbyLibrary("adult-other-id", "Adult - Other", List.of("/adult/other")),
                new EmbyLibrary("adult-jav-id", "Adult-JAV", List.of("/adult/jav"))
        ));
        when(embyClient.listTopLevelMediaItems("adult-jav-id", "Movie", 0, 24, null))
                .thenReturn(new EmbyMediaLibraryPage(List.of(), 501));

        var response = service.listItems("adult-jav", 1, 24, null);

        verify(authService).requireAdminUser();
        verify(embyClient).listTopLevelMediaItems("adult-jav-id", "Movie", 0, 24, null);
        assertThat(response.total()).isEqualTo(501);
    }

    @Test
    void rejectsLibrariesOutsideTheAllowedSetBeforeCallingEmby() {
        assertThatThrownBy(() -> service.listItems("adult", 1, 24, null))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getMessage()).contains("adult-other 或 adult-jav");
                });

        verify(authService).requireAdminUser();
        verify(embyClient, never()).listLibraries();
    }

    @Test
    void reportsEmbyFailuresAsServiceUnavailableInsteadOfAnEmptyPage() {
        when(embyClient.listLibraries()).thenThrow(new EmbyClientException("timeout"));

        assertThatThrownBy(() -> service.listItems("tv", 1, 24, null))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(exception.getCode()).isEqualTo(503);
                    assertThat(exception.getMessage()).isEqualTo("Emby 媒体库暂时不可用");
                });
    }

    @Test
    void proxiesPosterWithOneEmbyRequestAfterAdminAuthorization() {
        when(embyClient.getPrimaryImageWithContentType("item-1"))
                .thenReturn(new EmbyPrimaryImage(new byte[]{1, 2, 3}, "image/jpeg"));

        AdminMediaLibraryPoster poster = service.getPoster("item-1");

        verify(authService).requireAdminUser();
        verify(embyClient, never()).listLibraries();
        assertThat(poster.bytes()).containsExactly(1, 2, 3);
        assertThat(poster.contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void reportsPosterUpstreamFailureAsServiceUnavailable() {
        when(embyClient.getPrimaryImageWithContentType("missing"))
                .thenThrow(new EmbyClientException("Emby returned non-success status 404"));

        assertThatThrownBy(() -> service.getPoster("missing"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void refusesMetadataChangesForItemsOutsideTheSelectedLibrary() {
        when(embyClient.listLibraries()).thenReturn(List.of(
                new EmbyLibrary("anime-id", "Anime", List.of("/anime"))
        ));
        when(embyClient.getTopLevelMediaItem("anime-id", "Series", "movie-item"))
                .thenReturn(null);

        assertThatThrownBy(() -> service.applyMetadataCandidate(
                "movie-item", "anime", "title", 2018, "candidate", false
        )).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(exception.getMessage()).contains("指定媒体库");
        });

        verify(authService).requireAdminUser();
        verify(embyClient).listLibraries();
        verify(embyClient).getTopLevelMediaItem("anime-id", "Series", "movie-item");
        verifyNoMoreInteractions(embyClient);
    }
}
