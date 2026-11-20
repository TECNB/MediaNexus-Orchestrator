package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.medianexus.orchestrator.dto.magnet.request.*;
import com.medianexus.orchestrator.dto.resources.request.*;
import com.medianexus.orchestrator.dto.resources.response.*;
import com.medianexus.orchestrator.dto.taskcenter.request.OpenListReleaseRetryRequest;
import com.medianexus.orchestrator.integration.prowlarr.*;
import com.medianexus.orchestrator.model.*;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProwlarrReleaseIngestServiceTest {
    private static final String MAGNET = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567";
    private final AuthService auth = mock(AuthService.class);
    private final ProwlarrClient client = mock(ProwlarrClient.class);
    private final MagnetIngestService ingest = mock(MagnetIngestService.class);
    private final AnimeMagnetIngestTaskService anime = mock(AnimeMagnetIngestTaskService.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final ReleaseCandidateComparison comparisons = mock(ReleaseCandidateComparison.class);
    private final ReleaseFileTables tables = new ReleaseFileTables(mock(com.medianexus.orchestrator.integration.guangya.GuangyaClient.class), client);
    private final ProwlarrReleaseIngestService service = new ProwlarrReleaseIngestService(auth, ingest, anime, comparisons, tables);

    @BeforeEach void setup() {
        User owner = new User(); owner.setId(1L); when(auth.requireCurrentUser()).thenReturn(owner);
        when(client.resolveTorrent(any(), any())).thenReturn(new ResolvedTorrent(MAGNET, java.util.Optional.empty()));
    }

    @Test void bothEnginesRouteManualAndAutomaticSearchThroughCommonComparison() {
        var movie = new MovieReleaseRecommendationRequest(129, null, "电影", "Movie", 2001, "1080p");
        var series = new SeriesReleaseRecommendationRequest(null, 1, null, "剧集", "Show", 1, "1080p");
        for (boolean panSou : List.of(true, false)) {
            when(settings.usesPanSouForMagnetSearch()).thenReturn(panSou);
            service.recommendMovieRelease(movie);
            service.recommendMovieReleaseForAutomation(movie);
            service.recommendSeriesRelease(series);
            service.recommendSeriesReleaseForAutomation(series);
        }
        verify(comparisons, times(2)).previewMovie(movie);
        verify(comparisons, times(2)).recommendMovie(movie);
        verify(comparisons, times(2)).previewSeries(series);
        verify(comparisons, times(2)).recommendSeries(series);
        verifyNoInteractions(client);
    }

    @Test void listsAndRefreshUseTheSameComparisonProtocol() {
        var movie = new MovieReleaseSearchRequest(129, null, "电影", "Movie", 2001, "1080p");
        var series = new SeriesReleaseSearchRequest(null, 1, null, "剧集", "Show", 1, "1080p");
        service.searchMovieReleases(movie); service.refreshMovieReleases(movie);
        service.searchSeriesReleases(series); service.refreshSeriesReleases(series);
        verify(comparisons).searchMovie(movie); verify(comparisons).refreshMovie(movie);
        verify(comparisons).searchSeries(series); verify(comparisons).refreshSeries(series);
    }

    @Test void selectedMagnetRemainsUsableAfterEngineSwitch() {
        service.ingestSelectedSeries(selected(null, MAGNET, true, "ANIME"));
        var request = ArgumentCaptor.forClass(SeriesMagnetIngestRequest.class);
        var metadata = ArgumentCaptor.forClass(ReleaseIngestMetadata.class);
        verify(ingest).createAnimeSeasonSeriesTask(request.capture(), metadata.capture());
        assertThat(request.getValue().magnet()).isEqualTo(MAGNET);
        assertThat(metadata.getValue().acceptUnverifiedRisk()).isTrue();
        assertThat(metadata.getValue().sourceType()).isEqualTo("PANSOU");
        verifyNoInteractions(client);
    }

    @Test void originalTorrentEvidenceIdentityAvoidsAnotherReferenceFetchOnSelectedSubmit() {
        var release = new ProwlarrRelease("Show S01 1080p", 100L, 5, null, null, "Indexer", null, 7, "guid", "opaque", "0123456789abcdef0123456789abcdef01234567", null);
        tables.remember(release);
        service.ingestSelectedSeries(selected(7, "opaque", false, "SERIES"));
        var request = ArgumentCaptor.forClass(SeriesMagnetIngestRequest.class);
        var metadata = ArgumentCaptor.forClass(ReleaseIngestMetadata.class);
        verify(ingest).createSeriesTask(request.capture(), metadata.capture());
        assertThat(request.getValue().magnet()).isEqualTo(MAGNET);
        assertThat(metadata.getValue().sourceType()).isEqualTo("PROWLARR_RELEASE");
        assertThat(metadata.getValue().releaseGuid()).isEqualTo("opaque");
        verifyNoInteractions(client);
    }

    @Test void selectedSeriesRetryRetainsMetadataAndExplicitPermission() {
        var original = new SeriesMagnetIngestTask(); original.setTaskProductType("ANIME");
        var chain = new TaskRetryReference("group", "SERIES", "old");
        service.ingestSelectedSeriesRetry(original, new OpenListReleaseRetryRequest("Show S01 1080p", "Indexer", 100L,
                7, "opaque", List.of("1080p"), List.of("hdr"), true), chain);
        var metadata = ArgumentCaptor.forClass(ReleaseIngestMetadata.class);
        verify(ingest).createSeriesRetryTask(eq(original), eq(MAGNET), metadata.capture(), eq(chain));
        assertThat(metadata.getValue().releaseTitle()).isEqualTo("Show S01 1080p");
        assertThat(metadata.getValue().acceptUnverifiedRisk()).isTrue();
        assertThat(metadata.getValue().sourceType()).isEqualTo("PROWLARR_RELEASE");
    }

    @Test void independentAnimeRetryStillUsesItsOriginalOwner() {
        var original = new AnimeMagnetIngestTask();
        var chain = new TaskRetryReference("group", "ANIME", "old");
        service.ingestSelectedAnimeRetry(original, new OpenListReleaseRetryRequest("Show S01 1080p", "Indexer", 100L,
                7, "opaque", List.of("1080p"), List.of("hdr")), chain);
        verify(anime).createRetryTask(eq(original), eq(MAGNET), any(ReleaseIngestMetadata.class), eq(chain));
    }

    private SeriesReleaseOpenListIngestRequest selected(Integer indexer, String ref, boolean accepted, String type) {
        return new SeriesReleaseOpenListIngestRequest("Show", "Show", 1, type, "Show S01 1080p", "Indexer", 100L,
                indexer, ref, List.of("1080p"), List.of(), 1, accepted);
    }
}
