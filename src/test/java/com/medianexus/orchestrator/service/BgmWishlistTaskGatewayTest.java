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

    @Test void manualMovieTaskIsAssociatedWhenAutomationLedgerIsAbsent() {
        var movies = mock(MovieMagnetIngestTaskMapper.class);
        var task = new com.medianexus.orchestrator.model.MovieMagnetIngestTask();
        task.setId("manual-movie"); task.setTmdbId(1); task.setStatus("SUCCEEDED");
        when(movies.selectOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(task);
        when(movies.selectById("manual-movie")).thenReturn(task);
        var gateway = new BgmWishlistTaskGateway(movies, mock(SeriesMagnetIngestTaskMapper.class),
                mock(AnimeMagnetIngestTaskMapper.class), mock(ProwlarrReleaseIngestService.class),
                mock(MediaLibraryPresenceService.class), mock(BgmWishlistSessionLocks.class),
                mock(BgmWishlistTargetTaskMapper.class), mock(MediaLibraryDeletionWorkflow.class));
        assertEquals(new BgmWishlistTaskGateway.ActiveTask("MOVIE", "manual-movie", "SUCCEEDED"),
                gateway.previousTask(new Target("MOVIE", 1, null, "作品", "Original", 2026)));
    }

    @Test void manualSeriesAssociationUsesExactSeasonAndRetainsFailedAttempt() {
        var series = mock(SeriesMagnetIngestTaskMapper.class);
        var task = new com.medianexus.orchestrator.model.SeriesMagnetIngestTask();
        task.setId("manual-season"); task.setTmdbId(17); task.setSeasonNumber(2); task.setStatus("FAILED");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "series"),
                com.medianexus.orchestrator.model.SeriesMagnetIngestTask.class);
        when(series.selectOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenAnswer(call -> {
            var query = call.getArgument(0, com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
            assertTrue(query.getSqlSegment().contains("tmdb_id ="));
            assertTrue(query.getSqlSegment().contains("season_number ="));
            assertTrue(query.getParamNameValuePairs().containsValue(17));
            assertTrue(query.getParamNameValuePairs().containsValue(2));
            return task;
        });
        when(series.selectById("manual-season")).thenReturn(task);
        var gateway = new BgmWishlistTaskGateway(mock(MovieMagnetIngestTaskMapper.class), series,
                mock(AnimeMagnetIngestTaskMapper.class), mock(ProwlarrReleaseIngestService.class),
                mock(MediaLibraryPresenceService.class), mock(BgmWishlistSessionLocks.class),
                mock(BgmWishlistTargetTaskMapper.class), mock(MediaLibraryDeletionWorkflow.class));
        assertEquals(new BgmWishlistTaskGateway.ActiveTask("SERIES", "manual-season", "FAILED"),
                gateway.previousTask(new Target("SERIES", 17, 2, "作品", "Original", 2026)));
    }

    @Test void deletionHistoryDoesNotResurrectUnrecordedManualTask() {
        for (String previous : java.util.Arrays.asList(null, "deleted-manual-task")) {
            var movies = mock(MovieMagnetIngestTaskMapper.class);
            var deletions = mock(MediaLibraryDeletionWorkflow.class);
            var deleted = mock(com.medianexus.orchestrator.dto.admin.response.AdminMediaDeletionTaskResponse.class);
            when(deleted.status()).thenReturn("SUCCEEDED");
            when(deletions.latestBgmDeletion(anyString())).thenReturn(new MediaLibraryDeletionWorkflow.BgmDeletion(deleted, previous));
            var gateway = new BgmWishlistTaskGateway(movies, mock(SeriesMagnetIngestTaskMapper.class),
                    mock(AnimeMagnetIngestTaskMapper.class), mock(ProwlarrReleaseIngestService.class),
                    mock(MediaLibraryPresenceService.class), mock(BgmWishlistSessionLocks.class),
                    mock(BgmWishlistTargetTaskMapper.class), deletions);
            assertNull(gateway.previousTask(new Target("MOVIE", 1, null, "作品", "Original", 2026)));
            verifyNoInteractions(movies);
        }
    }

    @Test void newerLegacyAnimeTaskWinsOverOlderSeriesAttempt() {
        var series = mock(SeriesMagnetIngestTaskMapper.class);
        var anime = mock(AnimeMagnetIngestTaskMapper.class);
        var failed = new com.medianexus.orchestrator.model.SeriesMagnetIngestTask();
        failed.setId("old-series"); failed.setStatus("FAILED"); failed.setCreatedAt(java.time.LocalDateTime.of(2026, 11, 1, 0, 0));
        var succeeded = new com.medianexus.orchestrator.model.AnimeMagnetIngestTask();
        succeeded.setId("new-anime"); succeeded.setStatus("SUCCEEDED"); succeeded.setCreatedAt(failed.getCreatedAt().plusDays(1));
        when(series.selectOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(failed);
        when(anime.selectOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(succeeded);
        when(anime.selectById("new-anime")).thenReturn(succeeded);
        var gateway = new BgmWishlistTaskGateway(mock(MovieMagnetIngestTaskMapper.class), series, anime,
                mock(ProwlarrReleaseIngestService.class), mock(MediaLibraryPresenceService.class),
                mock(BgmWishlistSessionLocks.class), mock(BgmWishlistTargetTaskMapper.class), mock(MediaLibraryDeletionWorkflow.class));
        assertEquals(new BgmWishlistTaskGateway.ActiveTask("ANIME", "new-anime", "SUCCEEDED"),
                gateway.previousTask(new Target("SERIES", 17, 2, "作品", "Original", 2026)));
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
