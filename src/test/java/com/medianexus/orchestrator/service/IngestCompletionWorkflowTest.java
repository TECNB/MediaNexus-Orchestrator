package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.support.IngestTargetFixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.medianexus.orchestrator.config.OpenListProperties;
import com.medianexus.orchestrator.integration.openlist.OpenListClient;
import com.medianexus.orchestrator.integration.openlist.OpenListFileInfo;
import com.medianexus.orchestrator.integration.openlist.OpenListOfflineTaskInfo;
import com.medianexus.orchestrator.mapper.AnimeMagnetIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.AnimeMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.MovieMagnetIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.MovieMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.SeriesMagnetIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.SeriesMagnetIngestTaskMapper;
import com.medianexus.orchestrator.model.AnimeMagnetIngestTask;
import com.medianexus.orchestrator.model.MovieMagnetIngestTask;
import com.medianexus.orchestrator.model.SeriesMagnetIngestTask;
import com.medianexus.orchestrator.service.organization.LibraryOrganizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class IngestCompletionWorkflowTest {

    private final MovieMagnetIngestTaskMapper movies = mock(MovieMagnetIngestTaskMapper.class);
    private final SeriesMagnetIngestTaskMapper series = mock(SeriesMagnetIngestTaskMapper.class);
    private final AnimeMagnetIngestTaskMapper anime = mock(AnimeMagnetIngestTaskMapper.class);
    private final OpenListClient openList = mock(OpenListClient.class);
    private final LibraryOrganizer organizer = mock(LibraryOrganizer.class);
    private final IngestTaskNotifier notifier = mock(IngestTaskNotifier.class);
    private final AutoSymlinkRefreshService refresh = mock(AutoSymlinkRefreshService.class);
    private final MovieMagnetIngestTaskLogMapper movieLogs = mock(MovieMagnetIngestTaskLogMapper.class);
    private final SeriesMagnetIngestTaskLogMapper seriesLogs = mock(SeriesMagnetIngestTaskLogMapper.class);
    private final OpenListProperties properties = new OpenListProperties();
    private final List<String> savedStatuses = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final OfflineIngestScheduler scheduler = new OfflineIngestScheduler(openList);
    private final java.util.concurrent.CountDownLatch failed = new java.util.concurrent.CountDownLatch(1);

    @org.junit.jupiter.api.AfterEach
    void closeScheduler() { scheduler.close(); }

    @BeforeEach
    void configureOfflineDependencies() {
        for (Class<?> entity : List.of(MovieMagnetIngestTask.class, SeriesMagnetIngestTask.class, AnimeMagnetIngestTask.class)) {
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                    new org.apache.ibatis.builder.MapperBuilderAssistant(
                            new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), entity);
        }
        properties.setMovieRootPath("/media");
        properties.setTvRootPath("/media");
        properties.setOfflineTimeout(Duration.ofSeconds(2));
        properties.setPollInterval(Duration.ofMillis(1));
        properties.setAnimeRenameTemplate("{title} S{seasonFormat}E{episodeFormat}");
        properties.setAnimeExcludePatterns("");
        when(openList.normalizePath(anyString())).thenAnswer(call -> call.getArgument(0));
        when(openList.offlineDirectoryForPath(anyString())).thenAnswer(call ->
                new OpenListClient.OfflineDirectory("account", "root", call.getArgument(0)));
        when(openList.joinPath(anyString(), anyString())).thenAnswer(call -> call.getArgument(0) + "/" + call.getArgument(1));
        when(openList.addOfflineDownload(anyString(), anyString())).thenReturn("offline-1");
        when(openList.offlineTaskInfo("offline-1")).thenReturn(new OpenListOfflineTaskInfo("offline-1", 2, "done", 100, 0L, null));
        when(refresh.refreshMovie()).thenReturn(AutoSymlinkRefreshService.RefreshOutcome.skipped("未启用"));
        when(refresh.refreshSeries()).thenReturn(AutoSymlinkRefreshService.RefreshOutcome.skipped("未启用"));
        when(refresh.refreshAnime()).thenReturn(AutoSymlinkRefreshService.RefreshOutcome.skipped("未启用"));
        when(refresh.refreshForPath(anyString(), anyString())).thenReturn(AutoSymlinkRefreshService.RefreshOutcome.skipped("未启用"));
        when(movies.update(any())).thenAnswer(call -> recordStatus(call.getArgument(0)));
        when(series.update(any())).thenAnswer(call -> recordStatus(call.getArgument(0)));
        when(anime.update(any())).thenAnswer(call -> recordStatus(call.getArgument(0)));
        doAnswer(call -> { assertSavedSuccess(); return null; }).when(notifier).movieCompleted(any());
        doAnswer(call -> { assertSavedSuccess(); return null; }).when(notifier).seriesCompleted(any());
        doAnswer(call -> { assertSavedSuccess(); return null; }).when(notifier).animeCompleted(any());
    }

    @Test
    void movieWorkerNotifiesOnlyAfterSuccessfulPersistence() {
        MovieMagnetIngestTask task = movieTask();
        when(movies.selectById("movie-1")).thenReturn(task);
        files("/media/Movie", "The.Movie.2020.1080p.mkv");

        ReflectionTestUtils.invokeMethod(magnetWorker(), "runMovieTask", task.getId());

        verify(notifier, org.mockito.Mockito.timeout(2000)).movieCompleted(task);
        assertSavedSuccess();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MOVIE", "SERIES", "ANIME"})
    void visibleFilesNeverReplaceConfirmedDownloadCompletion(String kind) throws Exception {
        properties.setOfflineTimeout(Duration.ofMillis(30));
        when(openList.offlineTaskInfo("offline-1")).thenReturn(new OpenListOfflineTaskInfo("offline-1", 3, "canceling", 99, 0L, null));
        if (kind.equals("MOVIE")) {
            MovieMagnetIngestTask task = movieTask();
            when(movies.selectById(task.getId())).thenReturn(task);
            files(task.getTempPath(), "The.Movie.2020.1080p.mkv");
            ReflectionTestUtils.invokeMethod(magnetWorker(), "runMovieTask", task.getId());
        } else if (kind.equals("SERIES")) {
            SeriesMagnetIngestTask task = new SeriesMagnetIngestTask();
            task.setId("series-1"); task.setSavePath("/media/Show"); task.setTempPath("/media/Show");
            task.setMagnet("magnet:?xt=urn:btih:demo"); task.setTaskProductType("SERIES");
            when(series.selectById(task.getId())).thenReturn(task);
            files(task.getTempPath(), "Show.S01E01.mkv");
            ReflectionTestUtils.invokeMethod(magnetWorker(), "runSeriesTask", task.getId());
        } else {
            AnimeMagnetIngestTask task = new AnimeMagnetIngestTask();
            task.setId("anime-1"); task.setSavePath("/media/Show"); task.setTempPath("/media/Show");
            task.setMagnet("magnet:?xt=urn:btih:demo");
            when(anime.selectById(task.getId())).thenReturn(task);
            files(task.getTempPath(), "Show - 01.mkv");
            AnimeMagnetIngestTaskService worker = new AnimeMagnetIngestTaskService(anime,
                    mock(AnimeMagnetIngestTaskLogMapper.class), openList, properties, new AnimeEpisodeRenameService(properties),
                    organizer, null, null, refresh, null, IngestTargetFixtures.legacy(), notifier);
            ReflectionTestUtils.setField(worker, "offlineScheduler", scheduler);
            ReflectionTestUtils.invokeMethod(worker, "runTask", task.getId());
        }
        assertThat(failed.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        verify(openList, org.mockito.Mockito.never()).findFiles(anyString());
        verify(openList, org.mockito.Mockito.never()).deleteOfflineTask(anyString());
        verifyNoInteractions(organizer);
        assertThat(savedStatuses).doesNotContain("ORGANIZING", "SUCCEEDED");
    }

    @Test
    void restartWithUnresolvedSubmissionBlocksNewSubmissions() {
        when(movies.selectCount(any())).thenReturn(1L);
        MagnetIngestService worker = magnetWorker();
        worker.markUnfinishedTasksInterrupted();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> scheduler.enqueue("/new", "new",
                Duration.ofSeconds(2), Duration.ofMillis(1), mock(OfflineIngestScheduler.Download.class)))
                .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
        verify(openList, org.mockito.Mockito.never()).addOfflineDownload(anyString(), anyString());
    }

    @Test
    void failedOrganizationNeverSendsCompletionNotice() throws Exception {
        MovieMagnetIngestTask task = movieTask();
        when(movies.selectById("movie-1")).thenReturn(task);
        files("/media/Movie", "The.Movie.2020.1080p.mkv");
        doThrow(new IllegalStateException("整理失败")).when(organizer).organize(any(), any());

        ReflectionTestUtils.invokeMethod(magnetWorker(), "runMovieTask", task.getId());

        assertThat(failed.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(savedStatuses).endsWith("FAILED").doesNotContain("SUCCEEDED");
        verify(notifier, org.mockito.Mockito.never()).movieCompleted(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SERIES", "ANIME"})
    void televisionAndSharedAnimeWorkersNotifyAfterSuccessfulPersistence(String productType) {
        SeriesMagnetIngestTask task = new SeriesMagnetIngestTask();
        task.setId("series-1");
        task.setTitle("Show");
        task.setSeasonNumber(1);
        task.setTaskProductType(productType);
        task.setSavePath("/media/Show");
        task.setTempPath("/media/Show");
        task.setMagnet("magnet:?xt=urn:btih:demo");
        when(series.selectById(task.getId())).thenReturn(task);
        files("/media/Show", "Show.S01E01.mkv");

        ReflectionTestUtils.invokeMethod(magnetWorker(), "runSeriesTask", task.getId());

        verify(notifier, org.mockito.Mockito.timeout(2000)).seriesCompleted(task);
        assertSavedSuccess();
    }

    @Test
    void independentAnimeWorkerNotifiesAfterSuccessfulPersistence() {
        AnimeMagnetIngestTask task = new AnimeMagnetIngestTask();
        task.setId("anime-1");
        task.setTitle("Show");
        task.setSeasonNumber(1);
        task.setSavePath("/media/Show");
        task.setTempPath("/media/Show");
        task.setMagnet("magnet:?xt=urn:btih:demo");
        when(anime.selectById(task.getId())).thenReturn(task);
        files("/media/Show", "[Group] Show - 01.mkv");
        AnimeMagnetIngestTaskService worker = new AnimeMagnetIngestTaskService(
                anime, mock(AnimeMagnetIngestTaskLogMapper.class), openList, properties,
                new AnimeEpisodeRenameService(properties), organizer, null, null, refresh, null, IngestTargetFixtures.legacy(), notifier);

        ReflectionTestUtils.setField(worker, "offlineScheduler", scheduler);
        ReflectionTestUtils.invokeMethod(worker, "runTask", task.getId());

        verify(notifier, org.mockito.Mockito.timeout(2000)).animeCompleted(task);
        assertSavedSuccess();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MOVIE", "SERIES"})
    void acceptedIdentityLogConnectionFailureDoesNotStrandDurableDownload(String kind) {
        if (kind.equals("MOVIE")) {
            var task = movieTask();
            when(movies.selectById(task.getId())).thenReturn(task);
            files(task.getTempPath(), "The.Movie.2020.1080p.mkv");
            when(movieLogs.insert(any(com.medianexus.orchestrator.model.MovieMagnetIngestTaskLog.class))).thenAnswer(call -> {
                com.medianexus.orchestrator.model.MovieMagnetIngestTaskLog entry = call.getArgument(0);
                if ("OpenList 离线任务已创建".equals(entry.getMessage())) throw jdbcUnavailable();
                return 1;
            });
            ReflectionTestUtils.invokeMethod(magnetWorker(), "runMovieTask", task.getId());
            verify(notifier, org.mockito.Mockito.timeout(2000)).movieCompleted(task);
            verify(movies, org.mockito.Mockito.times(4)).update(any());
        } else {
            var task = new SeriesMagnetIngestTask();
            task.setId("series-1"); task.setTitle("Show"); task.setSeasonNumber(1);
            task.setTaskProductType("SERIES"); task.setSavePath("/media/Show"); task.setTempPath("/media/Show");
            task.setMagnet("magnet:?xt=urn:btih:demo");
            when(series.selectById(task.getId())).thenReturn(task);
            files(task.getTempPath(), "Show.S01E01.mkv");
            when(seriesLogs.insert(any(com.medianexus.orchestrator.model.SeriesMagnetIngestTaskLog.class))).thenAnswer(call -> {
                com.medianexus.orchestrator.model.SeriesMagnetIngestTaskLog entry = call.getArgument(0);
                if ("OpenList 离线任务已创建".equals(entry.getMessage())) throw jdbcUnavailable();
                return 1;
            });
            ReflectionTestUtils.invokeMethod(magnetWorker(), "runSeriesTask", task.getId());
            verify(notifier, org.mockito.Mockito.timeout(2000)).seriesCompleted(task);
            verify(series, org.mockito.Mockito.times(4)).update(any());
        }
        assertSavedSuccess();
        verify(openList, org.mockito.Mockito.times(1)).addOfflineDownload(anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MOVIE", "SERIES"})
    void requiredOrganizingCheckpointPrecedesUpstreamRecordDeletion(String kind) throws Exception {
        var checkpointFailed = new java.util.concurrent.CountDownLatch(1);
        if (kind.equals("MOVIE")) {
            var task = movieTask();
            when(movies.selectById(task.getId())).thenReturn(task);
            doAnswer(call -> {
                LambdaUpdateWrapper<?> update = call.getArgument(0); update.getSqlSet();
                if (update.getParamNameValuePairs().containsValue("ORGANIZING")) { checkpointFailed.countDown(); throw jdbcUnavailable(); }
                return recordStatus(call.getArgument(0));
            }).when(movies).update(any());
            ReflectionTestUtils.invokeMethod(magnetWorker(), "runMovieTask", task.getId());
        } else {
            var task = new SeriesMagnetIngestTask();
            task.setId("series-1"); task.setTaskProductType("SERIES");
            task.setSavePath("/media/Show"); task.setTempPath("/media/Show"); task.setMagnet("magnet:?xt=urn:btih:demo");
            when(series.selectById(task.getId())).thenReturn(task);
            doAnswer(call -> {
                LambdaUpdateWrapper<?> update = call.getArgument(0); update.getSqlSet();
                if (update.getParamNameValuePairs().containsValue("ORGANIZING")) { checkpointFailed.countDown(); throw jdbcUnavailable(); }
                return recordStatus(call.getArgument(0));
            }).when(series).update(any());
            ReflectionTestUtils.invokeMethod(magnetWorker(), "runSeriesTask", task.getId());
        }
        assertThat(checkpointFailed.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(failed.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        verify(openList, org.mockito.Mockito.never()).deleteOfflineTask(anyString());
        verifyNoInteractions(organizer);
    }

    @Test
    void failureStatusIsDurableBeforeOptionalFailureLogConnectionError() throws Exception {
        var task = movieTask();
        when(movies.selectById(task.getId())).thenReturn(task);
        when(movieLogs.insert(any(com.medianexus.orchestrator.model.MovieMagnetIngestTaskLog.class))).thenAnswer(call -> {
            com.medianexus.orchestrator.model.MovieMagnetIngestTaskLog entry = call.getArgument(0);
            if ("failed".equals(entry.getStage())) throw jdbcUnavailable();
            return 1;
        });
        ReflectionTestUtils.invokeMethod(magnetWorker(), "markMovieFailed", task.getId(), "captured original failure");
        assertThat(savedStatuses).endsWith("FAILED");
        verify(notifier).failed("movie", task.getId(), null, task.getTitle(), null, "captured original failure");
    }

    @ParameterizedTest
    @ValueSource(strings = {"MOVIE", "SERIES"})
    void explicitCheckpointRecoveryUpdatesTaskRowsWithoutReplayingCallbackLogs(String kind) {
        var download = new java.util.concurrent.atomic.AtomicReference<OfflineIngestScheduler.Download>();
        var captureScheduler = mock(OfflineIngestScheduler.class);
        when(captureScheduler.enqueue(anyString(), org.mockito.ArgumentMatchers.nullable(String.class), any(), any(), any()))
                .thenAnswer(call -> {
                    download.set(call.getArgument(4));
                    return new java.util.concurrent.CompletableFuture<Void>();
                });
        var worker = magnetWorker();
        ReflectionTestUtils.setField(worker, "offlineScheduler", captureScheduler);
        var attempts = new java.util.concurrent.ConcurrentHashMap<String, Integer>();
        org.mockito.stubbing.Answer<Integer> checkpoint = call -> {
            LambdaUpdateWrapper<?> update = call.getArgument(0); update.getSqlSet();
            String status = update.getParamNameValuePairs().values().stream()
                    .filter(value -> Set.of("DOWNLOADING", "FAILED").contains(value))
                    .map(Object::toString).findFirst().orElseThrow();
            if (attempts.merge(status, 1, Integer::sum) == 1) throw jdbcUnavailable();
            return recordStatus(call.getArgument(0));
        };
        if (kind.equals("MOVIE")) {
            var task = movieTask();
            when(movies.selectById(task.getId())).thenReturn(task);
            doAnswer(checkpoint).when(movies).update(any());
            ReflectionTestUtils.invokeMethod(worker, "runMovieTask", task.getId());
        } else {
            var task = new SeriesMagnetIngestTask();
            task.setId("series-1"); task.setSavePath("/media/Show");
            when(series.selectById(task.getId())).thenReturn(task);
            doAnswer(checkpoint).when(series).update(any());
            ReflectionTestUtils.invokeMethod(worker, "runSeriesTask", task.getId());
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> download.get().submitted("offline-1"))
                .isInstanceOf(org.springframework.jdbc.CannotGetJdbcConnectionException.class);
        download.get().recoverSubmittedCheckpoint("offline-1");
        var failure = new IllegalStateException("captured failure");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> download.get().failed(failure))
                .isInstanceOf(org.springframework.jdbc.CannotGetJdbcConnectionException.class);
        download.get().recoverFailureCheckpoint(failure);
        assertThat(savedStatuses).containsExactly("DOWNLOADING", "FAILED");
        assertThat(attempts).containsEntry("DOWNLOADING", 2).containsEntry("FAILED", 2);
        verifyNoInteractions(movieLogs, seriesLogs);
        verifyNoInteractions(openList);
    }

    private static org.springframework.jdbc.CannotGetJdbcConnectionException jdbcUnavailable() {
        return new org.springframework.jdbc.CannotGetJdbcConnectionException("test connection unavailable");
    }

    private int recordStatus(LambdaUpdateWrapper<?> update) {
        update.getSqlSet();
        update.getParamNameValuePairs().values().stream()
                .filter(value -> Set.of("SUBMITTED", "DOWNLOADING", "ORGANIZING", "SUCCEEDED", "FAILED").contains(value))
                .map(Object::toString).forEach(savedStatuses::add);
        if (savedStatuses.contains("FAILED")) failed.countDown();
        return 1;
    }

    private void assertSavedSuccess() {
        assertThat(savedStatuses).endsWith("SUCCEEDED").doesNotContain("FAILED");
    }

    private void files(String path, String name) {
        List<OpenListFileInfo> files = List.of(new OpenListFileInfo(name, 1024L * 1024 * 1024, false, path));
        when(openList.findFiles(path)).thenReturn(files);
        when(openList.listFiles(path)).thenReturn(files);
    }

    private MovieMagnetIngestTask movieTask() {
        MovieMagnetIngestTask task = new MovieMagnetIngestTask();
        task.setId("movie-1");
        task.setTitle("The Movie");
        task.setYear(2020);
        task.setSavePath("/media/Movie");
        task.setTempPath("/media/Movie");
        task.setMagnet("magnet:?xt=urn:btih:demo");
        return task;
    }

    private MagnetIngestService magnetWorker() {
        MagnetIngestService worker = new MagnetIngestService(movies, movieLogs,
                series, seriesLogs, openList, properties,
                new MovieSeriesFileRenameService(), organizer, null, null, refresh, null, IngestTargetFixtures.legacy(), notifier);
        ReflectionTestUtils.setField(worker, "offlineScheduler", scheduler);
        return worker;
    }
}
