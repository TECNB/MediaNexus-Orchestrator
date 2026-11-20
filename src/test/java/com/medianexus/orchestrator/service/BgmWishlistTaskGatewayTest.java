package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.dto.bgm.BgmWishlistOperationContract.Candidate;
import com.medianexus.orchestrator.dto.magnet.response.MovieMagnetIngestTaskResponse;
import com.medianexus.orchestrator.dto.magnet.response.SeriesMagnetIngestTaskResponse;
import com.medianexus.orchestrator.dto.resources.response.ProwlarrReleaseItemResponse;
import com.medianexus.orchestrator.mapper.*;
import com.medianexus.orchestrator.model.User;
import com.medianexus.orchestrator.service.BgmWishlistTaskGateway.Target;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BgmWishlistTaskGatewayTest {
    @Test void onlyNoReleaseMayBecomeNullRecommendation() {
        Fixture f = new Fixture();
        for (String mediaType : List.of("MOVIE", "SERIES")) {
            Target target = f.target(mediaType);
            for (String code : List.of("NO_RELEASE", "EVIDENCE_BUDGET_EXHAUSTED", "SEASON_UNKNOWN")) {
                var failure = new ProwlarrReleaseIngestService.NoMatchingReleaseException(code, "待核验");
                if ("MOVIE".equals(mediaType)) {
                    doThrow(failure).when(f.releases).recommendMovieReleaseForAutomation(any());
                } else {
                    doThrow(failure).when(f.releases).recommendSeriesReleaseForAutomation(any());
                }
                if ("NO_RELEASE".equals(code)) assertNull(f.gateway.recommend(target, "1080p"));
                else assertSame(failure, assertThrows(ProwlarrReleaseIngestService.NoMatchingReleaseException.class,
                        () -> f.gateway.recommend(target, "1080p")));
            }
        }
    }

    @Test void confirmedMovieAnimeAndSeriesPassOnlySelectedCandidateRiskToRequests() {
        for (boolean risk : List.of(false, true)) {
            Fixture f = new Fixture();
            Candidate candidate = new Candidate("chosen", f.release, risk);
            f.gateway.createConfirmed(f.target("MOVIE"), candidate, f.owner);
            verify(f.releases).ingestConfirmedMovieForOwner(argThat(request -> request.acceptUnverifiedRisk() == risk
                    && request.downloadRef().equals(f.release.downloadRef())), eq(f.owner));
            f.gateway.createConfirmed(f.target("SERIES"), candidate, f.owner);
            verify(f.releases).ingestConfirmedAnimeForOwner(argThat(request -> request.acceptUnverifiedRisk() == risk
                    && request.taskProductType().equals("ANIME")), eq(f.owner));
            f.gateway.createConfirmedSeries(f.target("SERIES"), candidate, f.owner);
            verify(f.releases).ingestConfirmedSeriesForOwner(argThat(request -> request.acceptUnverifiedRisk() == risk
                    && request.taskProductType().equals("SERIES")), eq(f.owner));
        }
    }

    @Test void automaticCreatesNeverReceiveRiskAuthorization() {
        Fixture f = new Fixture();
        f.gateway.create(f.target("MOVIE"), f.release, f.owner);
        f.gateway.create(f.target("SERIES"), f.release, f.owner);
        f.gateway.createSeries(f.target("SERIES"), f.release, f.owner);
        verify(f.releases).ingestSelectedMovieForOwner(argThat(request -> !request.acceptUnverifiedRisk()), eq(f.owner));
        verify(f.releases).ingestSelectedAnimeForOwner(argThat(request -> !request.acceptUnverifiedRisk()), eq(f.owner));
        verify(f.releases).ingestSelectedSeriesForOwner(argThat(request -> !request.acceptUnverifiedRisk()), eq(f.owner));
    }

    @Test void chartLedgerFailureLeavesSharedUnknownBarrierForWishlistAndWeChat() {
        Fixture f=new Fixture();
        var ledger=mock(BgmWishlistTargetTaskMapper.class);
        var rows=new java.util.HashMap<String,BgmWishlistTaskGateway.ActiveTask>();
        when(ledger.select(anyString())).thenAnswer(call -> rows.get(call.getArgument(0)));
        doAnswer(call -> { rows.put(call.getArgument(0),call.getArgument(1)); return null; }).when(ledger).insert(anyString(),any());
        doThrow(new IllegalStateException("ledger unavailable")).when(ledger).replace(anyString(),any());
        var locks=mock(BgmWishlistSessionLocks.class);
        when(locks.withLock(anyString(),any(),any())).thenAnswer(call -> call.<Supplier<?>>getArgument(2).get());
        var presence=mock(MediaLibraryPresenceService.class);
        when(presence.checkForAutomation(any())).thenReturn(new MediaLibraryPresenceService.AutomationPresence(true,false,true));
        var gateway=new BgmWishlistTaskGateway(mock(MovieMagnetIngestTaskMapper.class),mock(SeriesMagnetIngestTaskMapper.class),
                mock(AnimeMagnetIngestTaskMapper.class),f.releases,presence,locks,ledger,mock(MediaLibraryDeletionWorkflow.class));
        assertThrows(BgmWishlistTaskGateway.CreatedTaskRecordException.class,()->gateway.createChartMovie(f.target("MOVIE"),f.release,f.owner));
        assertEquals("UNKNOWN",gateway.previousTask(f.target("MOVIE")).status());
        assertThrows(BgmWishlistTaskGateway.EligibilityRefusal.class,()->gateway.create(f.target("MOVIE"),f.release,f.owner));
        verify(f.releases,times(1)).ingestSelectedMovieForOwner(any(),eq(f.owner));
    }

    static class Fixture {
        final ProwlarrReleaseIngestService releases = mock(ProwlarrReleaseIngestService.class);
        final User owner = new User();
        final ProwlarrReleaseItemResponse release = new ProwlarrReleaseItemResponse("中文发布", 1L, 1, 0, 0,
                "indexer", null, 1, "ref", List.of("1080p"), List.of(), List.of("S01"), null, null);
        final BgmWishlistTaskGateway gateway;
        Fixture() {
            var locks = mock(BgmWishlistSessionLocks.class);
            when(locks.withLock(anyString(), any(), any())).thenAnswer(call -> call.<Supplier<?>>getArgument(2).get());
            var presence = mock(MediaLibraryPresenceService.class);
            when(presence.checkForAutomation(any())).thenReturn(new MediaLibraryPresenceService.AutomationPresence(true, false, true));
            gateway = new BgmWishlistTaskGateway(mock(MovieMagnetIngestTaskMapper.class), mock(SeriesMagnetIngestTaskMapper.class),
                    mock(AnimeMagnetIngestTaskMapper.class), releases, presence, locks,
                    mock(BgmWishlistTargetTaskMapper.class), mock(MediaLibraryDeletionWorkflow.class));
            var movie = mock(MovieMagnetIngestTaskResponse.class);
            when(movie.id()).thenReturn("movie-task"); when(movie.status()).thenReturn("PENDING");
            var series = mock(SeriesMagnetIngestTaskResponse.class);
            when(series.id()).thenReturn("series-task"); when(series.status()).thenReturn("PENDING");
            when(releases.ingestSelectedMovieForOwner(any(), any())).thenReturn(movie);
            when(releases.ingestSelectedAnimeForOwner(any(), any())).thenReturn(series);
            when(releases.ingestSelectedSeriesForOwner(any(), any())).thenReturn(series);
            when(releases.ingestConfirmedMovieForOwner(any(), any())).thenReturn(movie);
            when(releases.ingestConfirmedAnimeForOwner(any(), any())).thenReturn(series);
            when(releases.ingestConfirmedSeriesForOwner(any(), any())).thenReturn(series);
        }
        Target target(String type) { return new Target(type, 1, "MOVIE".equals(type) ? null : 1, "作品", "Original", 2024); }
    }
}
