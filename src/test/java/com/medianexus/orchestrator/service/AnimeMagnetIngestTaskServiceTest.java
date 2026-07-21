package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.OpenListProperties;
import com.medianexus.orchestrator.dto.magnet.request.AnimeMagnetIngestTaskCreateRequest;
import com.medianexus.orchestrator.integration.openlist.OpenListClient;
import com.medianexus.orchestrator.integration.openlist.OpenListLibraryOrganizer;
import com.medianexus.orchestrator.integration.openlist.OpenListFileInfo;
import com.medianexus.orchestrator.mapper.AnimeMagnetIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.AnimeMagnetIngestTaskMapper;
import com.medianexus.orchestrator.model.AnimeMagnetIngestTask;
import com.medianexus.orchestrator.model.User;
import com.medianexus.orchestrator.model.UserActionType;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.test.util.ReflectionTestUtils;

class AnimeMagnetIngestTaskServiceTest {

    private final AnimeMagnetIngestTaskMapper taskMapper = mock(AnimeMagnetIngestTaskMapper.class);
    private final AnimeMagnetIngestTaskLogMapper taskLogMapper = mock(AnimeMagnetIngestTaskLogMapper.class);
    private final TestAuthService authService = new TestAuthService();
    private final TestUserActionQuotaService quotaService = new TestUserActionQuotaService();
    private final MediaLibraryPresenceService mediaLibraryPresenceService = mock(MediaLibraryPresenceService.class);
    private final ControlledExecutor taskExecutor = new ControlledExecutor();
    private final AtomicReference<AnimeMagnetIngestTask> insertedTask = new AtomicReference<>();
    private final AnimeMagnetIngestTaskService service = new AnimeMagnetIngestTaskService(
            taskMapper,
            taskLogMapper,
            null,
            null,
            null,
            null,
            authService,
            quotaService,
            null,
            mediaLibraryPresenceService,
            taskExecutor
    );

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setId(1L);
        user.setRole("USER");
        authService.currentUser = user;
        when(taskMapper.selectOne(any())).thenReturn(null);
        when(taskMapper.insert(any(AnimeMagnetIngestTask.class))).thenAnswer(invocation -> {
            insertedTask.set(invocation.getArgument(0));
            return 1;
        });
    }

    @Test
    void exposesOneExplicitSpringInjectionConstructor() {
        AutowiredAnnotationBeanPostProcessor processor = new AutowiredAnnotationBeanPostProcessor();

        assertThat(processor.determineCandidateConstructors(
                AnimeMagnetIngestTaskService.class,
                "animeMagnetIngestTaskService"
        )).hasSize(1);
    }

    @Test
    void createsAnimeTaskWithChineseFolderEvenWhenThemoviedbNameIsProvided() {
        AnimeMagnetIngestTaskService service = serviceWithAnimePathTemplate();

        service.createTask(new AnimeMagnetIngestTaskCreateRequest(
                "magnet:?xt=urn:btih:frierenhash",
                null,
                null,
                "葬送的芙莉莲",
                "葬送的芙莉莲",
                "Frieren: Beyond Journey's End",
                2,
                "Frieren: Beyond Journey's End",
                209867
        ));

        assertThat(insertedTask.get().getTitle()).isEqualTo("葬送的芙莉莲");
        assertThat(insertedTask.get().getBgmId()).isNull();
        assertThat(insertedTask.get().getName()).isEqualTo("Frieren: Beyond Journey's End");
        assertThat(insertedTask.get().getTmdbId()).isEqualTo(209867);
        assertThat(insertedTask.get().getSavePath()).isEqualTo("/pikpak/Media/Anime/葬送的芙莉莲/Season 02");
    }

    @Test
    void removesNewRetryAttemptWhenTaskSchedulingIsRejected() {
        taskExecutor.reject = true;

        assertThatThrownBy(() -> service.createRetryTask(
                originalTask(),
                "magnet:?xt=urn:btih:newhash",
                new TaskRetryReference("anime-group-1", "ANIME", "anime-1")
        ))
                .isInstanceOf(RejectedExecutionException.class);

        verify(taskLogMapper).delete(any());
        verify(taskMapper).deleteById(insertedTask.get().getId());
    }

    @Test
    void animeOrganizeDeletesAuxiliaryDirectoriesWithoutScanningThemWhenRootHasEpisodes() {
        OpenListProperties properties = new OpenListProperties();
        properties.setAnimeRenameTemplate("{title} S{seasonFormat}E{episodeFormat}");
        properties.setAnimeExcludePatterns("特别篇,\\d-\\d,总集");
        OpenListClient openListClient = mock(OpenListClient.class);
        when(openListClient.normalizePath(anyString())).thenAnswer(invocation -> normalizePath(invocation.getArgument(0)));
        when(openListClient.joinPath(anyString(), anyString())).thenAnswer(invocation ->
                normalizePath(invocation.getArgument(0) + "/" + invocation.getArgument(1)));
        when(openListClient.listFiles("/anime/Show/Season 01")).thenReturn(List.of(
                new OpenListFileInfo("[Group] Show - 01.mkv", 1024L, false, "/anime/Show/Season 01"),
                new OpenListFileInfo("[Group] Show - 01.ass", 64L, false, "/anime/Show/Season 01"),
                new OpenListFileInfo("Scans", null, true, "/anime/Show/Season 01"),
                new OpenListFileInfo("CDs", null, true, "/anime/Show/Season 01"),
                new OpenListFileInfo("NCOP", null, true, "/anime/Show/Season 01")
        ));

        AnimeMagnetIngestTaskService service = new AnimeMagnetIngestTaskService(
                taskMapper,
                taskLogMapper,
                openListClient,
                properties,
                new AnimeEpisodeRenameService(properties),
                new OpenListLibraryOrganizer(openListClient),
                authService,
                quotaService,
                null,
                mediaLibraryPresenceService,
                taskExecutor
        );

        ReflectionTestUtils.invokeMethod(service, "organizeFiles", animeTask());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> deletedNames = ArgumentCaptor.forClass(List.class);
        verify(openListClient).remove(eq("/anime/Show/Season 01"), deletedNames.capture());
        assertThat(deletedNames.getValue()).containsExactly("Scans", "CDs", "NCOP");
        verify(openListClient, never()).listFiles("/anime/Show/Season 01/Scans");
        verify(openListClient, never()).listFiles("/anime/Show/Season 01/CDs");
        verify(openListClient, never()).listFiles("/anime/Show/Season 01/NCOP");
    }

    @Test
    void animeVideoVisibilityCheckDoesNotScanAuxiliaryDirectories() {
        OpenListProperties properties = new OpenListProperties();
        OpenListClient openListClient = mock(OpenListClient.class);
        when(openListClient.normalizePath(anyString())).thenAnswer(invocation -> normalizePath(invocation.getArgument(0)));
        when(openListClient.joinPath(anyString(), anyString())).thenAnswer(invocation ->
                normalizePath(invocation.getArgument(0) + "/" + invocation.getArgument(1)));
        when(openListClient.listFiles("/anime/Show/Season 01")).thenReturn(List.of(
                new OpenListFileInfo("Release", null, true, "/anime/Show/Season 01"),
                new OpenListFileInfo("Scans", null, true, "/anime/Show/Season 01")
        ));
        when(openListClient.listFiles("/anime/Show/Season 01/Release")).thenReturn(List.of(
                new OpenListFileInfo("[Group] Show - 01.mkv", 1024L, false, "/anime/Show/Season 01/Release")
        ));

        AnimeMagnetIngestTaskService service = new AnimeMagnetIngestTaskService(
                taskMapper,
                taskLogMapper,
                openListClient,
                properties,
                new AnimeEpisodeRenameService(properties),
                new OpenListLibraryOrganizer(openListClient),
                authService,
                quotaService,
                null,
                mediaLibraryPresenceService,
                taskExecutor
        );

        Boolean hasVideoFiles = ReflectionTestUtils.invokeMethod(service, "hasVideoFiles", "/anime/Show/Season 01");

        assertThat(hasVideoFiles).isTrue();
        verify(openListClient, never()).listFiles("/anime/Show/Season 01/Scans");
    }

    @Test
    void animeOrganizeSplitsLargeRenameAndMoveBatches() {
        OpenListProperties properties = new OpenListProperties();
        properties.setAnimeRenameTemplate("{title} S{seasonFormat}E{episodeFormat}");
        properties.setAnimeExcludePatterns("特别篇,\\d-\\d,总集");
        OpenListClient openListClient = mock(OpenListClient.class);
        when(openListClient.normalizePath(anyString())).thenAnswer(invocation -> normalizePath(invocation.getArgument(0)));
        when(openListClient.joinPath(anyString(), anyString())).thenAnswer(invocation ->
                normalizePath(invocation.getArgument(0) + "/" + invocation.getArgument(1)));
        List<OpenListFileInfo> releaseFiles = new ArrayList<>();
        for (int episode = 1; episode <= 25; episode++) {
            releaseFiles.add(new OpenListFileInfo(
                    String.format("[Group] Show - %02d.mkv", episode),
                    1024L,
                    false,
                    "/anime/Show/Season 01/Release"
            ));
        }
        when(openListClient.listFiles("/anime/Show/Season 01")).thenReturn(List.of(
                new OpenListFileInfo("Release", null, true, "/anime/Show/Season 01")
        ));
        when(openListClient.listFiles("/anime/Show/Season 01/Release")).thenReturn(releaseFiles);

        AnimeMagnetIngestTaskService service = new AnimeMagnetIngestTaskService(
                taskMapper,
                taskLogMapper,
                openListClient,
                properties,
                new AnimeEpisodeRenameService(properties),
                new OpenListLibraryOrganizer(openListClient),
                authService,
                quotaService,
                null,
                mediaLibraryPresenceService,
                taskExecutor
        );

        ReflectionTestUtils.invokeMethod(service, "organizeFiles", animeTask());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> renameBatches = ArgumentCaptor.forClass(Map.class);
        verify(openListClient, times(3)).batchRename(eq("/anime/Show/Season 01/Release"), renameBatches.capture());
        assertThat(renameBatches.getAllValues()).extracting(Map::size).containsExactly(10, 10, 5);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> moveBatches = ArgumentCaptor.forClass(List.class);
        verify(openListClient, times(3)).move(
                eq("/anime/Show/Season 01/Release"),
                eq("/anime/Show/Season 01"),
                moveBatches.capture()
        );
        assertThat(moveBatches.getAllValues()).extracting(List::size).containsExactly(10, 10, 5);
    }

    private AnimeMagnetIngestTask originalTask() {
        AnimeMagnetIngestTask task = new AnimeMagnetIngestTask();
        task.setId("anime-1");
        task.setBgmId("1234");
        task.setBgmUrl("https://bgm.tv/subject/1234");
        task.setTitle("Anime");
        task.setNameCn("Anime CN");
        task.setName("Anime Original");
        task.setSeasonNumber(1);
        task.setSavePath("/anime/Anime/Season 01");
        task.setAttemptGroupId("anime-group-1");
        task.setCreatedAt(LocalDateTime.parse("2026-07-01T10:00:00"));
        task.setUpdatedAt(LocalDateTime.parse("2026-07-01T10:00:00"));
        return task;
    }

    private AnimeMagnetIngestTask animeTask() {
        AnimeMagnetIngestTask task = new AnimeMagnetIngestTask();
        task.setId("anime-1");
        task.setTitle("Show");
        task.setSeasonNumber(1);
        task.setSavePath("/anime/Show/Season 01");
        task.setTempPath("/anime/Show/Season 01");
        return task;
    }

    private static String normalizePath(String path) {
        String normalized = path == null ? "" : path.trim().replaceAll("/{2,}", "/");
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private AnimeMagnetIngestTaskService serviceWithAnimePathTemplate() {
        OpenListProperties properties = new OpenListProperties();
        properties.setTimeout(Duration.ofSeconds(5));
        properties.setAnimePathTemplate("/pikpak/Media/Anime/{themoviedbName}/Season {seasonFormat}");
        OpenListClient openListClient = new OpenListClient(properties, new ObjectMapper());
        return new AnimeMagnetIngestTaskService(
                taskMapper,
                taskLogMapper,
                openListClient,
                properties,
                new AnimeEpisodeRenameService(properties),
                new OpenListLibraryOrganizer(openListClient),
                authService,
                quotaService,
                null,
                mediaLibraryPresenceService,
                taskExecutor
        );
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

    private static class TestUserActionQuotaService extends UserActionQuotaService {

        TestUserActionQuotaService() {
            super(null, null);
        }

        @Override
        public void consumeDailyContentCreate(User user, UserActionType actionType) {
        }
    }

    private static class ControlledExecutor extends AbstractExecutorService {

        private boolean reject;

        @Override
        public void execute(Runnable command) {
            if (reject) {
                throw new RejectedExecutionException("executor stopped");
            }
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return false;
        }
    }
}
