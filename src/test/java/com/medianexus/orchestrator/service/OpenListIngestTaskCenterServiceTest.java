package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.dto.magnet.response.AdultMagnetIngestTaskResponse;
import com.medianexus.orchestrator.dto.magnet.response.AnimeMagnetIngestTaskResponse;
import com.medianexus.orchestrator.dto.magnet.response.MovieMagnetIngestTaskResponse;
import com.medianexus.orchestrator.dto.magnet.response.SeriesMagnetIngestTaskResponse;
import com.medianexus.orchestrator.dto.resources.response.MovieSearchItem;
import com.medianexus.orchestrator.dto.resources.response.MovieSearchResponse;
import com.medianexus.orchestrator.dto.resources.response.SeriesSearchItem;
import com.medianexus.orchestrator.dto.resources.response.SeriesSearchResponse;
import com.medianexus.orchestrator.dto.taskcenter.request.OpenListManualMagnetRetryRequest;
import com.medianexus.orchestrator.dto.taskcenter.request.OpenListAdultBatchRetryRequest;
import com.medianexus.orchestrator.dto.taskcenter.request.OpenListReleaseRetryRequest;
import com.medianexus.orchestrator.dto.taskcenter.response.OpenListIngestTaskCenterDetailResponse;
import com.medianexus.orchestrator.dto.taskcenter.response.OpenListIngestTaskCenterListResponse;
import com.medianexus.orchestrator.dto.taskcenter.response.OpenListManualMagnetRetryResponse;
import com.medianexus.orchestrator.dto.taskcenter.response.OpenListReleaseRetryContextResponse;
import com.medianexus.orchestrator.mapper.AdultMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.AdultMagnetIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.AnimeMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.AnimeMagnetIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.MovieMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.MovieMagnetIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.SeriesMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.SeriesMagnetIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.UserMapper;
import com.medianexus.orchestrator.model.AdultMagnetIngestTask;
import com.medianexus.orchestrator.model.AdultMagnetIngestTaskLog;
import com.medianexus.orchestrator.model.AnimeMagnetIngestTask;
import com.medianexus.orchestrator.model.AnimeMagnetIngestTaskLog;
import com.medianexus.orchestrator.model.MovieMagnetIngestTask;
import com.medianexus.orchestrator.model.MovieMagnetIngestTaskLog;
import com.medianexus.orchestrator.model.SeriesMagnetIngestTask;
import com.medianexus.orchestrator.model.SeriesMagnetIngestTaskLog;
import com.medianexus.orchestrator.model.User;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class OpenListIngestTaskCenterServiceTest {

    private final TestAuthService authService = new TestAuthService();
    private final MovieMagnetIngestTaskMapper movieTaskMapper = mock(MovieMagnetIngestTaskMapper.class);
    private final SeriesMagnetIngestTaskMapper seriesTaskMapper = mock(SeriesMagnetIngestTaskMapper.class);
    private final AnimeMagnetIngestTaskMapper animeTaskMapper = mock(AnimeMagnetIngestTaskMapper.class);
    private final AdultMagnetIngestTaskMapper adultTaskMapper = mock(AdultMagnetIngestTaskMapper.class);
    private final MovieMagnetIngestTaskLogMapper movieTaskLogMapper = mock(MovieMagnetIngestTaskLogMapper.class);
    private final SeriesMagnetIngestTaskLogMapper seriesTaskLogMapper = mock(SeriesMagnetIngestTaskLogMapper.class);
    private final AnimeMagnetIngestTaskLogMapper animeTaskLogMapper = mock(AnimeMagnetIngestTaskLogMapper.class);
    private final AdultMagnetIngestTaskLogMapper adultTaskLogMapper = mock(AdultMagnetIngestTaskLogMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final MovieSeriesRetryRecorder movieSeriesRetryRecorder = new MovieSeriesRetryRecorder();
    private final AnimeRetryRecorder animeRetryRecorder = new AnimeRetryRecorder();
    private final AdultRetryRecorder adultRetryRecorder = new AdultRetryRecorder();
    private final MovieSeriesReleaseRetryRecorder releaseRetryRecorder = new MovieSeriesReleaseRetryRecorder();
    private final AnimeReleaseRetryRecorder animeReleaseRetryRecorder = new AnimeReleaseRetryRecorder();
    private final RecordingMagnetIngestService magnetIngestService =
            new RecordingMagnetIngestService(movieSeriesRetryRecorder);
    private final RecordingAnimeMagnetIngestTaskService animeMagnetIngestTaskService =
            new RecordingAnimeMagnetIngestTaskService(animeRetryRecorder);
    private final RecordingAdultMagnetIngestService adultMagnetIngestService =
            new RecordingAdultMagnetIngestService(adultRetryRecorder);
    private final RecordingProwlarrReleaseIngestService prowlarrReleaseIngestService =
            new RecordingProwlarrReleaseIngestService(releaseRetryRecorder, animeReleaseRetryRecorder);
    private final RecordingMovieSeriesResourceSearchService resourceSearchService =
            new RecordingMovieSeriesResourceSearchService();
    private final OpenListIngestTaskCenterService service = new OpenListIngestTaskCenterService(
            authService,
            movieTaskMapper,
            seriesTaskMapper,
            animeTaskMapper,
            adultTaskMapper,
            movieTaskLogMapper,
            seriesTaskLogMapper,
            animeTaskLogMapper,
            adultTaskLogMapper,
            userMapper,
            new ObjectMapper(),
            magnetIngestService,
            animeMagnetIngestTaskService,
            adultMagnetIngestService,
            prowlarrReleaseIngestService,
            resourceSearchService
    );

    @BeforeEach
    void setUp() {
        when(adultTaskMapper.selectList(any())).thenReturn(List.of());
        when(movieTaskLogMapper.selectList(any())).thenReturn(List.of());
        when(seriesTaskLogMapper.selectList(any())).thenReturn(List.of());
        when(animeTaskLogMapper.selectList(any())).thenReturn(List.of());
        when(adultTaskLogMapper.selectList(any())).thenReturn(List.of());
        movieSeriesRetryRecorder.reset();
        animeRetryRecorder.reset();
        adultRetryRecorder.reset();
        releaseRetryRecorder.reset();
        animeReleaseRetryRecorder.reset();
        resourceSearchService.reset();
    }

    @Test
    void listsMovieSeriesAndTwoAnimeSourcesByUpdatedAt() {
        authService.currentUser = user(1L, "ADMIN");
        when(movieTaskMapper.selectList(any())).thenReturn(List.of(
                movieTask("movie-1", "Movie", LocalDateTime.parse("2026-07-01T10:00:00"))
        ));
        when(seriesTaskMapper.selectList(any())).thenReturn(List.of(
                seriesTask("series-1", "Series", "SERIES", LocalDateTime.parse("2026-07-01T11:00:00")),
                seriesTask("anime-series-1", "Anime via Series", "ANIME", LocalDateTime.parse("2026-07-01T13:00:00"))
        ));
        when(animeTaskMapper.selectList(any())).thenReturn(List.of(
                animeTask("anime-1", "Anime via Magnet", LocalDateTime.parse("2026-07-01T12:00:00"))
        ));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(1L, "ADMIN", "tecn")));

        OpenListIngestTaskCenterListResponse response = service.listOpenListIngestTasks();

        assertThat(response.items())
                .extracting("id")
                .containsExactly("anime-series-1", "anime-1", "series-1", "movie-1");
        assertThat(response.items())
                .extracting("productType")
                .containsExactly("ANIME", "ANIME", "SERIES", "MOVIE");
        assertThat(response.items())
                .extracting("sourceType")
                .containsExactly("PROWLARR_RELEASE", "MANUAL_MAGNET", "PROWLARR_RELEASE", "MANUAL_MAGNET");
        assertThat(response.items())
                .extracting("createdByUsername")
                .containsExactly("tecn", "tecn", "tecn", "tecn");
        assertThat(response.total()).isEqualTo(4);
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.pageSize()).isEqualTo(10);
        assertThat(response.allCount()).isEqualTo(4);
        assertThat(response.inProgressCount()).isEqualTo(4);
        assertThat(response.needsAttentionCount()).isZero();
        assertThat(response.succeededCount()).isZero();
    }

    @Test
    void prefersChineseTaskCenterTitlesAndKeepsOriginalTitleSearchable() {
        authService.currentUser = user(1L, "USER", "owner");
        MovieMagnetIngestTask movie = movieTask(
                "movie-1",
                "Casino Royale",
                LocalDateTime.parse("2026-07-01T10:00:00")
        );
        movie.setOriginalTitle("007：大战皇家赌场");
        SeriesMagnetIngestTask series = seriesTask(
                "series-1",
                "Breaking Bad",
                "SERIES",
                LocalDateTime.parse("2026-07-01T11:00:00")
        );
        series.setTitle("绝命毒师");
        series.setOriginalTitle("Breaking Bad");
        series.setSeriesName("Breaking Bad");
        AnimeMagnetIngestTask anime = animeTask(
                "anime-1",
                "Frieren: Beyond Journey's End",
                LocalDateTime.parse("2026-07-01T12:00:00")
        );
        anime.setNameCn("葬送的芙莉莲");
        anime.setName("葬送のフリーレン");
        when(movieTaskMapper.selectList(any())).thenReturn(List.of(movie));
        when(seriesTaskMapper.selectList(any())).thenReturn(List.of(series));
        when(animeTaskMapper.selectList(any())).thenReturn(List.of(anime));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(1L, "USER", "owner")));

        OpenListIngestTaskCenterListResponse response = service.listOpenListIngestTasks();

        assertThat(response.items())
                .extracting("title")
                .containsExactly("葬送的芙莉莲 S01", "绝命毒师 Season 1", "007：大战皇家赌场 (2026)");

        OpenListIngestTaskCenterListResponse searchResponse = service.listOpenListIngestTasks(
                null,
                null,
                null,
                "Frieren",
                null,
                null
        );

        assertThat(searchResponse.items())
                .extracting("id")
                .containsExactly("anime-1");
    }

    @Test
    void listsOnlyLatestAttemptWhileSearchingAndCountingAcrossAttemptChain() {
        authService.currentUser = user(1L, "USER", "owner");
        MovieMagnetIngestTask first = movieTask(
                "movie-1",
                "Original searchable title",
                "FAILED",
                "old-searchable-hash",
                LocalDateTime.parse("2026-07-01T10:00:00")
        );
        first.setAttemptGroupId("group-1");
        MovieMagnetIngestTask second = movieTask(
                "movie-2",
                "Movie retry",
                "INTERRUPTED",
                "retry-hash",
                LocalDateTime.parse("2026-07-01T11:00:00")
        );
        second.setAttemptGroupId("group-1");
        MovieMagnetIngestTask latest = movieTask(
                "movie-3",
                "Movie current",
                "PENDING",
                "current-hash",
                LocalDateTime.parse("2026-07-01T12:00:00")
        );
        latest.setAttemptGroupId("group-1");
        when(movieTaskMapper.selectList(any())).thenReturn(List.of(first, latest, second));

        OpenListIngestTaskCenterListResponse response = service.listOpenListIngestTasks(
                "ALL",
                "ALL",
                "ALL",
                "Original searchable title",
                1,
                10
        );

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).id()).isEqualTo("movie-3");
        assertThat(response.items().get(0).attemptCount()).isEqualTo(3);
        assertThat(response.allCount()).isEqualTo(1);
        assertThat(response.inProgressCount()).isEqualTo(1);
        assertThat(response.needsAttentionCount()).isZero();
    }

    @Test
    void filtersCollapsedAttemptChainByOriginSourceInsteadOfLatestRecoverySource() {
        authService.currentUser = user(1L, "USER", "owner");
        MovieMagnetIngestTask releaseOrigin = movieTask(
                "movie-1",
                "Release origin",
                "FAILED",
                "release-hash",
                LocalDateTime.parse("2026-07-01T10:00:00")
        );
        releaseOrigin.setSourceType("PROWLARR_RELEASE");
        releaseOrigin.setAttemptGroupId("group-1");
        MovieMagnetIngestTask manualFallback = movieTask(
                "movie-2",
                "Manual fallback",
                "PENDING",
                "manual-hash",
                LocalDateTime.parse("2026-07-01T11:00:00")
        );
        manualFallback.setSourceType("MANUAL_MAGNET");
        manualFallback.setAttemptGroupId("group-1");
        manualFallback.setRetryOfTaskType("MOVIE");
        manualFallback.setRetryOfTaskId("movie-1");
        when(movieTaskMapper.selectList(any())).thenReturn(List.of(releaseOrigin, manualFallback));

        OpenListIngestTaskCenterListResponse releaseFiltered = service.listOpenListIngestTasks(
                "ALL", "ALL", "PROWLARR_RELEASE", null, 1, 10
        );
        OpenListIngestTaskCenterListResponse manualFiltered = service.listOpenListIngestTasks(
                "ALL", "ALL", "MANUAL_MAGNET", null, 1, 10
        );

        assertThat(releaseFiltered.items()).hasSize(1);
        assertThat(releaseFiltered.items().get(0).id()).isEqualTo("movie-2");
        assertThat(releaseFiltered.items().get(0).sourceType()).isEqualTo("MANUAL_MAGNET");
        assertThat(manualFiltered.items()).isEmpty();
    }

    @Test
    void treatsMissingSeriesProductTypeAsSeries() {
        authService.currentUser = user(1L, "ADMIN");
        when(movieTaskMapper.selectList(any())).thenReturn(List.of());
        when(seriesTaskMapper.selectList(any())).thenReturn(List.of(
                seriesTask("legacy-series-1", "Legacy Series", null, LocalDateTime.parse("2026-07-01T11:00:00"))
        ));
        when(animeTaskMapper.selectList(any())).thenReturn(List.of());
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(1L, "ADMIN", "tecn")));

        OpenListIngestTaskCenterListResponse response = service.listOpenListIngestTasks();

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).productType()).isEqualTo("SERIES");
    }

    @Test
    void filtersByViewProductSourceAndKeyword() {
        authService.currentUser = user(1L, "ADMIN");
        when(movieTaskMapper.selectList(any())).thenReturn(List.of(
                movieTask("movie-1", "Movie", LocalDateTime.parse("2026-07-01T10:00:00"))
        ));
        when(seriesTaskMapper.selectList(any())).thenReturn(List.of(
                seriesTask(
                        "series-1",
                        "普通剧集",
                        "SERIES",
                        "SUBMITTED",
                        "PROWLARR_RELEASE",
                        "普通发布",
                        "series-hash",
                        LocalDateTime.parse("2026-07-01T11:00:00")
                ),
                seriesTask(
                        "anime-series-1",
                        "动漫整季",
                        "ANIME",
                        "ORGANIZING",
                        "PROWLARR_RELEASE",
                        "夏日发布资源",
                        "anime-series-hash",
                        LocalDateTime.parse("2026-07-01T13:00:00")
                )
        ));
        when(animeTaskMapper.selectList(any())).thenReturn(List.of(
                animeTask(
                        "anime-1",
                        "动漫手动磁力",
                        "DOWNLOADING",
                        "manual-anime-hash",
                        LocalDateTime.parse("2026-07-01T12:00:00")
                )
        ));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(1L, "ADMIN", "tecn")));

        OpenListIngestTaskCenterListResponse response = service.listOpenListIngestTasks(
                "IN_PROGRESS",
                "ANIME",
                "PROWLARR_RELEASE",
                "夏日",
                1,
                10
        );

        assertThat(response.items())
                .extracting("id")
                .containsExactly("anime-series-1");
        assertThat(response.total()).isEqualTo(1);
        assertThat(response.allCount()).isEqualTo(1);
        assertThat(response.inProgressCount()).isEqualTo(1);
        assertThat(response.needsAttentionCount()).isZero();
        assertThat(response.succeededCount()).isZero();
    }

    @Test
    void treatsFailedInterruptedAndPartialSuccessAsNeedsAttention() {
        authService.currentUser = user(1L, "ADMIN");
        when(movieTaskMapper.selectList(any())).thenReturn(List.of(
                movieTask(
                        "movie-1",
                        "Failed Movie",
                        "FAILED",
                        "failed-movie-hash",
                        LocalDateTime.parse("2026-07-01T10:00:00")
                )
        ));
        when(seriesTaskMapper.selectList(any())).thenReturn(List.of(
                seriesTask(
                        "series-1",
                        "Interrupted Series",
                        "SERIES",
                        "INTERRUPTED",
                        "MANUAL_MAGNET",
                        null,
                        "interrupted-series-hash",
                        LocalDateTime.parse("2026-07-01T11:00:00")
                )
        ));
        when(animeTaskMapper.selectList(any())).thenReturn(List.of(
                animeTask(
                        "anime-1",
                        "Partial Anime",
                        "PARTIAL_SUCCESS",
                        "partial-anime-hash",
                        LocalDateTime.parse("2026-07-01T12:00:00")
                )
        ));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(1L, "ADMIN", "tecn")));

        OpenListIngestTaskCenterListResponse response = service.listOpenListIngestTasks(
                "NEEDS_ATTENTION",
                null,
                null,
                null,
                1,
                20
        );

        assertThat(response.items())
                .extracting("id")
                .containsExactly("anime-1", "series-1", "movie-1");
        assertThat(response.total()).isEqualTo(3);
        assertThat(response.needsAttentionCount()).isEqualTo(3);
    }

    @Test
    void paginatesAfterGlobalSortingAndClampsPage() {
        authService.currentUser = user(1L, "ADMIN");
        when(movieTaskMapper.selectList(any())).thenReturn(IntStream.rangeClosed(1, 11)
                .mapToObj(index -> movieTask(
                        "movie-" + index,
                        "Movie " + index,
                        LocalDateTime.parse("2026-07-01T10:00:00").plusMinutes(index)
                ))
                .toList());
        when(seriesTaskMapper.selectList(any())).thenReturn(List.of());
        when(animeTaskMapper.selectList(any())).thenReturn(List.of());
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(1L, "ADMIN", "tecn")));

        OpenListIngestTaskCenterListResponse response = service.listOpenListIngestTasks(
                null,
                "MOVIE",
                null,
                null,
                2,
                10
        );

        assertThat(response.page()).isEqualTo(2);
        assertThat(response.pageSize()).isEqualTo(10);
        assertThat(response.total()).isEqualTo(11);
        assertThat(response.items())
                .extracting("id")
                .containsExactly("movie-1");
    }

    @Test
    void acceptsAdultProductFilterBeforeAdultTasksAreListed() {
        authService.currentUser = user(1L, "ADMIN");
        when(movieTaskMapper.selectList(any())).thenReturn(List.of(
                movieTask("movie-1", "Movie", LocalDateTime.parse("2026-07-01T10:00:00"))
        ));
        when(seriesTaskMapper.selectList(any())).thenReturn(List.of());
        when(animeTaskMapper.selectList(any())).thenReturn(List.of());
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(1L, "ADMIN", "tecn")));

        OpenListIngestTaskCenterListResponse response = service.listOpenListIngestTasks(
                null,
                "ADULT",
                null,
                null,
                1,
                20
        );

        assertThat(response.items()).isEmpty();
        assertThat(response.total()).isZero();
        assertThat(response.allCount()).isZero();
    }

    @Test
    void listsAdultTasksForAdminWithCreatorAndBatchProgressSummary() {
        authService.currentUser = user(9L, "ADMIN");
        when(movieTaskMapper.selectList(any())).thenReturn(List.of());
        when(seriesTaskMapper.selectList(any())).thenReturn(List.of());
        when(animeTaskMapper.selectList(any())).thenReturn(List.of());
        when(adultTaskMapper.selectList(any())).thenReturn(List.of(
                adultTask("adult-1", 2L, LocalDateTime.parse("2026-07-01T14:00:00"))
        ));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(2L, "ADMIN", "adult-admin")));

        OpenListIngestTaskCenterListResponse response = service.listOpenListIngestTasks(
                null,
                "ADULT",
                null,
                null,
                1,
                10
        );

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).taskType()).isEqualTo("ADULT");
        assertThat(response.items().get(0).productType()).isEqualTo("ADULT");
        assertThat(response.items().get(0).sourceType()).isEqualTo("MANUAL_MAGNET");
        assertThat(response.items().get(0).createdByUsername()).isEqualTo("adult-admin");
        assertThat(response.items().get(0).title()).isEqualTo("western 批量任务 2026-07-01");
        assertThat(response.items().get(0).releaseTitle()).isNull();
        assertThat(response.items().get(0).detailPath()).isEqualTo("/tasks/adult/adult-1");
        assertThat(response.items().get(0).progressSummary())
                .isEqualTo("已提交 7，成功 4，失败 1，重复 2，保留 3，删除 1");
        assertThat(response.total()).isEqualTo(1);
        assertThat(response.allCount()).isEqualTo(1);
    }

    @Test
    void hidesAdultTasksFromNonAdminWithoutReadingAdultMapper() {
        authService.currentUser = user(1L, "USER");
        when(movieTaskMapper.selectList(any())).thenReturn(List.of());
        when(seriesTaskMapper.selectList(any())).thenReturn(List.of());
        when(animeTaskMapper.selectList(any())).thenReturn(List.of());

        OpenListIngestTaskCenterListResponse response = service.listOpenListIngestTasks(
                null,
                "ADULT",
                null,
                "western",
                1,
                10
        );

        assertThat(response.items()).isEmpty();
        assertThat(response.total()).isZero();
        assertThat(response.allCount()).isZero();
        verify(adultTaskMapper, never()).selectList(any());
    }

    @Test
    void sortsAndPaginatesAdultTogetherWithOtherTasks() {
        authService.currentUser = user(9L, "ADMIN");
        when(movieTaskMapper.selectList(any())).thenReturn(List.of(
                movieTask("movie-1", "Movie", LocalDateTime.parse("2026-07-01T10:00:00"))
        ));
        when(seriesTaskMapper.selectList(any())).thenReturn(List.of(
                seriesTask("series-1", "Series", "SERIES", LocalDateTime.parse("2026-07-01T12:00:00"))
        ));
        when(animeTaskMapper.selectList(any())).thenReturn(List.of());
        when(adultTaskMapper.selectList(any())).thenReturn(List.of(
                adultTask("adult-1", 9L, LocalDateTime.parse("2026-07-01T11:00:00"))
        ));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(9L, "ADMIN", "admin")));

        OpenListIngestTaskCenterListResponse response = service.listOpenListIngestTasks(
                null,
                null,
                null,
                null,
                1,
                2
        );

        assertThat(response.items())
                .extracting("id")
                .containsExactly("series-1", "adult-1");
        assertThat(response.total()).isEqualTo(3);
        assertThat(response.pageSize()).isEqualTo(2);
    }

    @Test
    void treatsNullAdultProgressCountsAsZero() {
        authService.currentUser = user(9L, "ADMIN");
        when(movieTaskMapper.selectList(any())).thenReturn(List.of());
        when(seriesTaskMapper.selectList(any())).thenReturn(List.of());
        when(animeTaskMapper.selectList(any())).thenReturn(List.of());
        AdultMagnetIngestTask task = adultTask("adult-1", 9L, LocalDateTime.parse("2026-07-01T14:00:00"));
        task.setDownloadLinksJson("[\"magnet:?xt=urn:btih:firsthash\",\"ed2k://|file|video.mkv|123|0123456789abcdef0123456789abcdef|/\"]");
        task.setSubmittedCount(null);
        task.setSucceededCount(null);
        task.setFailedCount(null);
        task.setDuplicateCount(null);
        task.setKeptCount(null);
        task.setDeletedCount(null);
        when(adultTaskMapper.selectList(any())).thenReturn(List.of(task));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(9L, "ADMIN", "admin")));

        OpenListIngestTaskCenterListResponse response = service.listOpenListIngestTasks();

        assertThat(response.items().get(0).progressSummary())
                .isEqualTo("已提交 0，成功 0，失败 0，重复 0，保留 0，删除 0");
    }

    @Test
    void onlyListsAdultTasksWhenSourceFilterIsAll() {
        authService.currentUser = user(9L, "ADMIN");
        when(movieTaskMapper.selectList(any())).thenReturn(List.of());
        when(seriesTaskMapper.selectList(any())).thenReturn(List.of());
        when(animeTaskMapper.selectList(any())).thenReturn(List.of());
        when(adultTaskMapper.selectList(any())).thenReturn(List.of(
                adultTask("adult-1", 9L, LocalDateTime.parse("2026-07-01T14:00:00"))
        ));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(9L, "ADMIN", "admin")));

        OpenListIngestTaskCenterListResponse manualSourceResponse = service.listOpenListIngestTasks(
                null,
                "ADULT",
                "MANUAL_MAGNET",
                null,
                1,
                10
        );
        OpenListIngestTaskCenterListResponse allSourceResponse = service.listOpenListIngestTasks(
                null,
                "ADULT",
                "ALL",
                null,
                1,
                10
        );

        assertThat(manualSourceResponse.items()).isEmpty();
        assertThat(manualSourceResponse.total()).isZero();
        assertThat(allSourceResponse.items())
                .extracting("id")
                .containsExactly("adult-1");
    }

    @Test
    void returnsMovieDetailWithReleaseMetadataLogsAndPendingExplanation() {
        authService.currentUser = user(1L, "USER", "owner");
        MovieMagnetIngestTask task = movieTask(
                "movie-1",
                "Movie",
                "PENDING",
                "movie-hash",
                LocalDateTime.parse("2026-07-01T10:00:00")
        );
        task.setSourceType("PROWLARR_RELEASE");
        task.setReleaseTitle("Movie.Release.2160p");
        task.setReleaseIndexer("Indexer");
        task.setReleaseSize(12_345L);
        task.setResolutionTags("2160p,1080p");
        task.setQualityTag("2160p");
        task.setDynamicRangeTags("hdr10,dolby_vision");
        when(movieTaskMapper.selectById("movie-1")).thenReturn(task);
        when(userMapper.selectById(1L)).thenReturn(user(1L, "USER", "owner"));
        when(movieTaskLogMapper.selectList(any())).thenReturn(List.of(
                movieLog(1L, "movie-1", "INFO", "created", "已创建"),
                movieLog(2L, "movie-1", "WARN", "downloading", "下载较慢"),
                movieLog(3L, "movie-1", "ERROR", "failed", "下载失败")
        ));

        OpenListIngestTaskCenterDetailResponse response = service.getOpenListIngestTaskDetail("movie", "movie-1");

        assertThat(response.taskType()).isEqualTo("MOVIE");
        assertThat(response.productType()).isEqualTo("MOVIE");
        assertThat(response.createdByUsername()).isEqualTo("owner");
        assertThat(response.sourceType()).isEqualTo("PROWLARR_RELEASE");
        assertThat(response.releaseTitle()).isEqualTo("Movie.Release.2160p");
        assertThat(response.releaseIndexer()).isEqualTo("Indexer");
        assertThat(response.releaseSize()).isEqualTo(12_345L);
        assertThat(response.resolutionTags()).containsExactly("2160p", "1080p");
        assertThat(response.dynamicRangeTags()).containsExactly("hdr10", "dolby_vision");
        assertThat(response.progress().organizedCount()).isZero();
        assertThat(response.progress().skippedCount()).isZero();
        assertThat(response.active()).isTrue();
        assertThat(response.pendingExplanation()).isEqualTo("任务已创建，正在等待对应任务执行器处理。");
        assertThat(response.logs()).extracting("id").containsExactly(1L, 2L, 3L);
        assertThat(response.lastWarningOrErrorLog().id()).isEqualTo(3L);
    }

    @Test
    void returnsSharedSeriesAnimeDetailAsAnimeProductType() {
        authService.currentUser = user(1L, "ADMIN", "admin");
        SeriesMagnetIngestTask task = seriesTask(
                "series-anime-1",
                "Anime via Series",
                "ANIME",
                LocalDateTime.parse("2026-07-01T11:00:00")
        );
        task.setErrorMessage("整理失败");
        when(seriesTaskMapper.selectById("series-anime-1")).thenReturn(task);
        when(userMapper.selectById(1L)).thenReturn(user(1L, "ADMIN", "admin"));
        when(seriesTaskLogMapper.selectList(any())).thenReturn(List.of(
                seriesLog(1L, "series-anime-1", "INFO", "submitted", "已提交"),
                seriesLog(2L, "series-anime-1", "WARN", "organizing", "跳过一个文件")
        ));

        OpenListIngestTaskCenterDetailResponse response = service.getOpenListIngestTaskDetail("SERIES", "series-anime-1");

        assertThat(response.taskType()).isEqualTo("SERIES");
        assertThat(response.productType()).isEqualTo("ANIME");
        assertThat(response.errorMessage()).isEqualTo("整理失败");
        assertThat(response.lastWarningOrErrorLog().message()).isEqualTo("跳过一个文件");
    }

    @Test
    void returnsStandaloneAnimeDetailWithOrganizedProgress() {
        authService.currentUser = user(1L, "USER");
        AnimeMagnetIngestTask task = animeTask(
                "anime-1",
                "Frieren: Beyond Journey's End",
                "PARTIAL_SUCCESS",
                "anime-hash",
                LocalDateTime.parse("2026-07-01T12:00:00")
        );
        task.setNameCn("葬送的芙莉莲");
        task.setName("葬送のフリーレン");
        task.setOrganizedCount(3);
        task.setSkippedCount(2);
        when(animeTaskMapper.selectById("anime-1")).thenReturn(task);
        when(userMapper.selectById(1L)).thenReturn(user(1L, "USER", "owner"));
        when(animeTaskLogMapper.selectList(any())).thenReturn(List.of(
                animeLog(1L, "anime-1", "ERROR", "failed", "整理失败")
        ));

        OpenListIngestTaskCenterDetailResponse response = service.getOpenListIngestTaskDetail("anime", "anime-1");

        assertThat(response.productType()).isEqualTo("ANIME");
        assertThat(response.title()).isEqualTo("葬送的芙莉莲 S01");
        assertThat(response.attemptChain().currentAttempt().title()).isEqualTo("葬送的芙莉莲 S01");
        assertThat(response.sourceType()).isEqualTo("MANUAL_MAGNET");
        assertThat(response.progressSummary()).isEqualTo("已整理 3，跳过 2");
        assertThat(response.progress().organizedCount()).isEqualTo(3);
        assertThat(response.progress().skippedCount()).isEqualTo(2);
        assertThat(response.active()).isFalse();
    }

    @Test
    void returnsAdultDetailForAdminWithBatchProgress() {
        authService.currentUser = user(9L, "ADMIN", "admin");
        AdultMagnetIngestTask task = adultTask("adult-1", 9L, LocalDateTime.parse("2026-07-01T14:00:00"));
        task.setDownloadLinksJson("[\"magnet:?xt=urn:btih:firsthash\",\"ed2k://|file|video.mkv|123|0123456789abcdef0123456789abcdef|/\"]");
        when(adultTaskMapper.selectById("adult-1")).thenReturn(task);
        when(userMapper.selectById(9L)).thenReturn(user(9L, "ADMIN", "admin"));
        when(adultTaskLogMapper.selectList(any())).thenReturn(List.of(
                adultLog(1L, "adult-1", "WARN", "finished", "部分链接失败")
        ));

        OpenListIngestTaskCenterDetailResponse response = service.getOpenListIngestTaskDetail("adult", "adult-1");

        assertThat(response.productType()).isEqualTo("ADULT");
        assertThat(response.releaseTitle()).isNull();
        assertThat(response.progress().submittedCount()).isEqualTo(7);
        assertThat(response.progress().succeededCount()).isEqualTo(4);
        assertThat(response.progress().failedCount()).isEqualTo(1);
        assertThat(response.progress().duplicateCount()).isEqualTo(2);
        assertThat(response.progress().keptCount()).isEqualTo(3);
        assertThat(response.progress().deletedCount()).isEqualTo(1);
        assertThat(response.lastWarningOrErrorLog().message()).isEqualTo("部分链接失败");
        assertThat(response.batchDownloadLinks()).containsExactly(
                "magnet:?xt=urn:btih:firsthash",
                "ed2k://|file|video.mkv|123|0123456789abcdef0123456789abcdef|/"
        );
    }

    @Test
    void returnsOriginalAttemptChainForNewTaskIdentity() {
        authService.currentUser = user(1L, "USER", "owner");
        MovieMagnetIngestTask task = movieTask(
                "movie-1",
                "Movie",
                "FAILED",
                "movie-hash",
                LocalDateTime.parse("2026-07-01T10:00:00")
        );
        task.setAttemptGroupId("movie-1");
        when(movieTaskMapper.selectById("movie-1")).thenReturn(task);
        when(movieTaskMapper.selectList(any())).thenReturn(List.of(task));
        when(userMapper.selectById(1L)).thenReturn(user(1L, "USER", "owner"));

        OpenListIngestTaskCenterDetailResponse response = service.getOpenListIngestTaskDetail("movie", "movie-1");

        assertThat(response.attemptChain().attemptGroupId()).isEqualTo("movie-1");
        assertThat(response.attemptChain().attempts()).hasSize(1);
        assertThat(response.attemptChain().currentAttempt().id()).isEqualTo("movie-1");
        assertThat(response.attemptChain().currentAttempt().current()).isTrue();
        assertThat(response.attemptChain().currentAttempt().retryOfTaskType()).isNull();
        assertThat(response.attemptChain().retryOf()).isNull();
    }

    @Test
    void givesLegacyTaskStableAttemptChainIdentityWithoutPersistedGroup() {
        authService.currentUser = user(1L, "USER", "owner");
        MovieMagnetIngestTask task = movieTask(
                "legacy-movie-1",
                "Legacy Movie",
                "FAILED",
                "legacy-movie-hash",
                LocalDateTime.parse("2026-07-01T10:00:00")
        );
        when(movieTaskMapper.selectById("legacy-movie-1")).thenReturn(task);
        when(userMapper.selectById(1L)).thenReturn(user(1L, "USER", "owner"));

        OpenListIngestTaskCenterDetailResponse response =
                service.getOpenListIngestTaskDetail("movie", "legacy-movie-1");

        assertThat(response.attemptChain().attemptGroupId()).isEqualTo("LEGACY:MOVIE:legacy-movie-1");
        assertThat(response.attemptChain().attempts())
                .extracting("id")
                .containsExactly("legacy-movie-1");
        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.logs()).isEmpty();
    }

    @Test
    void returnsContinuousAttemptsInTimeOrderWithVisibleRetrySources() {
        authService.currentUser = user(1L, "USER", "owner");
        MovieMagnetIngestTask first = movieTask(
                "movie-1",
                "Movie",
                "FAILED",
                "movie-hash-1",
                LocalDateTime.parse("2026-07-01T10:00:00")
        );
        first.setAttemptGroupId("group-1");
        MovieMagnetIngestTask second = movieTask(
                "movie-2",
                "Movie",
                "INTERRUPTED",
                "movie-hash-2",
                LocalDateTime.parse("2026-07-01T11:00:00")
        );
        second.setAttemptGroupId("group-1");
        second.setRetryOfTaskType("MOVIE");
        second.setRetryOfTaskId("movie-1");
        MovieMagnetIngestTask third = movieTask(
                "movie-3",
                "Movie",
                "PENDING",
                "movie-hash-3",
                LocalDateTime.parse("2026-07-01T12:00:00")
        );
        third.setAttemptGroupId("group-1");
        third.setRetryOfTaskType("MOVIE");
        third.setRetryOfTaskId("movie-2");
        when(movieTaskMapper.selectById("movie-3")).thenReturn(third);
        when(movieTaskMapper.selectList(any())).thenReturn(List.of(third, first, second));
        when(userMapper.selectById(1L)).thenReturn(user(1L, "USER", "owner"));

        OpenListIngestTaskCenterDetailResponse response = service.getOpenListIngestTaskDetail("movie", "movie-3");

        assertThat(response.attemptChain().attempts())
                .extracting("id")
                .containsExactly("movie-1", "movie-2", "movie-3");
        assertThat(response.attemptChain().currentAttempt().id()).isEqualTo("movie-3");
        assertThat(response.attemptChain().retryOf().id()).isEqualTo("movie-2");
        assertThat(response.attemptChain().attempts().get(1).retryOfTaskId()).isEqualTo("movie-1");
        assertThat(response.attemptChain().attempts().get(2).retryOfTaskId()).isEqualTo("movie-2");
    }

    @Test
    void returnsRecursiveRetrySourceWhenOriginalAttemptHasNoPersistedGroup() {
        authService.currentUser = user(1L, "USER", "owner");
        AnimeMagnetIngestTask first = animeTask(
                "anime-1",
                "失忆投捕",
                "INTERRUPTED",
                "anime-hash-1",
                LocalDateTime.parse("2026-06-30T17:02:00")
        );
        AnimeMagnetIngestTask second = animeTask(
                "anime-2",
                "失忆投捕",
                "INTERRUPTED",
                "anime-hash-2",
                LocalDateTime.parse("2026-07-02T13:42:00")
        );
        second.setAttemptGroupId("LEGACY:ANIME:anime-1");
        second.setRetryOfTaskType("ANIME");
        second.setRetryOfTaskId("anime-1");
        AnimeMagnetIngestTask third = animeTask(
                "anime-3",
                "失忆投捕",
                "PENDING",
                "anime-hash-3",
                LocalDateTime.parse("2026-07-02T13:46:00")
        );
        third.setAttemptGroupId("LEGACY:ANIME:anime-1");
        third.setRetryOfTaskType("ANIME");
        third.setRetryOfTaskId("anime-2");
        when(animeTaskMapper.selectById("anime-3")).thenReturn(third);
        when(animeTaskMapper.selectById("anime-2")).thenReturn(second);
        when(animeTaskMapper.selectById("anime-1")).thenReturn(first);
        when(animeTaskMapper.selectList(any())).thenReturn(List.of(second, third));
        when(userMapper.selectById(1L)).thenReturn(user(1L, "USER", "owner"));

        OpenListIngestTaskCenterDetailResponse response = service.getOpenListIngestTaskDetail("anime", "anime-3");

        assertThat(response.attemptChain().attemptGroupId()).isEqualTo("LEGACY:ANIME:anime-1");
        assertThat(response.attemptChain().attempts())
                .extracting("id")
                .containsExactly("anime-1", "anime-2", "anime-3");
        assertThat(response.attemptChain().retryOf().id()).isEqualTo("anime-2");
        assertThat(response.attemptChain().attempts().get(1).retryOfTaskId()).isEqualTo("anime-1");
        assertThat(response.attemptChain().attempts().get(2).retryOfTaskId()).isEqualTo("anime-2");
    }

    @Test
    void returnsCrossTypeAttemptChainAndKeepsSharedSeriesAnimeProductType() {
        authService.currentUser = user(1L, "ADMIN", "admin");
        MovieMagnetIngestTask movieAttempt = movieTask(
                "movie-1",
                "Movie",
                "FAILED",
                "movie-hash",
                LocalDateTime.parse("2026-07-01T10:00:00")
        );
        movieAttempt.setAttemptGroupId("group-cross");
        SeriesMagnetIngestTask animeAttempt = seriesTask(
                "series-anime-1",
                "Anime via Series",
                "ANIME",
                LocalDateTime.parse("2026-07-01T11:00:00")
        );
        animeAttempt.setAttemptGroupId("group-cross");
        animeAttempt.setRetryOfTaskType("MOVIE");
        animeAttempt.setRetryOfTaskId("movie-1");
        when(seriesTaskMapper.selectById("series-anime-1")).thenReturn(animeAttempt);
        when(movieTaskMapper.selectList(any())).thenReturn(List.of(movieAttempt));
        when(seriesTaskMapper.selectList(any())).thenReturn(List.of(animeAttempt));
        when(userMapper.selectById(1L)).thenReturn(user(1L, "ADMIN", "admin"));

        OpenListIngestTaskCenterDetailResponse response =
                service.getOpenListIngestTaskDetail("series", "series-anime-1");

        assertThat(response.productType()).isEqualTo("ANIME");
        assertThat(response.attemptChain().attempts())
                .extracting("id")
                .containsExactly("movie-1", "series-anime-1");
        assertThat(response.attemptChain().currentAttempt().productType()).isEqualTo("ANIME");
        assertThat(response.attemptChain().retryOf().taskType()).isEqualTo("MOVIE");
        assertThat(response.attemptChain().retryOf().id()).isEqualTo("movie-1");
    }

    @Test
    void filtersAttemptChainByPermissionWithoutLeakingAdultSource() {
        authService.currentUser = user(1L, "USER", "owner");
        MovieMagnetIngestTask movieAttempt = movieTask(
                "movie-1",
                "Movie",
                "FAILED",
                "movie-hash",
                LocalDateTime.parse("2026-07-01T10:00:00")
        );
        movieAttempt.setAttemptGroupId("group-sensitive");
        movieAttempt.setRetryOfTaskType("ADULT");
        movieAttempt.setRetryOfTaskId("adult-1");
        when(movieTaskMapper.selectById("movie-1")).thenReturn(movieAttempt);
        when(movieTaskMapper.selectList(any())).thenReturn(List.of(movieAttempt));
        when(userMapper.selectById(1L)).thenReturn(user(1L, "USER", "owner"));

        OpenListIngestTaskCenterDetailResponse response = service.getOpenListIngestTaskDetail("movie", "movie-1");

        assertThat(response.attemptChain().attempts()).hasSize(1);
        assertThat(response.attemptChain().currentAttempt().retryOfTaskType()).isNull();
        assertThat(response.attemptChain().currentAttempt().retryOfTaskId()).isNull();
        assertThat(response.attemptChain().retryOf()).isNull();
        verify(adultTaskMapper, never()).selectList(any());
    }

    @Test
    void reusesOriginalMovieManualMagnetAsNewAttempt() {
        authService.currentUser = user(1L, "USER", "owner");
        MovieMagnetIngestTask original = movieTask(
                "movie-1",
                "Movie",
                "FAILED",
                "old-hash",
                LocalDateTime.parse("2026-07-01T10:00:00")
        );
        original.setMagnet("magnet:?xt=urn:btih:oldhash");
        original.setAttemptGroupId("group-1");
        when(movieTaskMapper.selectById("movie-1")).thenReturn(original);
        movieSeriesRetryRecorder.nextMovieResponse = movieRetryResponse("movie-2");

        OpenListManualMagnetRetryResponse response =
                service.reuseOriginalManualMagnet("movie", "movie-1");

        assertThat(response.taskType()).isEqualTo("MOVIE");
        assertThat(response.id()).isEqualTo("movie-2");
        assertThat(response.detailPath()).isEqualTo("/tasks/movie/movie-2");
        assertThat(movieSeriesRetryRecorder.movieCallCount).isEqualTo(1);
        assertThat(movieSeriesRetryRecorder.lastMovieMagnet).isEqualTo("magnet:?xt=urn:btih:oldhash");
        assertThat(movieSeriesRetryRecorder.lastMovieRetryReference.attemptGroupId()).isEqualTo("group-1");
        assertThat(movieSeriesRetryRecorder.lastMovieRetryReference.taskType()).isEqualTo("MOVIE");
        assertThat(movieSeriesRetryRecorder.lastMovieRetryReference.taskId()).isEqualTo("movie-1");
    }

    @Test
    void replacesSeriesManualMagnetAndKeepsSharedAnimeProductContext() {
        authService.currentUser = user(1L, "USER", "owner");
        SeriesMagnetIngestTask original = seriesTask(
                "series-anime-1",
                "Anime via Series",
                "ANIME",
                "PARTIAL_SUCCESS",
                "MANUAL_MAGNET",
                null,
                "old-hash",
                LocalDateTime.parse("2026-07-01T10:00:00")
        );
        original.setMagnet("magnet:?xt=urn:btih:oldhash");
        when(seriesTaskMapper.selectById("series-anime-1")).thenReturn(original);
        movieSeriesRetryRecorder.nextSeriesResponse = seriesRetryResponse("series-anime-2", "ANIME");

        OpenListManualMagnetRetryResponse response = service.replaceManualMagnet(
                "series",
                "series-anime-1",
                new OpenListManualMagnetRetryRequest("magnet:?xt=urn:btih:newhash")
        );

        assertThat(response.detailPath()).isEqualTo("/tasks/series/series-anime-2");
        assertThat(movieSeriesRetryRecorder.seriesCallCount).isEqualTo(1);
        assertThat(movieSeriesRetryRecorder.lastSeriesTask.getTaskProductType()).isEqualTo("ANIME");
        assertThat(movieSeriesRetryRecorder.lastSeriesTask.getSeasonNumber()).isEqualTo(1);
        assertThat(movieSeriesRetryRecorder.lastSeriesMagnet).isEqualTo("magnet:?xt=urn:btih:newhash");
        assertThat(movieSeriesRetryRecorder.lastSeriesRetryReference.attemptGroupId()).isEqualTo("LEGACY:SERIES:series-anime-1");
    }

    @Test
    void replacesIndependentAnimeManualMagnetAsNewAttempt() {
        authService.currentUser = user(1L, "USER", "owner");
        AnimeMagnetIngestTask original = animeTask(
                "anime-1",
                "Anime",
                "INTERRUPTED",
                "old-hash",
                LocalDateTime.parse("2026-07-01T10:00:00")
        );
        original.setMagnet("magnet:?xt=urn:btih:oldhash");
        original.setBgmId("1234");
        original.setSavePath("/anime/Anime/Season 01");
        when(animeTaskMapper.selectById("anime-1")).thenReturn(original);
        animeRetryRecorder.nextResponse = animeRetryResponse("anime-2");

        OpenListManualMagnetRetryResponse response = service.replaceManualMagnet(
                "anime",
                "anime-1",
                new OpenListManualMagnetRetryRequest("magnet:?xt=urn:btih:newhash")
        );

        assertThat(response.taskType()).isEqualTo("ANIME");
        assertThat(response.detailPath()).isEqualTo("/tasks/anime/anime-2");
        assertThat(animeRetryRecorder.callCount).isEqualTo(1);
        assertThat(animeRetryRecorder.lastMagnet).isEqualTo("magnet:?xt=urn:btih:newhash");
    }

    @Test
    void reusesCurrentMagnetForProwlarrReleaseTask() {
        authService.currentUser = user(1L, "USER", "owner");
        SeriesMagnetIngestTask original = seriesTask(
                "series-1",
                "Series",
                "SERIES",
                "FAILED",
                "PROWLARR_RELEASE",
                "Release",
                "old-hash",
                LocalDateTime.parse("2026-07-01T10:00:00")
        );
        when(seriesTaskMapper.selectById("series-1")).thenReturn(original);
        movieSeriesRetryRecorder.nextSeriesResponse = seriesRetryResponse("series-2", "SERIES");

        OpenListManualMagnetRetryResponse response = service.reuseOriginalManualMagnet("series", "series-1");

        assertThat(response.detailPath()).isEqualTo("/tasks/series/series-2");
        assertThat(movieSeriesRetryRecorder.lastSeriesMagnet).isEqualTo(original.getMagnet());
        assertThat(movieSeriesRetryRecorder.lastSeriesRetryReference.taskId()).isEqualTo("series-1");
    }

    @Test
    void returnsReleaseRetryContextForManualSharedAnimeTaskWithoutGuessingProductType() {
        authService.currentUser = user(1L, "USER", "owner");
        SeriesMagnetIngestTask original = seriesTask(
                "series-anime-1",
                "Anime via Series",
                "ANIME",
                "FAILED",
                "MANUAL_MAGNET",
                null,
                "old-hash",
                LocalDateTime.parse("2026-07-01T10:00:00")
        );
        original.setOriginalTitle("Original Anime");
        original.setSeasonNumber(2);
        original.setQualityTag("1080p");
        when(seriesTaskMapper.selectById("series-anime-1")).thenReturn(original);

        OpenListReleaseRetryContextResponse response = service.getReleaseRetryContext(
                "series",
                "series-anime-1"
        );

        assertThat(response.productType()).isEqualTo("ANIME");
        assertThat(response.seasonNumber()).isEqualTo(2);
        assertThat(response.qualityTag()).isEqualTo("1080p");
        assertThat(response.releaseTitle()).isNull();
    }

    @Test
    void enrichesLegacyMovieReleaseRetryContextWithCatalogDisplayTitle() {
        authService.currentUser = user(1L, "USER", "owner");
        MovieMagnetIngestTask original = movieTask(
                "movie-1",
                "Casino Royale",
                "FAILED",
                "old-hash",
                LocalDateTime.parse("2026-07-01T10:00:00")
        );
        original.setOriginalTitle("Casino Royale");
        original.setYear(2006);
        original.setSourceType("PROWLARR_RELEASE");
        when(movieTaskMapper.selectById("movie-1")).thenReturn(original);
        resourceSearchService.movieResponse = new MovieSearchResponse(List.of(new MovieSearchItem(
                "tmdb:36557",
                "007：大战皇家赌场",
                "Casino Royale",
                2006,
                "",
                null,
                36557,
                "tt0381061",
                List.of("Casino Royale"),
                "released"
        )));

        OpenListReleaseRetryContextResponse response = service.getReleaseRetryContext("movie", "movie-1");

        assertThat(resourceSearchService.lastMovieTerm).isEqualTo("Casino Royale");
        assertThat(response.title()).isEqualTo("007：大战皇家赌场");
        assertThat(response.originalTitle()).isEqualTo("Casino Royale");
    }

    @Test
    void enrichesLegacySeriesReleaseRetryContextWithCatalogDisplayTitle() {
        authService.currentUser = user(1L, "USER", "owner");
        SeriesMagnetIngestTask original = seriesTask(
                "series-1",
                "Breaking Bad",
                "SERIES",
                "FAILED",
                "PROWLARR_RELEASE",
                null,
                "old-hash",
                LocalDateTime.parse("2026-07-01T10:00:00")
        );
        original.setOriginalTitle("Breaking Bad");
        when(seriesTaskMapper.selectById("series-1")).thenReturn(original);
        resourceSearchService.seriesResponse = new SeriesSearchResponse(List.of(new SeriesSearchItem(
                "tmdb:1396",
                "绝命毒师",
                "Breaking Bad",
                2008,
                "",
                null,
                81189,
                "tt0903747",
                1396,
                "ended",
                "AMC",
                "standard"
        )));

        OpenListReleaseRetryContextResponse response = service.getReleaseRetryContext("series", "series-1");

        assertThat(resourceSearchService.lastSeriesTerm).isEqualTo("Breaking Bad");
        assertThat(response.title()).isEqualTo("绝命毒师");
        assertThat(response.originalTitle()).isEqualTo("Breaking Bad");
    }

    @Test
    void retriesManualSharedAnimeTaskWithSelectedReleaseInSameAttemptChain() {
        authService.currentUser = user(1L, "USER", "owner");
        SeriesMagnetIngestTask original = seriesTask(
                "series-anime-1",
                "Anime via Series",
                "ANIME",
                "PARTIAL_SUCCESS",
                "MANUAL_MAGNET",
                null,
                "old-hash",
                LocalDateTime.parse("2026-07-01T10:00:00")
        );
        original.setAttemptGroupId("anime-group-1");
        when(seriesTaskMapper.selectById("series-anime-1")).thenReturn(original);
        releaseRetryRecorder.nextSeriesResponse = seriesRetryResponse("series-anime-2", "ANIME");
        OpenListReleaseRetryRequest request = releaseRetryRequest();

        OpenListManualMagnetRetryResponse response = service.retryWithSelectedRelease(
                "series",
                "series-anime-1",
                request
        );

        assertThat(response.detailPath()).isEqualTo("/tasks/series/series-anime-2");
        assertThat(releaseRetryRecorder.lastSeriesTask.getTaskProductType()).isEqualTo("ANIME");
        assertThat(releaseRetryRecorder.lastRelease).isSameAs(request);
        assertThat(releaseRetryRecorder.lastRetryReference.attemptGroupId()).isEqualTo("anime-group-1");
        assertThat(releaseRetryRecorder.lastRetryReference.taskId()).isEqualTo("series-anime-1");
    }

    @Test
    void returnsReleaseRetryContextForIndependentAnimeTask() {
        authService.currentUser = user(1L, "USER", "owner");
        AnimeMagnetIngestTask original = animeTask(
                "anime-1",
                "Anime Title",
                "FAILED",
                "old-hash",
                LocalDateTime.parse("2026-07-01T10:00:00")
        );
        original.setSourceType("PROWLARR_RELEASE");
        original.setReleaseTitle("Anime.Release.1080p");
        original.setReleaseIndexer("Indexer A");
        original.setReleaseSize(8_000_000_000L);
        original.setResolutionTags("1080p");
        original.setQualityTag("1080p");
        original.setDynamicRangeTags("sdr");
        when(animeTaskMapper.selectById("anime-1")).thenReturn(original);

        OpenListReleaseRetryContextResponse response = service.getReleaseRetryContext("anime", "anime-1");

        assertThat(response.taskType()).isEqualTo("ANIME");
        assertThat(response.productType()).isEqualTo("ANIME");
        assertThat(response.title()).isEqualTo("Anime Title");
        assertThat(response.originalTitle()).isEqualTo("Anime Title");
        assertThat(response.seasonNumber()).isEqualTo(1);
        assertThat(response.releaseTitle()).isEqualTo("Anime.Release.1080p");
        assertThat(response.releaseIndexer()).isEqualTo("Indexer A");
        assertThat(response.releaseSize()).isEqualTo(8_000_000_000L);
        assertThat(response.resolutionTags()).containsExactly("1080p");
        assertThat(response.dynamicRangeTags()).containsExactly("sdr");
    }

    @Test
    void retriesIndependentAnimeTaskWithSelectedReleaseInSameAttemptChain() {
        authService.currentUser = user(1L, "USER", "owner");
        AnimeMagnetIngestTask original = animeTask(
                "anime-1",
                "Anime Title",
                "PARTIAL_SUCCESS",
                "old-hash",
                LocalDateTime.parse("2026-07-01T10:00:00")
        );
        original.setSourceType("MANUAL_MAGNET");
        original.setAttemptGroupId("anime-group-1");
        when(animeTaskMapper.selectById("anime-1")).thenReturn(original);
        animeReleaseRetryRecorder.nextResponse = animeRetryResponse("anime-2");
        OpenListReleaseRetryRequest request = releaseRetryRequest();

        OpenListManualMagnetRetryResponse response = service.retryWithSelectedRelease(
                "anime",
                "anime-1",
                request
        );

        assertThat(response.detailPath()).isEqualTo("/tasks/anime/anime-2");
        assertThat(animeReleaseRetryRecorder.lastTask).isSameAs(original);
        assertThat(animeReleaseRetryRecorder.lastRelease).isSameAs(request);
        assertThat(animeReleaseRetryRecorder.lastRetryReference.attemptGroupId()).isEqualTo("anime-group-1");
        assertThat(animeReleaseRetryRecorder.lastRetryReference.taskId()).isEqualTo("anime-1");
    }

    @Test
    void allowsProwlarrTaskToReplaceOrReuseCurrentMagnet() {
        authService.currentUser = user(1L, "USER", "owner");
        MovieMagnetIngestTask original = movieTask(
                "movie-1",
                "Movie",
                "FAILED",
                "old-hash",
                LocalDateTime.parse("2026-07-01T10:00:00")
        );
        original.setSourceType("PROWLARR_RELEASE");
        original.setMagnet("magnet:?xt=urn:btih:oldhash");
        when(movieTaskMapper.selectById("movie-1")).thenReturn(original);
        movieSeriesRetryRecorder.nextMovieResponse = movieRetryResponse("movie-2");

        OpenListManualMagnetRetryResponse response = service.replaceManualMagnet(
                "movie",
                "movie-1",
                new OpenListManualMagnetRetryRequest("magnet:?xt=urn:btih:newhash")
        );

        assertThat(response.detailPath()).isEqualTo("/tasks/movie/movie-2");
        assertThat(movieSeriesRetryRecorder.lastMovieMagnet).isEqualTo("magnet:?xt=urn:btih:newhash");

        OpenListManualMagnetRetryResponse reused = service.reuseOriginalManualMagnet("movie", "movie-1");

        assertThat(reused.detailPath()).isEqualTo("/tasks/movie/movie-2");
        assertThat(movieSeriesRetryRecorder.lastMovieMagnet).isEqualTo("magnet:?xt=urn:btih:oldhash");
    }

    @Test
    void rejectsManualMagnetRetryForSucceededTask() {
        authService.currentUser = user(1L, "USER", "owner");
        MovieMagnetIngestTask original = movieTask(
                "movie-1",
                "Movie",
                "SUCCEEDED",
                "old-hash",
                LocalDateTime.parse("2026-07-01T10:00:00")
        );
        when(movieTaskMapper.selectById("movie-1")).thenReturn(original);

        assertThatThrownBy(() -> service.reuseOriginalManualMagnet("movie", "movie-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("状态不支持");
        assertThat(movieSeriesRetryRecorder.movieCallCount).isZero();
    }

    @Test
    void rejectsManualMagnetRetryForAdultWithoutReadingAdultTask() {
        authService.currentUser = user(9L, "ADMIN", "admin");

        assertThatThrownBy(() -> service.reuseOriginalManualMagnet("adult", "adult-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("任务类型不支持");
        verify(adultTaskMapper, never()).selectById("adult-1");
    }

    @Test
    void resubmitsRecoverableAdultBatchAsLinkedAttemptWithInheritedCategory() {
        authService.currentUser = user(9L, "ADMIN", "admin");
        AdultMagnetIngestTask original = adultTask(
                "adult-1",
                9L,
                LocalDateTime.parse("2026-07-01T14:00:00")
        );
        original.setCategory("JAV");
        original.setAttemptGroupId("adult-group-1");
        when(adultTaskMapper.selectById("adult-1")).thenReturn(original);
        adultRetryRecorder.nextTaskId = "adult-2";

        OpenListManualMagnetRetryResponse response = service.retryAdultBatch(
                "adult-1",
                new OpenListAdultBatchRetryRequest(List.of(
                        "magnet:?xt=urn:btih:firsthash",
                        "ed2k://|file|video.mkv|123|0123456789abcdef0123456789abcdef|/"
                ))
        );

        assertThat(response.taskType()).isEqualTo("ADULT");
        assertThat(response.detailPath()).isEqualTo("/tasks/adult/adult-2");
        assertThat(adultRetryRecorder.lastCategory).isEqualTo("JAV");
        assertThat(adultRetryRecorder.lastRetryReference.attemptGroupId()).isEqualTo("adult-group-1");
        assertThat(adultRetryRecorder.lastRetryReference.taskType()).isEqualTo("ADULT");
        assertThat(adultRetryRecorder.lastRetryReference.taskId()).isEqualTo("adult-1");
    }

    @Test
    void rejectsEmptyAdultBatchBeforeCreatingAttempt() {
        authService.currentUser = user(9L, "ADMIN", "admin");
        AdultMagnetIngestTask original = adultTask(
                "adult-1",
                9L,
                LocalDateTime.parse("2026-07-01T14:00:00")
        );
        when(adultTaskMapper.selectById("adult-1")).thenReturn(original);

        assertThatThrownBy(() -> service.retryAdultBatch(
                "adult-1",
                new OpenListAdultBatchRetryRequest(List.of())
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能为空");
        assertThat(adultRetryRecorder.callCount).isZero();
    }

    @Test
    void rejectsAdultBatchRetryForActiveTask() {
        authService.currentUser = user(9L, "ADMIN", "admin");
        AdultMagnetIngestTask original = adultTask(
                "adult-1",
                9L,
                LocalDateTime.parse("2026-07-01T14:00:00")
        );
        original.setStatus("DOWNLOADING");
        when(adultTaskMapper.selectById("adult-1")).thenReturn(original);

        assertThatThrownBy(() -> service.retryAdultBatch(
                "adult-1",
                new OpenListAdultBatchRetryRequest(List.of("magnet:?xt=urn:btih:firsthash"))
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("状态不支持");
        assertThat(adultRetryRecorder.callCount).isZero();
    }

    @Test
    void hidesAdultBatchRetryFromNonAdminWithoutReadingTask() {
        authService.currentUser = user(1L, "USER", "user");

        assertThatThrownBy(() -> service.retryAdultBatch(
                "adult-1",
                new OpenListAdultBatchRetryRequest(List.of("magnet:?xt=urn:btih:firsthash"))
        ))
                .isInstanceOf(BusinessException.class)
                .extracting("httpStatus")
                .isEqualTo(HttpStatus.NOT_FOUND);
        verify(adultTaskMapper, never()).selectById("adult-1");
        assertThat(adultRetryRecorder.callCount).isZero();
    }

    @Test
    void hidesAdultDetailFromNonAdminWithoutReadingAdultMapper() {
        authService.currentUser = user(1L, "USER");

        assertThatThrownBy(() -> service.getOpenListIngestTaskDetail("adult", "adult-1"))
                .isInstanceOf(BusinessException.class)
                .extracting("httpStatus")
                .isEqualTo(HttpStatus.NOT_FOUND);
        verify(adultTaskMapper, never()).selectById("adult-1");
    }

    private User user(Long id, String role) {
        return user(id, role, "user-" + id);
    }

    private User user(Long id, String role, String username) {
        User user = new User();
        user.setId(id);
        user.setRole(role);
        user.setUsername(username);
        return user;
    }

    private MovieMagnetIngestTask movieTask(String id, String title, LocalDateTime updatedAt) {
        return movieTask(id, title, "PENDING", id + "-hash", updatedAt);
    }

    private MovieMagnetIngestTask movieTask(
            String id,
            String title,
            String status,
            String magnetHash,
            LocalDateTime updatedAt
    ) {
        MovieMagnetIngestTask task = new MovieMagnetIngestTask();
        task.setId(id);
        task.setTitle(title);
        task.setOriginalTitle(title);
        task.setYear(2026);
        task.setStatus(status);
        task.setStage("created");
        task.setMagnet("magnet:?xt=urn:btih:" + magnetHash);
        task.setMagnetHash(magnetHash);
        task.setSavePath("/movies/" + title);
        task.setTempPath("/movies/" + title);
        task.setCreatedByUserId(1L);
        task.setOrganizedCount(0);
        task.setSkippedCount(0);
        task.setCreatedAt(updatedAt.minusMinutes(5));
        task.setUpdatedAt(updatedAt);
        return task;
    }

    private SeriesMagnetIngestTask seriesTask(
            String id,
            String title,
            String productType,
            LocalDateTime updatedAt
    ) {
        return seriesTask(
                id,
                title,
                productType,
                "SUBMITTED",
                "PROWLARR_RELEASE",
                null,
                id + "-hash",
                updatedAt
        );
    }

    private SeriesMagnetIngestTask seriesTask(
            String id,
            String title,
            String productType,
            String status,
            String sourceType,
            String releaseTitle,
            String magnetHash,
            LocalDateTime updatedAt
    ) {
        SeriesMagnetIngestTask task = new SeriesMagnetIngestTask();
        task.setId(id);
        task.setTitle(title);
        task.setOriginalTitle(title);
        task.setSeasonNumber(1);
        task.setSeriesName(title);
        task.setSeasonFolder("Season 1");
        task.setStatus(status);
        task.setStage("submitted");
        task.setSourceType(sourceType);
        task.setReleaseTitle(releaseTitle);
        task.setMagnet("magnet:?xt=urn:btih:" + magnetHash);
        task.setMagnetHash(magnetHash);
        task.setTaskProductType(productType);
        task.setSavePath("/series/" + title + "/Season 1");
        task.setTempPath("/series/" + title + "/Season 1");
        task.setCreatedByUserId(1L);
        task.setOrganizedCount(0);
        task.setSkippedCount(0);
        task.setCreatedAt(updatedAt.minusMinutes(5));
        task.setUpdatedAt(updatedAt);
        return task;
    }

    private AnimeMagnetIngestTask animeTask(String id, String title, LocalDateTime updatedAt) {
        return animeTask(id, title, "DOWNLOADING", id + "-hash", updatedAt);
    }

    private AnimeMagnetIngestTask animeTask(
            String id,
            String title,
            String status,
            String magnetHash,
            LocalDateTime updatedAt
    ) {
        AnimeMagnetIngestTask task = new AnimeMagnetIngestTask();
        task.setId(id);
        task.setTitle(title);
        task.setNameCn(title);
        task.setName(title);
        task.setBgmId(id + "-bgm");
        task.setSeasonNumber(1);
        task.setStatus(status);
        task.setStage("downloading");
        task.setMagnet("magnet:?xt=urn:btih:" + magnetHash);
        task.setMagnetHash(magnetHash);
        task.setSavePath("/anime/" + title + "/Season 01");
        task.setTempPath("/anime/" + title + "/Season 01");
        task.setCreatedByUserId(1L);
        task.setOrganizedCount(0);
        task.setSkippedCount(0);
        task.setCreatedAt(updatedAt.minusMinutes(5));
        task.setUpdatedAt(updatedAt);
        return task;
    }

    private MovieMagnetIngestTaskResponse movieRetryResponse(String id) {
        return new MovieMagnetIngestTaskResponse(
                id,
                1L,
                "PENDING",
                "created",
                "Movie",
                "Movie",
                2026,
                "MANUAL_MAGNET",
                null,
                null,
                null,
                List.of(),
                null,
                List.of(),
                "new-hash",
                "/movies/Movie",
                "/movies/Movie",
                0,
                0,
                null,
                LocalDateTime.parse("2026-07-01T11:00:00"),
                LocalDateTime.parse("2026-07-01T11:00:00"),
                null
        );
    }

    private OpenListReleaseRetryRequest releaseRetryRequest() {
        return new OpenListReleaseRetryRequest(
                "New.Release.1080p",
                "Indexer B",
                12_000_000_000L,
                2,
                "https://prowlarr.example/download/2",
                List.of("1080p"),
                List.of("sdr")
        );
    }

    private SeriesMagnetIngestTaskResponse seriesRetryResponse(String id, String productType) {
        return new SeriesMagnetIngestTaskResponse(
                id,
                1L,
                "PENDING",
                "created",
                "Series",
                "Series",
                1,
                productType,
                "MANUAL_MAGNET",
                null,
                null,
                null,
                List.of(),
                null,
                List.of(),
                "Series",
                "Season 1",
                "new-hash",
                "/series/Series/Season 1",
                "/series/Series/Season 1",
                0,
                0,
                null,
                LocalDateTime.parse("2026-07-01T11:00:00"),
                LocalDateTime.parse("2026-07-01T11:00:00"),
                null
        );
    }

    private AnimeMagnetIngestTaskResponse animeRetryResponse(String id) {
        return new AnimeMagnetIngestTaskResponse(
                id,
                1L,
                "PENDING",
                "created",
                "1234",
                "Anime",
                "Anime",
                "Anime",
                1,
                "new-hash",
                "/anime/Anime/Season 01",
                "/anime/Anime/Season 01",
                0,
                0,
                null,
                LocalDateTime.parse("2026-07-01T11:00:00"),
                LocalDateTime.parse("2026-07-01T11:00:00"),
                null
        );
    }

    private AdultMagnetIngestTaskResponse adultRetryResponse(String id) {
        return new AdultMagnetIngestTaskResponse(
                id,
                1L,
                "JAV",
                "PENDING",
                "created",
                "2026-07-01",
                "/adult/2026-07-01",
                1,
                0,
                0,
                0,
                0,
                0,
                0,
                null,
                LocalDateTime.parse("2026-07-01T11:00:00"),
                LocalDateTime.parse("2026-07-01T11:00:00"),
                null
        );
    }

    private AdultMagnetIngestTask adultTask(String id, Long createdByUserId, LocalDateTime updatedAt) {
        AdultMagnetIngestTask task = new AdultMagnetIngestTask();
        task.setId(id);
        task.setCreatedByUserId(createdByUserId);
        task.setCategory("western");
        task.setStatus("PARTIAL_SUCCESS");
        task.setStage("finished");
        task.setDateFolder("2026-07-01");
        task.setTargetPath("/sensitive/adult/path");
        task.setMagnetHashes("adult-sensitive-hash");
        task.setOpenlistTaskIds("adult-openlist-task-id");
        task.setSubmittedCount(7);
        task.setSucceededCount(4);
        task.setFailedCount(1);
        task.setDuplicateCount(2);
        task.setKeptCount(3);
        task.setDeletedCount(1);
        task.setCreatedAt(updatedAt.minusMinutes(5));
        task.setUpdatedAt(updatedAt);
        return task;
    }

    private MovieMagnetIngestTaskLog movieLog(
            Long id,
            String taskId,
            String level,
            String stage,
            String message
    ) {
        MovieMagnetIngestTaskLog log = new MovieMagnetIngestTaskLog();
        log.setId(id);
        log.setTaskId(taskId);
        log.setLevel(level);
        log.setStage(stage);
        log.setMessage(message);
        log.setCreatedAt(LocalDateTime.parse("2026-07-01T10:00:00").plusMinutes(id));
        return log;
    }

    private SeriesMagnetIngestTaskLog seriesLog(
            Long id,
            String taskId,
            String level,
            String stage,
            String message
    ) {
        SeriesMagnetIngestTaskLog log = new SeriesMagnetIngestTaskLog();
        log.setId(id);
        log.setTaskId(taskId);
        log.setLevel(level);
        log.setStage(stage);
        log.setMessage(message);
        log.setCreatedAt(LocalDateTime.parse("2026-07-01T10:00:00").plusMinutes(id));
        return log;
    }

    private AnimeMagnetIngestTaskLog animeLog(
            Long id,
            String taskId,
            String level,
            String stage,
            String message
    ) {
        AnimeMagnetIngestTaskLog log = new AnimeMagnetIngestTaskLog();
        log.setId(id);
        log.setTaskId(taskId);
        log.setLevel(level);
        log.setStage(stage);
        log.setMessage(message);
        log.setCreatedAt(LocalDateTime.parse("2026-07-01T10:00:00").plusMinutes(id));
        return log;
    }

    private AdultMagnetIngestTaskLog adultLog(
            Long id,
            String taskId,
            String level,
            String stage,
            String message
    ) {
        AdultMagnetIngestTaskLog log = new AdultMagnetIngestTaskLog();
        log.setId(id);
        log.setTaskId(taskId);
        log.setLevel(level);
        log.setStage(stage);
        log.setMessage(message);
        log.setCreatedAt(LocalDateTime.parse("2026-07-01T10:00:00").plusMinutes(id));
        return log;
    }

    private static class TestAuthService extends AuthService {

        private User currentUser;

        TestAuthService() {
            super(null, (RegistrationCodeSettingsService) null, null);
        }

        @Override
        public User requireCurrentUser() {
            return currentUser;
        }
    }

    private static class RecordingMovieSeriesResourceSearchService extends MovieSeriesResourceSearchService {

        private MovieSearchResponse movieResponse = new MovieSearchResponse(List.of());
        private SeriesSearchResponse seriesResponse = new SeriesSearchResponse(List.of());
        private String lastMovieTerm;
        private String lastSeriesTerm;

        RecordingMovieSeriesResourceSearchService() {
            super(null, null, null);
        }

        @Override
        public MovieSearchResponse searchMovies(String term) {
            lastMovieTerm = term;
            return movieResponse;
        }

        @Override
        public SeriesSearchResponse searchSeries(String term) {
            lastSeriesTerm = term;
            return seriesResponse;
        }

        private void reset() {
            movieResponse = new MovieSearchResponse(List.of());
            seriesResponse = new SeriesSearchResponse(List.of());
            lastMovieTerm = null;
            lastSeriesTerm = null;
        }
    }

    private static class RecordingMagnetIngestService extends MagnetIngestService {

        private final MovieSeriesRetryRecorder recorder;

        RecordingMagnetIngestService(MovieSeriesRetryRecorder recorder) {
            super(null, null, null, null, null, null, null, null, null, null, null);
            this.recorder = recorder;
        }

        @Override
        public MovieMagnetIngestTaskResponse createMovieRetryTask(
                MovieMagnetIngestTask originalTask,
                String magnet,
                TaskRetryReference retryReference
        ) {
            recorder.movieCallCount++;
            recorder.lastMovieMagnet = magnet;
            recorder.lastMovieRetryReference = retryReference;
            return recorder.nextMovieResponse;
        }

        @Override
        public SeriesMagnetIngestTaskResponse createSeriesRetryTask(
                SeriesMagnetIngestTask originalTask,
                String magnet,
                TaskRetryReference retryReference
        ) {
            recorder.seriesCallCount++;
            recorder.lastSeriesTask = originalTask;
            recorder.lastSeriesMagnet = magnet;
            recorder.lastSeriesRetryReference = retryReference;
            return recorder.nextSeriesResponse;
        }
    }

    private static class RecordingAnimeMagnetIngestTaskService extends AnimeMagnetIngestTaskService {

        private final AnimeRetryRecorder recorder;

        RecordingAnimeMagnetIngestTaskService(AnimeRetryRecorder recorder) {
            super(null, null, null, null, null, null, null, null, null);
            this.recorder = recorder;
        }

        @Override
        public AnimeMagnetIngestTaskResponse createRetryTask(
                AnimeMagnetIngestTask originalTask,
                String magnet,
                TaskRetryReference retryReference
        ) {
            recorder.callCount++;
            recorder.lastMagnet = magnet;
            return recorder.nextResponse;
        }
    }

    private class RecordingAdultMagnetIngestService extends AdultMagnetIngestService {

        private final AdultRetryRecorder recorder;

        RecordingAdultMagnetIngestService(AdultRetryRecorder recorder) {
            super(null, null, null, null, null, null, null, new ObjectMapper());
            this.recorder = recorder;
        }

        @Override
        public AdultMagnetIngestTaskResponse createRetryTask(
                String category,
                List<String> downloadLinks,
                TaskRetryReference retryReference
        ) {
            recorder.callCount++;
            recorder.lastCategory = category;
            recorder.lastRetryReference = retryReference;
            return adultRetryResponse(recorder.nextTaskId);
        }
    }

    private static class RecordingProwlarrReleaseIngestService extends ProwlarrReleaseIngestService {

        private final MovieSeriesReleaseRetryRecorder movieSeriesRecorder;
        private final AnimeReleaseRetryRecorder animeRecorder;

        RecordingProwlarrReleaseIngestService(
                MovieSeriesReleaseRetryRecorder movieSeriesRecorder,
                AnimeReleaseRetryRecorder animeRecorder
        ) {
            super(null, null, null, null, null);
            this.movieSeriesRecorder = movieSeriesRecorder;
            this.animeRecorder = animeRecorder;
        }

        @Override
        public MovieMagnetIngestTaskResponse ingestSelectedMovieRetry(
                MovieMagnetIngestTask originalTask,
                OpenListReleaseRetryRequest request,
                TaskRetryReference retryReference
        ) {
            movieSeriesRecorder.lastMovieTask = originalTask;
            movieSeriesRecorder.lastRelease = request;
            movieSeriesRecorder.lastRetryReference = retryReference;
            return movieSeriesRecorder.nextMovieResponse;
        }

        @Override
        public SeriesMagnetIngestTaskResponse ingestSelectedSeriesRetry(
                SeriesMagnetIngestTask originalTask,
                OpenListReleaseRetryRequest request,
                TaskRetryReference retryReference
        ) {
            movieSeriesRecorder.lastSeriesTask = originalTask;
            movieSeriesRecorder.lastRelease = request;
            movieSeriesRecorder.lastRetryReference = retryReference;
            return movieSeriesRecorder.nextSeriesResponse;
        }

        @Override
        public AnimeMagnetIngestTaskResponse ingestSelectedAnimeRetry(
                AnimeMagnetIngestTask originalTask,
                OpenListReleaseRetryRequest request,
                TaskRetryReference retryReference
        ) {
            animeRecorder.lastTask = originalTask;
            animeRecorder.lastRelease = request;
            animeRecorder.lastRetryReference = retryReference;
            return animeRecorder.nextResponse;
        }
    }

    private static class MovieSeriesRetryRecorder {

        private MovieMagnetIngestTaskResponse nextMovieResponse;
        private SeriesMagnetIngestTaskResponse nextSeriesResponse;
        private int movieCallCount;
        private int seriesCallCount;
        private String lastMovieMagnet;
        private String lastSeriesMagnet;
        private SeriesMagnetIngestTask lastSeriesTask;
        private TaskRetryReference lastMovieRetryReference;
        private TaskRetryReference lastSeriesRetryReference;

        void reset() {
            nextMovieResponse = null;
            nextSeriesResponse = null;
            movieCallCount = 0;
            seriesCallCount = 0;
            lastMovieMagnet = null;
            lastSeriesMagnet = null;
            lastSeriesTask = null;
            lastMovieRetryReference = null;
            lastSeriesRetryReference = null;
        }
    }

    private static class AnimeRetryRecorder {

        private AnimeMagnetIngestTaskResponse nextResponse;
        private int callCount;
        private String lastMagnet;

        void reset() {
            nextResponse = null;
            callCount = 0;
            lastMagnet = null;
        }
    }

    private static class MovieSeriesReleaseRetryRecorder {

        private MovieMagnetIngestTaskResponse nextMovieResponse;
        private SeriesMagnetIngestTaskResponse nextSeriesResponse;
        private MovieMagnetIngestTask lastMovieTask;
        private SeriesMagnetIngestTask lastSeriesTask;
        private OpenListReleaseRetryRequest lastRelease;
        private TaskRetryReference lastRetryReference;

        void reset() {
            nextMovieResponse = null;
            nextSeriesResponse = null;
            lastMovieTask = null;
            lastSeriesTask = null;
            lastRelease = null;
            lastRetryReference = null;
        }
    }

    private static class AnimeReleaseRetryRecorder {

        private AnimeMagnetIngestTaskResponse nextResponse;
        private AnimeMagnetIngestTask lastTask;
        private OpenListReleaseRetryRequest lastRelease;
        private TaskRetryReference lastRetryReference;

        void reset() {
            nextResponse = null;
            lastTask = null;
            lastRelease = null;
            lastRetryReference = null;
        }
    }

    private static class AdultRetryRecorder {

        private String nextTaskId;
        private int callCount;
        private String lastCategory;
        private TaskRetryReference lastRetryReference;

        void reset() {
            nextTaskId = null;
            callCount = 0;
            lastCategory = null;
            lastRetryReference = null;
        }
    }
}
