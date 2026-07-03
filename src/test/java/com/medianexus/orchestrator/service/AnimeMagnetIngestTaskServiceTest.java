package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.OpenListProperties;
import com.medianexus.orchestrator.dto.magnet.request.AnimeMagnetIngestTaskCreateRequest;
import com.medianexus.orchestrator.integration.openlist.OpenListClient;
import com.medianexus.orchestrator.mapper.AnimeMagnetIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.AnimeMagnetIngestTaskMapper;
import com.medianexus.orchestrator.model.AnimeMagnetIngestTask;
import com.medianexus.orchestrator.model.User;
import com.medianexus.orchestrator.model.UserActionType;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;

class AnimeMagnetIngestTaskServiceTest {

    private final AnimeMagnetIngestTaskMapper taskMapper = mock(AnimeMagnetIngestTaskMapper.class);
    private final AnimeMagnetIngestTaskLogMapper taskLogMapper = mock(AnimeMagnetIngestTaskLogMapper.class);
    private final TestAuthService authService = new TestAuthService();
    private final TestUserActionQuotaService quotaService = new TestUserActionQuotaService();
    private final ControlledExecutor taskExecutor = new ControlledExecutor();
    private final AtomicReference<AnimeMagnetIngestTask> insertedTask = new AtomicReference<>();
    private final AnimeMagnetIngestTaskService service = new AnimeMagnetIngestTaskService(
            taskMapper,
            taskLogMapper,
            null,
            null,
            null,
            authService,
            quotaService,
            null,
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
                "1234",
                "https://bgm.tv/subject/1234",
                "葬送的芙莉莲",
                "葬送的芙莉莲",
                "Frieren: Beyond Journey's End",
                2,
                "Frieren: Beyond Journey's End"
        ));

        assertThat(insertedTask.get().getTitle()).isEqualTo("葬送的芙莉莲");
        assertThat(insertedTask.get().getName()).isEqualTo("Frieren: Beyond Journey's End");
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
                authService,
                quotaService,
                null,
                taskExecutor
        );
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
