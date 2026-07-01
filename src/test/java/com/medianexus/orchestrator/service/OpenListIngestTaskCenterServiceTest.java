package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.medianexus.orchestrator.dto.taskcenter.response.OpenListIngestTaskCenterListResponse;
import com.medianexus.orchestrator.mapper.AnimeMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.MovieMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.SeriesMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.UserMapper;
import com.medianexus.orchestrator.model.AnimeMagnetIngestTask;
import com.medianexus.orchestrator.model.MovieMagnetIngestTask;
import com.medianexus.orchestrator.model.SeriesMagnetIngestTask;
import com.medianexus.orchestrator.model.User;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class OpenListIngestTaskCenterServiceTest {

    private final TestAuthService authService = new TestAuthService();
    private final MovieMagnetIngestTaskMapper movieTaskMapper = mock(MovieMagnetIngestTaskMapper.class);
    private final SeriesMagnetIngestTaskMapper seriesTaskMapper = mock(SeriesMagnetIngestTaskMapper.class);
    private final AnimeMagnetIngestTaskMapper animeTaskMapper = mock(AnimeMagnetIngestTaskMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final OpenListIngestTaskCenterService service = new OpenListIngestTaskCenterService(
            authService,
            movieTaskMapper,
            seriesTaskMapper,
            animeTaskMapper,
            userMapper
    );

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
