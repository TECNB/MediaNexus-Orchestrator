package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medianexus.orchestrator.dto.taskcenter.response.OpenListIngestTaskCenterListResponse;
import com.medianexus.orchestrator.mapper.AdultMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.AnimeMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.MovieMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.SeriesMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.UserMapper;
import com.medianexus.orchestrator.model.AdultMagnetIngestTask;
import com.medianexus.orchestrator.model.AnimeMagnetIngestTask;
import com.medianexus.orchestrator.model.MovieMagnetIngestTask;
import com.medianexus.orchestrator.model.SeriesMagnetIngestTask;
import com.medianexus.orchestrator.model.User;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenListIngestTaskCenterServiceTest {

    private final TestAuthService authService = new TestAuthService();
    private final MovieMagnetIngestTaskMapper movieTaskMapper = mock(MovieMagnetIngestTaskMapper.class);
    private final SeriesMagnetIngestTaskMapper seriesTaskMapper = mock(SeriesMagnetIngestTaskMapper.class);
    private final AnimeMagnetIngestTaskMapper animeTaskMapper = mock(AnimeMagnetIngestTaskMapper.class);
    private final AdultMagnetIngestTaskMapper adultTaskMapper = mock(AdultMagnetIngestTaskMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final OpenListIngestTaskCenterService service = new OpenListIngestTaskCenterService(
            authService,
            movieTaskMapper,
            seriesTaskMapper,
            animeTaskMapper,
            adultTaskMapper,
            userMapper
    );

    @BeforeEach
    void setUp() {
        when(adultTaskMapper.selectList(any())).thenReturn(List.of());
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
        assertThat(response.items().get(0).detailPath()).isEqualTo("/magnet-ingest");
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
        task.setYear(2026);
        task.setStatus(status);
        task.setStage("created");
        task.setMagnetHash(magnetHash);
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
        task.setSeriesName(title);
        task.setSeasonFolder("Season 1");
        task.setStatus(status);
        task.setStage("submitted");
        task.setSourceType(sourceType);
        task.setReleaseTitle(releaseTitle);
        task.setMagnetHash(magnetHash);
        task.setTaskProductType(productType);
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
        task.setSeasonNumber(1);
        task.setStatus(status);
        task.setStage("downloading");
        task.setMagnetHash(magnetHash);
        task.setCreatedByUserId(1L);
        task.setOrganizedCount(0);
        task.setSkippedCount(0);
        task.setCreatedAt(updatedAt.minusMinutes(5));
        task.setUpdatedAt(updatedAt);
        return task;
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

    private static class TestAuthService extends AuthService {

        private User currentUser;

        TestAuthService() {
            super(null, null, null);
        }

        @Override
        public User requireCurrentUser() {
            return currentUser;
        }
    }
}
