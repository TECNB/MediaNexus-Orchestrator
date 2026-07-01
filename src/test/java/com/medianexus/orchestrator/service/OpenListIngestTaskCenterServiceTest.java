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
        MovieMagnetIngestTask task = new MovieMagnetIngestTask();
        task.setId(id);
        task.setTitle(title);
        task.setYear(2026);
        task.setStatus("PENDING");
        task.setStage("created");
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
        SeriesMagnetIngestTask task = new SeriesMagnetIngestTask();
        task.setId(id);
        task.setSeriesName(title);
        task.setSeasonFolder("Season 1");
        task.setStatus("SUBMITTED");
        task.setStage("submitted");
        task.setSourceType("PROWLARR_RELEASE");
        task.setTaskProductType(productType);
        task.setCreatedByUserId(1L);
        task.setOrganizedCount(0);
        task.setSkippedCount(0);
        task.setCreatedAt(updatedAt.minusMinutes(5));
        task.setUpdatedAt(updatedAt);
        return task;
    }

    private AnimeMagnetIngestTask animeTask(String id, String title, LocalDateTime updatedAt) {
        AnimeMagnetIngestTask task = new AnimeMagnetIngestTask();
        task.setId(id);
        task.setTitle(title);
        task.setSeasonNumber(1);
        task.setStatus("DOWNLOADING");
        task.setStage("downloading");
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
