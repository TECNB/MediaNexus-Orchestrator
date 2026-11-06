package com.medianexus.orchestrator.service;

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
    private final IngestCompletionNotifier notifier = mock(IngestCompletionNotifier.class);
    private final AutoSymlinkRefreshService refresh = mock(AutoSymlinkRefreshService.class);
    private final OpenListProperties properties = new OpenListProperties();
    private final List<String> savedStatuses = new ArrayList<>();

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
        when(openList.joinPath(anyString(), anyString())).thenAnswer(call -> call.getArgument(0) + "/" + call.getArgument(1));
        when(openList.addOfflineDownload(anyString(), anyString())).thenReturn("offline-1");
        when(openList.offlineTaskInfo("offline-1")).thenReturn(new OpenListOfflineTaskInfo("offline-1", 2, "done", 100, 0L, null));
        when(refresh.refreshMovie()).thenReturn(AutoSymlinkRefreshService.RefreshOutcome.skipped("未启用"));
        when(refresh.refreshSeries()).thenReturn(AutoSymlinkRefreshService.RefreshOutcome.skipped("未启用"));
        when(refresh.refreshAnime()).thenReturn(AutoSymlinkRefreshService.RefreshOutcome.skipped("未启用"));
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

        assertSavedSuccess();
        verify(notifier).movieCompleted(task);
    }

    @Test
    void failedOrganizationNeverSendsCompletionNotice() {
        MovieMagnetIngestTask task = movieTask();
        when(movies.selectById("movie-1")).thenReturn(task);
        files("/media/Movie", "The.Movie.2020.1080p.mkv");
        doThrow(new IllegalStateException("整理失败")).when(organizer).organize(any(), any());

        ReflectionTestUtils.invokeMethod(magnetWorker(), "runMovieTask", task.getId());

        assertThat(savedStatuses).endsWith("FAILED").doesNotContain("SUCCEEDED");
        verifyNoInteractions(notifier);
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

        assertSavedSuccess();
        verify(notifier).seriesCompleted(task);
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
                new AnimeEpisodeRenameService(properties), organizer, null, null, refresh, null, notifier);

        ReflectionTestUtils.invokeMethod(worker, "runTask", task.getId());

        assertSavedSuccess();
        verify(notifier).animeCompleted(task);
    }

    private int recordStatus(LambdaUpdateWrapper<?> update) {
        update.getSqlSet();
        update.getParamNameValuePairs().values().stream()
                .filter(value -> Set.of("SUBMITTED", "DOWNLOADING", "ORGANIZING", "SUCCEEDED", "FAILED").contains(value))
                .map(Object::toString).forEach(savedStatuses::add);
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
        return new MagnetIngestService(movies, mock(MovieMagnetIngestTaskLogMapper.class),
                series, mock(SeriesMagnetIngestTaskLogMapper.class), openList, properties,
                new MovieSeriesFileRenameService(), organizer, null, null, refresh, null, notifier);
    }
}
