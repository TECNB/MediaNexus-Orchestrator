package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.medianexus.orchestrator.config.OpenListProperties;
import com.medianexus.orchestrator.dto.magnet.request.MovieMagnetIngestRequest;
import com.medianexus.orchestrator.dto.magnet.request.SeriesMagnetIngestRequest;
import com.medianexus.orchestrator.dto.resources.request.MovieReleaseOpenListIngestRequest;
import com.medianexus.orchestrator.dto.resources.request.SeriesReleaseOpenListIngestRequest;
import com.medianexus.orchestrator.dto.taskcenter.request.OpenListReleaseRetryRequest;
import com.medianexus.orchestrator.integration.guangya.GuangyaClient;
import com.medianexus.orchestrator.integration.guangya.GuangyaException;
import com.medianexus.orchestrator.integration.openlist.OpenListClient;
import com.medianexus.orchestrator.integration.prowlarr.ProwlarrClient;
import com.medianexus.orchestrator.mapper.*;
import com.medianexus.orchestrator.model.MovieMagnetIngestTask;
import com.medianexus.orchestrator.model.SeriesMagnetIngestTask;
import com.medianexus.orchestrator.model.User;
import com.medianexus.orchestrator.service.organization.LibraryOrganizer;
import com.medianexus.orchestrator.support.IngestTargetFixtures;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class ReleaseSubmissionAcceptanceTest {
    private static final String MAGNET = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567";
    private final MovieMagnetIngestTaskMapper movies = mock(MovieMagnetIngestTaskMapper.class);
    private final SeriesMagnetIngestTaskMapper series = mock(SeriesMagnetIngestTaskMapper.class);
    private final MovieMagnetIngestTaskLogMapper movieLogs = mock(MovieMagnetIngestTaskLogMapper.class);
    private final UserActionQuotaService quota = mock(UserActionQuotaService.class);
    private final ExecutorService executor = mock(ExecutorService.class);
    private final OpenListClient downloads = mock(OpenListClient.class);
    private final User owner = new User();
    private MagnetIngestService ingest;
    private ProwlarrReleaseIngestService selected;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "movie"), MovieMagnetIngestTask.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "series"), SeriesMagnetIngestTask.class);
        owner.setId(1L);
        AuthService auth = mock(AuthService.class);
        when(auth.requireCurrentUser()).thenReturn(owner);
        OpenListProperties paths = new OpenListProperties();
        paths.setMovieRootPath("/movies");
        paths.setTvRootPath("/series");
        paths.setAnimePathTemplate("/anime/{title}/Season {season}");
        when(downloads.joinPath(anyString(), anyString())).thenAnswer(call -> call.getArgument(0) + "/" + call.getArgument(1));
        var organizer = mock(LibraryOrganizer.class);
        ingest = new MagnetIngestService(movies, movieLogs, series, mock(SeriesMagnetIngestTaskLogMapper.class),
                downloads, paths, new MovieSeriesFileRenameService(), organizer, organizer, organizer,
                auth, quota, mock(AutoSymlinkRefreshService.class), mock(MediaLibraryPresenceService.class),
                IngestTargetFixtures.legacy(), executor, mock(IngestTaskNotifier.class));
        var resolver = mock(GuangyaClient.class);
        when(resolver.resolve(anyString(), any())).thenThrow(new GuangyaException(GuangyaException.Reason.UNAVAILABLE, "超时"));
        ReflectionTestUtils.setField(ingest, "submissionSafety", new ReleaseSubmissionSafety(
                new ReleaseFileTables(resolver, mock(ProwlarrClient.class)),
                new ReleaseFileAssessment(new MovieSeriesFileRenameService(), new ReleaseTitleTagParser())));
        var prowlarr = mock(ProwlarrClient.class);
        when(prowlarr.resolveTorrent(any(), any())).thenReturn(new com.medianexus.orchestrator.integration.prowlarr.ResolvedTorrent(MAGNET, java.util.Optional.empty()));
        selected = new ProwlarrReleaseIngestService(auth, ingest,
                mock(AnimeMagnetIngestTaskService.class), mock(ReleaseCandidateComparison.class), new ReleaseFileTables(resolver, prowlarr));
    }

    @Test
    void rawMovieSeriesAnimeSeasonAndRetryRejectBeforeQuotaWritesOrDownload() {
        assertThatThrownBy(() -> ingest.createMovieTask(new MovieMagnetIngestRequest(MAGNET, "Arrival", null, 2016)))
                .hasMessageContaining("accept_unverified_risk");
        assertThatThrownBy(() -> ingest.createSeriesTask(new SeriesMagnetIngestRequest(MAGNET, "Show", null, 1)))
                .hasMessageContaining("accept_unverified_risk");
        assertThatThrownBy(() -> ingest.createAnimeSeasonSeriesTask(new SeriesMagnetIngestRequest(MAGNET, "Show", null, 1), ReleaseIngestMetadata.manual()))
                .hasMessageContaining("accept_unverified_risk");
        assertThatThrownBy(() -> ingest.createMovieRetryTask(movieTask(), MAGNET, chain())).hasMessageContaining("accept_unverified_risk");
        assertThatThrownBy(() -> ingest.createSeriesRetryTask(seriesTask(), MAGNET, chain())).hasMessageContaining("accept_unverified_risk");
        verifyNoInteractions(quota, executor, movieLogs);
        verify(movies, never()).insert(any(MovieMagnetIngestTask.class));
        verify(downloads, never()).addOfflineDownload(anyString(), anyString());
        verify(series, never()).insert(any(SeriesMagnetIngestTask.class));
    }

    @Test
    void rawPermissionReachesCommonSafetyAndWritesExplicitRiskLog() {
        ingest.createMovieTask(new MovieMagnetIngestRequest(MAGNET, "Arrival", null, 2016, null, true));
        verify(quota).consumeDailyContentCreate(eq(owner), any());
        verify(movies).insert(any(MovieMagnetIngestTask.class));
        ArgumentCaptor<com.medianexus.orchestrator.model.MovieMagnetIngestTaskLog> logs = ArgumentCaptor.forClass(com.medianexus.orchestrator.model.MovieMagnetIngestTaskLog.class);
        verify(movieLogs, times(2)).insert(logs.capture());
        assertThat(logs.getAllValues()).anySatisfy(log -> assertThat(log.getDetail()).contains("显式接受未核验风险"));
        // Worker is mocked: this suite never creates an actual download.
        verify(executor).submit(any(Runnable.class));
    }

    @Test
    void resolvedProwlarrMovieSelectedAndRetryCannotBypassCommonSafety() {
        var request = new MovieReleaseOpenListIngestRequest("Arrival", null, 2016, "Arrival 2016 1080p",
                "Indexer", null, 1, "opaque-ref", List.of("1080p"), List.of(), null, false);
        assertThatThrownBy(() -> selected.ingestSelectedMovie(request)).hasMessageContaining("accept_unverified_risk");
        assertThatThrownBy(() -> selected.ingestSelectedMovieRetry(movieTask(), new OpenListReleaseRetryRequest(
                request.releaseTitle(), request.indexer(), null, 1, request.downloadRef(), List.of("1080p"), List.of()), chain()))
                .hasMessageContaining("accept_unverified_risk");
        verifyNoInteractions(quota, executor);
        selected.ingestSelectedMovie(new MovieReleaseOpenListIngestRequest("Arrival", null, 2016, request.releaseTitle(),
                request.indexer(), null, 1, request.downloadRef(), List.of("1080p"), List.of(), null, true));
        verify(movies).insert(any(MovieMagnetIngestTask.class));
    }

    @Test
    void seriesAndAnimeSeasonSelectedPermissionAndRetryAreClosed() {
        for (String type : List.of("SERIES", "ANIME")) {
            assertThatThrownBy(() -> selected.ingestSelectedSeries(seriesRequest(type, false)))
                    .hasMessageContaining("accept_unverified_risk");
            selected.ingestSelectedSeries(seriesRequest(type, true));
        }
        verify(series, times(2)).insert(any(SeriesMagnetIngestTask.class));
        assertThatThrownBy(() -> selected.ingestSelectedSeriesRetry(seriesTask(), new OpenListReleaseRetryRequest(
                "Show S01 1080p", "Indexer", null, 1, "opaque", List.of("1080p"), List.of()), chain()))
                .hasMessageContaining("accept_unverified_risk");
        selected.ingestSelectedSeriesRetry(seriesTask(), new OpenListReleaseRetryRequest(
                "Show S01 1080p", "Indexer", null, 1, "opaque", List.of("1080p"), List.of(), true), chain());
        verify(series, times(3)).insert(any(SeriesMagnetIngestTask.class));
    }

    @Test
    void ownerAutomationCannotBorrowManualRiskPermission() {
        var permission = ReleaseIngestMetadata.manual().withUnverifiedRisk(true);
        assertThatThrownBy(() -> ingest.createMovieTaskForOwner(new MovieMagnetIngestRequest(MAGNET, "Arrival", null, 2016, null, true), permission, owner))
                .hasMessageContaining("accept_unverified_risk");
        assertThatThrownBy(() -> ingest.createSeriesTaskForOwner(new SeriesMagnetIngestRequest(MAGNET, "Show", null, 1, null, true), permission, owner))
                .hasMessageContaining("accept_unverified_risk");
        assertThatThrownBy(() -> ingest.createAnimeSeasonTaskForOwner(new SeriesMagnetIngestRequest(MAGNET, "Show", null, 1, null, true), permission, owner))
                .hasMessageContaining("accept_unverified_risk");
        verifyNoInteractions(quota, executor);
    }

    @Test
    void ownerReleaseAutomationDiscardsExplicitPermissionForAllProducts() {
        assertThatThrownBy(() -> selected.ingestSelectedMovieForOwner(confirmedMovieRequest(true), owner))
                .hasMessageContaining("accept_unverified_risk");
        assertThatThrownBy(() -> selected.ingestSelectedAnimeForOwner(seriesRequest("ANIME", true), owner))
                .hasMessageContaining("accept_unverified_risk");
        assertThatThrownBy(() -> selected.ingestSelectedSeriesForOwner(seriesRequest("SERIES", true), owner))
                .hasMessageContaining("accept_unverified_risk");
        verifyNoInteractions(quota, executor);
    }

    @Test
    void confirmedOwnerRequiresExplicitPermissionAndPreservesItForAllProducts() {
        assertThatThrownBy(() -> selected.ingestConfirmedMovieForOwner(confirmedMovieRequest(false), owner))
                .hasMessageContaining("accept_unverified_risk");
        assertThatThrownBy(() -> selected.ingestConfirmedAnimeForOwner(seriesRequest("ANIME", false), owner))
                .hasMessageContaining("accept_unverified_risk");
        assertThatThrownBy(() -> selected.ingestConfirmedSeriesForOwner(seriesRequest("SERIES", false), owner))
                .hasMessageContaining("accept_unverified_risk");
        verifyNoInteractions(quota, executor);

        selected.ingestConfirmedMovieForOwner(confirmedMovieRequest(true), owner);
        selected.ingestConfirmedAnimeForOwner(seriesRequest("ANIME", true), owner);
        selected.ingestConfirmedSeriesForOwner(seriesRequest("SERIES", true), owner);
        verify(movies).insert(any(MovieMagnetIngestTask.class));
        verify(series, times(2)).insert(any(SeriesMagnetIngestTask.class));
        verify(quota, times(3)).consumeDailyContentCreate(eq(owner), any());
        verify(executor, times(3)).submit(any(Runnable.class));
    }

    @Test
    void confirmedOwnerCannotOverrideKnownConflicts() {
        var movie = new MovieReleaseOpenListIngestRequest("Arrival", null, 2016, "Arrival 2017 1080p",
                "Indexer", null, 1, "opaque", List.of("1080p"), List.of(), null, true);
        assertThatThrownBy(() -> selected.ingestConfirmedMovieForOwner(movie, owner))
                .hasMessageContaining("年份");
        String seriesMagnet = "magnet:?xt=urn:btih:1123456789abcdef0123456789abcdef01234567";
        var anime = new SeriesReleaseOpenListIngestRequest("Show", null, 1, "ANIME", "Show S02 1080p",
                "Indexer", null, null, seriesMagnet, List.of("1080p"), List.of(), null, true);
        assertThatThrownBy(() -> selected.ingestConfirmedAnimeForOwner(anime, owner)).hasMessageContaining("季");
        assertThatThrownBy(() -> selected.ingestConfirmedSeriesForOwner(anime, owner)).hasMessageContaining("季");
        verifyNoInteractions(quota, executor);
        verify(movies, never()).insert(any(MovieMagnetIngestTask.class));
        verify(series, never()).insert(any(SeriesMagnetIngestTask.class));
    }

    @Test
    void confirmedOwnerStillRejectsAnActiveDuplicateBeforeInsertion() {
        when(movies.selectOne(any())).thenReturn(movieTask());
        when(series.selectOne(any())).thenReturn(seriesTask());
        assertThatThrownBy(() -> selected.ingestConfirmedMovieForOwner(confirmedMovieRequest(true), owner))
                .hasMessageContaining("相同 magnet");
        assertThatThrownBy(() -> selected.ingestConfirmedAnimeForOwner(seriesRequest("ANIME", true), owner))
                .hasMessageContaining("相同 magnet");
        assertThatThrownBy(() -> selected.ingestConfirmedSeriesForOwner(seriesRequest("SERIES", true), owner))
                .hasMessageContaining("相同 magnet");
        verifyNoInteractions(quota, executor);
    }

    private MovieReleaseOpenListIngestRequest confirmedMovieRequest(boolean accepted) {
        return new MovieReleaseOpenListIngestRequest("Arrival", null, 2016, "Arrival 2016 1080p",
                "Indexer", null, null, "magnet:?xt=urn:btih:2123456789abcdef0123456789abcdef01234567",
                List.of("1080p"), List.of(), null, accepted);
    }


    private SeriesReleaseOpenListIngestRequest seriesRequest(String type, boolean accepted) {
        return new SeriesReleaseOpenListIngestRequest("Show", null, 1, type, "Show S01 1080p", "Indexer",
                null, 1, "opaque", List.of("1080p"), List.of(), null, accepted);
    }
    private MovieMagnetIngestTask movieTask() {
        var task = new MovieMagnetIngestTask(); task.setTitle("Arrival"); task.setYear(2016); task.setSavePath("/movies/Arrival (2016)"); return task;
    }
    private SeriesMagnetIngestTask seriesTask() {
        var task = new SeriesMagnetIngestTask(); task.setTitle("Show"); task.setSeasonNumber(1); task.setSavePath("/series/Show/Season 1"); return task;
    }
    private TaskRetryReference chain() { return new TaskRetryReference("group", "MOVIE", "old"); }
}
