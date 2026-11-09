package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.TmdbProperties;
import com.medianexus.orchestrator.config.WeComProperties;
import com.medianexus.orchestrator.integration.tmdb.TmdbClient;
import com.medianexus.orchestrator.integration.wecom.WeComClient;
import com.medianexus.orchestrator.integration.wecom.WeComClient.Recipient;
import com.medianexus.orchestrator.integration.wecom.WeComClient.Scope;
import com.medianexus.orchestrator.model.AnimeMagnetIngestTask;
import com.medianexus.orchestrator.model.MovieMagnetIngestTask;
import com.medianexus.orchestrator.model.SeriesMagnetIngestTask;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class IngestTaskNotifierTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final WeComProperties properties = new WeComProperties();
    private final TmdbProperties tmdbProperties = new TmdbProperties();
    private final TmdbClient tmdb = mock(TmdbClient.class);
    private final WeComClient wecom = mock(WeComClient.class);
    private final WeComBindings bindings = mock(WeComBindings.class);
    private final Recipient recipient = new Recipient(new Scope("corp", 1L), "member");
    private final IngestExceptionNotifier exceptions = mock(IngestExceptionNotifier.class);
    private final ControlledExecutor executor = new ControlledExecutor();
    private final IngestTaskNotifier notifier = new IngestTaskNotifier(
            properties, tmdbProperties, tmdb, wecom, executor, exceptions, bindings);

    @BeforeEach
    void configure() {
        properties.setEnabled(true);
        when(bindings.recipient(99L)).thenReturn(recipient);
        properties.setFrontendBaseUrl("https://media.example/");
        tmdbProperties.setImageBaseUrl("https://image.tmdb.org/t/p/w500");
    }

    @Test
    void organizingFailureDoesNotRecommendChangingTorrent() {
        notifier.failed("series", "series-2", 99L, "作品", "organizing", "下载文件整理失败");
        executor.runPending();
        verify(exceptions).send(eq(recipient), eq("task:series:series-2"), eq("入库处理失败｜作品"), contains("查看具体失败阶段"), eq("/tasks/series/series-2"));
    }

    @Test
    void finalDownloadTimeoutUsesTextNotificationAndActualTaskLinkWithoutLoadingImages() {
        notifier.failed("series", "series-1", 99L, "作品", "downloading", "OpenList 离线下载超时");
        verifyNoInteractions(exceptions, tmdb, wecom);
        executor.runPending();
        verify(exceptions).send(eq(recipient), eq("task:series:series-1"), eq("下载超时｜作品"), contains("更换种子"), eq("/tasks/series/series-1"));
        verifyNoInteractions(tmdb, wecom);
    }

    @Test
    void queuesAfterCompletionWithoutRunningNetworkInTheCaller() throws Exception {
        MovieMagnetIngestTask task = movie();
        when(tmdb.getMovieImages(157336, tmdbProperties)).thenReturn(mapper.readTree("""
                {"backdrops":[{"file_path":"/backdrop.jpg","iso_639_1":null}],
                 "posters":[{"file_path":"/poster.jpg"}]}
                """));

        notifier.movieCompleted(task);
        verifyNoInteractions(tmdb, wecom);
        executor.runPending();

        verify(wecom).sendNews(eq(recipient), eq("星际穿越（2014） · 任务处理完成"), contains("电影入库任务"),
                eq("https://image.tmdb.org/t/p/w500/backdrop.jpg"), eq("https://media.example/tasks/movie/movie-1"));
        assertThat(task.getStatus()).isEqualTo("SUCCEEDED");
    }

    @Test
    void doesNotUseLegacyRecipientForUnboundOrOwnerlessTasks() {
        properties.setUserId("legacy-admin");
        MovieMagnetIngestTask unbound = movie();
        unbound.setCreatedByUserId(12L);
        notifier.movieStarted(unbound);
        notifier.failed("movie", unbound.getId(), 12L, "作品", "downloading", "超时");
        MovieMagnetIngestTask ownerless = movie();
        ownerless.setCreatedByUserId(null);
        notifier.movieCompleted(ownerless);
        executor.runPending();
        verifyNoInteractions(tmdb, wecom, exceptions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SERIES", "ANIME"})
    void sharedSeriesTasksUseTvImagesAndTheirActualDetailRoute(String productType) throws Exception {
        SeriesMagnetIngestTask task = new SeriesMagnetIngestTask();
        task.setCreatedByUserId(99L);
        task.setId("series-1");
        task.setTitle("葬送的芙莉莲");
        task.setSeasonNumber(2);
        task.setTaskProductType(productType);
        task.setTmdbId(209867);
        when(tmdb.getTvImages(209867, tmdbProperties)).thenReturn(mapper.readTree("{\"posters\":[{\"file_path\":\"/poster.jpg\"}]}"));

        notifier.seriesCompleted(task);
        executor.runPending();

        verify(wecom).sendNews(eq(recipient), contains("第2季 · 任务处理完成"),
                contains("ANIME".equals(productType) ? "动漫" : "剧集"),
                eq("https://image.tmdb.org/t/p/w500/poster.jpg"), eq("https://media.example/tasks/series/series-1"));
    }

    @Test
    void independentAnimeTaskUsesTvImagesButAnimeDetailRoute() throws Exception {
        AnimeMagnetIngestTask task = new AnimeMagnetIngestTask();
        task.setCreatedByUserId(99L);
        task.setId("anime-1");
        task.setTitle("葬送的芙莉莲");
        task.setTmdbId(209867);
        task.setSeasonNumber(1);
        when(tmdb.getTvImages(209867, tmdbProperties)).thenReturn(mapper.readTree("{\"backdrops\":[{\"file_path\":\"/cover.jpg\"}]}"));

        notifier.animeCompleted(task);
        executor.runPending();

        verify(wecom).sendNews(eq(recipient), contains("第1季"), contains("动漫"),
                eq("https://image.tmdb.org/t/p/w500/cover.jpg"), eq("https://media.example/tasks/anime/anime-1"));
    }

    @Test
    void skipsMissingImagesInsteadOfUsingLogoOrText() throws Exception {
        when(tmdb.getMovieImages(157336, tmdbProperties)).thenReturn(mapper.readTree("""
                {"backdrops":[],"posters":[],"logos":[{"file_path":"/logo.png"}]}
                """));

        notifier.movieCompleted(movie());
        executor.runPending();

        verifyNoInteractions(wecom);
    }

    @Test
    void disabledNotificationsAndMissingIdentityDoNotQueryImages() {
        properties.setEnabled(false);
        notifier.movieCompleted(movie());
        properties.setEnabled(true);
        MovieMagnetIngestTask historicalTask = movie();
        historicalTask.setTmdbId(null);
        notifier.movieCompleted(historicalTask);
        executor.runPending();

        verifyNoInteractions(tmdb, wecom);
    }

    @Test
    void tokenOrSendFailureCannotEscapeIntoTheTaskResult() throws Exception {
        when(tmdb.getMovieImages(157336, tmdbProperties)).thenReturn(mapper.readTree("{\"backdrops\":[{\"file_path\":\"/cover.jpg\"}]}"));
        doThrow(new IllegalStateException("企业微信 API 业务错误码 60020"))
                .when(wecom).sendNews(eq(recipient), anyString(), anyString(), anyString(), anyString());
        MovieMagnetIngestTask task = movie();

        notifier.movieCompleted(task);

        assertThatCode(executor::runPending).doesNotThrowAnyException();
        assertThat(task.getStatus()).isEqualTo("SUCCEEDED");
    }

    @Test
    void schedulingFailureCannotEscapeIntoTheTaskResult() {
        executor.shutdown();

        assertThatCode(() -> notifier.movieCompleted(movie())).doesNotThrowAnyException();
        verifyNoInteractions(tmdb, wecom);
    }

    @Test
    void longUnicodeTitleRetainsCompletionResultAndFitsUtf8Budget() throws Exception {
        MovieMagnetIngestTask task = movie();
        task.setTitle("🎬中文".repeat(50));
        when(tmdb.getMovieImages(157336, tmdbProperties)).thenReturn(mapper.readTree("{\"posters\":[{\"file_path\":\"/cover.jpg\"}]}"));

        notifier.movieCompleted(task);
        executor.runPending();

        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        verify(wecom).sendNews(eq(recipient), title.capture(), anyString(), anyString(), anyString());
        assertThat(title.getValue()).endsWith("… · 任务处理完成");
        assertThat(title.getValue().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(128);
        assertThat(StandardCharsets.UTF_8.newEncoder().canEncode(title.getValue())).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "file:///tmp/site", "https://user:pass@media.example", "https://media.example/?token=secret"})
    void invalidFrontendConfigurationNeverProducesAnUnsafeLink(String baseUrl) throws Exception {
        properties.setFrontendBaseUrl(baseUrl);
        when(tmdb.getMovieImages(157336, tmdbProperties)).thenReturn(mapper.readTree("{\"posters\":[{\"file_path\":\"/cover.jpg\"}]}"));

        notifier.movieCompleted(movie());
        assertThatCode(executor::runPending).doesNotThrowAnyException();

        verifyNoInteractions(wecom);
    }

    @ParameterizedTest
    @ValueSource(strings = {"movie", "series", "shared-anime", "anime"})
    void initiationUsesItsOwnMessageAndActualTaskDetailRoute(String kind) throws Exception {
        when(tmdb.getMovieImages(157336, tmdbProperties)).thenReturn(mapper.readTree("{\"posters\":[{\"file_path\":\"/cover.jpg\"}]}"));
        when(tmdb.getTvImages(209867, tmdbProperties)).thenReturn(mapper.readTree("{\"posters\":[{\"file_path\":\"/cover.jpg\"}]}"));
        String route;
        switch (kind) {
            case "movie" -> {
                notifier.movieStarted(movie());
                route = "movie/movie-1";
            }
            case "anime" -> {
                AnimeMagnetIngestTask task = new AnimeMagnetIngestTask();
                task.setCreatedByUserId(99L);
                task.setId("anime-1"); task.setTitle("Show"); task.setTmdbId(209867);
                notifier.animeStarted(task);
                route = "anime/anime-1";
            }
            default -> {
                SeriesMagnetIngestTask task = new SeriesMagnetIngestTask();
                task.setCreatedByUserId(99L);
                task.setId("series-1"); task.setTitle("Show"); task.setTmdbId(209867);
                task.setTaskProductType("shared-anime".equals(kind) ? "ANIME" : "SERIES");
                notifier.seriesStarted(task);
                route = "series/series-1";
            }
        }
        verifyNoInteractions(wecom);
        executor.runPending();
        verify(wecom).sendNews(eq(recipient), contains(" · 任务已发起"), contains("已创建并排入后台队列"),
                eq("https://image.tmdb.org/t/p/w500/cover.jpg"), eq("https://media.example/tasks/" + route));
    }

    private MovieMagnetIngestTask movie() {
        MovieMagnetIngestTask task = new MovieMagnetIngestTask();
        task.setCreatedByUserId(99L);
        task.setId("movie-1");
        task.setTitle("星际穿越");
        task.setYear(2014);
        task.setTmdbId(157336);
        task.setStatus("SUCCEEDED");
        return task;
    }

    private static class ControlledExecutor extends AbstractExecutorService {
        private final List<Runnable> pending = new ArrayList<>();
        private boolean shutdown;

        void runPending() {
            List<Runnable> tasks = List.copyOf(pending);
            pending.clear();
            tasks.forEach(Runnable::run);
        }

        @Override public void execute(Runnable command) {
            if (shutdown) throw new RejectedExecutionException();
            pending.add(command);
        }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; return List.of(); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }
    }
}
