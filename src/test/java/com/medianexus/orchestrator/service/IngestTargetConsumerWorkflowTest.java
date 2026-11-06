package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.OpenListProperties;
import com.medianexus.orchestrator.controller.MagnetIngestController;
import com.medianexus.orchestrator.dto.magnet.request.AdultMagnetIngestTaskCreateRequest;
import com.medianexus.orchestrator.dto.magnet.request.AnimeMagnetIngestTaskCreateRequest;
import com.medianexus.orchestrator.dto.magnet.request.MovieMagnetIngestRequest;
import com.medianexus.orchestrator.dto.magnet.request.SeriesMagnetIngestRequest;
import com.medianexus.orchestrator.integration.openlist.OpenListClient;
import com.medianexus.orchestrator.integration.openlist.OpenListClientException;
import com.medianexus.orchestrator.integration.openlist.OpenListStorageStatus;
import com.medianexus.orchestrator.mapper.AdultMagnetIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.AdultMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.AnimeMagnetIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.AnimeMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.MovieMagnetIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.MovieMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.SeriesMagnetIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.SeriesMagnetIngestTaskMapper;
import com.medianexus.orchestrator.model.AdultMagnetIngestTask;
import com.medianexus.orchestrator.model.AnimeMagnetIngestTask;
import com.medianexus.orchestrator.model.MovieMagnetIngestTask;
import com.medianexus.orchestrator.model.SeriesMagnetIngestTask;
import com.medianexus.orchestrator.model.User;
import com.medianexus.orchestrator.service.organization.LibraryOrganizer;
import com.medianexus.orchestrator.support.IngestTargetFixtures;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IngestTargetConsumerWorkflowTest {
    private final IngestTargetFixtures targets = new IngestTargetFixtures();
    private final OpenListProperties properties = new OpenListProperties();
    private final OpenListClient openList = mock(OpenListClient.class);
    private final AuthService auth = mock(AuthService.class);
    private final UserActionQuotaService quota = mock(UserActionQuotaService.class);
    private final MediaLibraryPresenceService presence = mock(MediaLibraryPresenceService.class);
    private final LibraryOrganizer organizer = mock(LibraryOrganizer.class);
    private final QueueExecutor worker = new QueueExecutor();
    private final Map<String, MovieMagnetIngestTask> movies = new HashMap<>();
    private final Map<String, SeriesMagnetIngestTask> series = new HashMap<>();
    private final Map<String, AnimeMagnetIngestTask> anime = new HashMap<>();
    private final Map<String, AdultMagnetIngestTask> adult = new HashMap<>();
    private final MovieMagnetIngestTaskMapper movieMapper = mock(MovieMagnetIngestTaskMapper.class);
    private final SeriesMagnetIngestTaskMapper seriesMapper = mock(SeriesMagnetIngestTaskMapper.class);
    private final AnimeMagnetIngestTaskMapper animeMapper = mock(AnimeMagnetIngestTaskMapper.class);
    private final AdultMagnetIngestTaskMapper adultMapper = mock(AdultMagnetIngestTaskMapper.class);
    private MagnetIngestService magnets;
    private AnimeMagnetIngestTaskService animeService;
    private AdultMagnetIngestService adultService;

    @BeforeEach
    void setUp() {
        for (Class<?> type : List.of(MovieMagnetIngestTask.class, SeriesMagnetIngestTask.class,
                AnimeMagnetIngestTask.class, AdultMagnetIngestTask.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
        }
        User user = new User();
        user.setId(1L);
        user.setRole("ADMIN");
        when(auth.requireCurrentUser()).thenReturn(user);
        when(auth.requireAdminUser()).thenReturn(user);
        properties.setTimeout(Duration.ofSeconds(1));
        properties.setMovieRootPath("/legacy/Movies");
        properties.setTvRootPath("/legacy/TV");
        properties.setAdultRootPath("/legacy/Adult");
        properties.setAnimePathTemplate("/legacy/Anime/{title}/Season {seasonFormat}");
        when(openList.normalizePath(anyString())).thenAnswer(call -> call.getArgument(0));
        when(openList.joinPath(anyString(), anyString())).thenAnswer(call -> call.getArgument(0) + "/" + call.getArgument(1));
        when(movieMapper.insert(any(MovieMagnetIngestTask.class))).thenAnswer(call -> {
            MovieMagnetIngestTask task = call.getArgument(0); movies.put(task.getId(), task); return 1;
        });
        when(seriesMapper.insert(any(SeriesMagnetIngestTask.class))).thenAnswer(call -> {
            SeriesMagnetIngestTask task = call.getArgument(0); series.put(task.getId(), task); return 1;
        });
        when(animeMapper.insert(any(AnimeMagnetIngestTask.class))).thenAnswer(call -> {
            AnimeMagnetIngestTask task = call.getArgument(0); anime.put(task.getId(), task); return 1;
        });
        when(adultMapper.insert(any(AdultMagnetIngestTask.class))).thenAnswer(call -> {
            AdultMagnetIngestTask task = call.getArgument(0); adult.put(task.getId(), task); return 1;
        });
        when(movieMapper.selectById(anyString())).thenAnswer(call -> movies.get(call.getArgument(0)));
        when(seriesMapper.selectById(anyString())).thenAnswer(call -> series.get(call.getArgument(0)));
        when(animeMapper.selectById(anyString())).thenAnswer(call -> anime.get(call.getArgument(0)));
        when(adultMapper.selectById(anyString())).thenAnswer(call -> adult.get(call.getArgument(0)));
        magnets = new MagnetIngestService(movieMapper, mock(MovieMagnetIngestTaskLogMapper.class),
                seriesMapper, mock(SeriesMagnetIngestTaskLogMapper.class), openList, properties,
                new MovieSeriesFileRenameService(), organizer, organizer, organizer, auth, quota, null, presence,
                targets.registry, worker, mock(IngestTaskNotifier.class));
        animeService = new AnimeMagnetIngestTaskService(animeMapper, mock(AnimeMagnetIngestTaskLogMapper.class),
                openList, properties, new AnimeEpisodeRenameService(properties), organizer, auth, quota, null,
                presence, worker, targets.registry, mock(IngestTaskNotifier.class));
        adultService = new AdultMagnetIngestService(adultMapper, mock(AdultMagnetIngestTaskLogMapper.class),
                openList, properties, new MovieSeriesFileRenameService(), organizer, auth, null, new ObjectMapper(),
                worker, new QueueExecutor(), targets.registry);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MOVIE", "TV", "ANIME", "ANIME_STANDALONE", "ADULT"})
    void newBusinessEntriesUseCategoryDefault(String entry) {
        targets.useDefault("new");
        String path = switch (entry) {
            case "MOVIE" -> magnets.createMovieTask(new MovieMagnetIngestRequest(
                    "magnet:?xt=urn:btih:movie", "Movie", null, 2024, 1)).savePath();
            case "TV" -> magnets.createSeriesTask(seriesRequest("tv"), resource()).savePath();
            case "ANIME" -> magnets.createAnimeSeasonSeriesTask(seriesRequest("anime"), resource()).savePath();
            case "ANIME_STANDALONE" -> animeService.createTask(new AnimeMagnetIngestTaskCreateRequest(
                    "magnet:?xt=urn:btih:anime", null, null, "Show", "Show", null, 1, "Show", 1)).savePath();
            case "ADULT" -> adultService.createTask(new AdultMagnetIngestTaskCreateRequest(
                    "JAV", List.of("magnet:?xt=urn:btih:adult"))).targetPath();
            default -> throw new AssertionError(entry);
        };
        String expected = switch (entry) {
            case "MOVIE" -> "/new/Movies/Movie (2024)";
            case "TV" -> "/new/TV/Show/Season 1";
            case "ANIME", "ANIME_STANDALONE" -> "/new/Anime/Show/Season 01";
            case "ADULT" -> "/new/Adult/JAV/" + LocalDate.now().format(DateTimeFormatter.ofPattern("M.d"));
            default -> throw new AssertionError(entry);
        };
        assertThat(path).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MOVIE", "TV", "ANIME", "ANIME_STANDALONE", "ADULT"})
    void retryKeepsOriginalSavePathAndPreparationRootAfterDefaultSwitch(String entry) {
        targets.useDefault("new");
        targets.stopOriginal();
        TaskRetryReference retry = new TaskRetryReference("group", "ANIME_STANDALONE".equals(entry) ? "ANIME" : entry, "original");
        String path;
        String expectedPath;
        String expectedRoot;
        if ("MOVIE".equals(entry)) {
            MovieMagnetIngestTask original = new MovieMagnetIngestTask();
            original.setTitle("Movie");
            original.setYear(2024);
            original.setSavePath("/old/Movies/Movie (2024)");
            path = magnets.createMovieRetryTask(original, "magnet:?xt=urn:btih:retry", retry).savePath();
            expectedPath = "/old/Movies/Movie (2024)";
            expectedRoot = "/old/Movies";
        } else if ("ANIME_STANDALONE".equals(entry)) {
            AnimeMagnetIngestTask original = new AnimeMagnetIngestTask();
            original.setTitle("Show");
            original.setSeasonNumber(1);
            original.setSavePath("/old/Anime/Show/Season 01");
            path = animeService.createRetryTask(original, "magnet:?xt=urn:btih:retry", retry).savePath();
            expectedPath = "/old/Anime/Show/Season 01";
            expectedRoot = "/old/Anime";
        } else if ("ADULT".equals(entry)) {
            AdultMagnetIngestTask original = new AdultMagnetIngestTask();
            original.setCategory("JAV");
            original.setDateFolder("7.15");
            original.setTargetPath("/old/Adult/JAV/7.15");
            adult.put("original", original);
            path = adultService.createRetryTask("JAV", List.of("magnet:?xt=urn:btih:retry"), retry).targetPath();
            expectedPath = "/old/Adult/JAV/7.15";
            expectedRoot = "/old/Adult";
        } else {
            SeriesMagnetIngestTask original = new SeriesMagnetIngestTask();
            original.setTitle("Show");
            original.setSeasonNumber(1);
            original.setTaskProductType(entry);
            original.setSeriesName("Show");
            original.setSeasonFolder("Season 1");
            original.setSavePath("ANIME".equals(entry) ? "/old/Anime/Show/Season 01" : "/old/TV/Show/Season 1");
            path = magnets.createSeriesRetryTask(original, "magnet:?xt=urn:btih:retry", retry).savePath();
            expectedPath = "ANIME".equals(entry) ? "/old/Anime/Show/Season 01" : "/old/TV/Show/Season 1";
            expectedRoot = "ANIME".equals(entry) ? "/old/Anime" : "/old/TV";
        }
        assertThat(path).isEqualTo(expectedPath);
        doThrow(new OpenListClientException("结束目录准备探针")).when(openList).ensureDirectoryReady(anyString(), anyString());
        worker.runNext();
        verify(openList).ensureDirectoryReady(expectedPath, expectedRoot);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MOVIE", "TV", "ANIME", "ADULT"})
    void nodeStatusFollowsCategoryDefaultAndExposesOnlyTargetMetadata(String category) throws Exception {
        String folder = switch (category) {
            case "MOVIE" -> "Movies";
            case "TV" -> "TV";
            case "ANIME" -> "Anime";
            case "ADULT" -> "Adult";
            default -> throw new AssertionError(category);
        };
        when(openList.storageStatus()).thenReturn(new OpenListStorageStatus("PikPak", true, 5L, 100L, 95L));
        when(openList.storageStatusForPath("/old/" + folder))
                .thenReturn(new OpenListStorageStatus("PikPak", true, 10L, 100L, 90L));
        when(openList.storageStatusForPath("/new/" + folder))
                .thenReturn(new OpenListStorageStatus("PikPak", false, 20L, 200L, 180L));
        assertThat(magnets.getNodeStatus().usedBytes()).isEqualTo(5L);
        MagnetIngestController controller = new MagnetIngestController(magnets);
        targets.useDefault("old");
        var original = controller.getNodeStatus(category).data();
        assertThat(original.usedBytes()).isEqualTo(10L);
        targets.useDefault("new");

        var response = controller.getNodeStatus(category).data();

        assertThat(response.usedBytes()).isEqualTo(20L);
        assertThat(response.online()).isFalse();
        var json = new ObjectMapper().valueToTree(response);
        assertThat(json.path("target_id").asText()).isEqualTo("new");
        assertThat(json.path("target_name").asText()).isEqualTo("新账号");
        assertThat(json.path("category").asText()).isEqualTo(category);
        assertThat(json.toString()).doesNotContain("/old", "/new", "/legacy", "uuid");
    }

    @Test
    void retryWithUnknownOwnershipNeverFallsBackToCurrentDefault() {
        targets.useDefault("new");
        MovieMagnetIngestTask original = new MovieMagnetIngestTask();
        original.setTitle("Movie");
        original.setYear(2024);
        original.setSavePath("/unknown/Movies/Movie (2024)");

        assertThatThrownBy(() -> magnets.createMovieRetryTask(original, "magnet:?xt=urn:btih:retry",
                new TaskRetryReference("group", "MOVIE", "original")))
                .isInstanceOf(com.medianexus.orchestrator.common.exception.BusinessException.class);
        assertThat(worker.commands).isEmpty();
    }

    private SeriesMagnetIngestRequest seriesRequest(String hash) {
        return new SeriesMagnetIngestRequest("magnet:?xt=urn:btih:" + hash, "Show", null, 1, 1);
    }

    private ReleaseIngestMetadata resource() {
        return new ReleaseIngestMetadata("PROWLARR_RELEASE", "Show 1080p", "indexer", 1024L, 1,
                "release", List.of("1080p"), List.of());
    }

    private static final class QueueExecutor extends AbstractExecutorService {
        private final List<Runnable> commands = new ArrayList<>();
        @Override public void execute(Runnable command) { commands.add(command); }
        void runNext() { commands.remove(0).run(); }
        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return false; }
    }
}
