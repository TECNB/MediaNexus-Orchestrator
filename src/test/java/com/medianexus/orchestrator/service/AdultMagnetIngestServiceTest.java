package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.config.OpenListProperties;
import com.medianexus.orchestrator.config.AuthProperties;
import com.medianexus.orchestrator.integration.openlist.OpenListClient;
import com.medianexus.orchestrator.mapper.AdultMagnetIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.AdultMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.UserMapper;
import com.medianexus.orchestrator.model.AdultMagnetIngestTask;
import com.medianexus.orchestrator.model.User;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class AdultMagnetIngestServiceTest {

    private final AdultMagnetIngestTaskMapper taskMapper = mock(AdultMagnetIngestTaskMapper.class);
    private final AdultMagnetIngestTaskLogMapper taskLogMapper = mock(AdultMagnetIngestTaskLogMapper.class);
    private final OpenListProperties openListProperties = openListProperties();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpenListClient openListClient = new OpenListClient(openListProperties, objectMapper);
    private final TestAuthService authService = new TestAuthService();
    private final ControlledExecutor taskExecutor = new ControlledExecutor();
    private final AtomicReference<AdultMagnetIngestTask> insertedTask = new AtomicReference<>();
    private final AdultMagnetIngestService service = new AdultMagnetIngestService(
            taskMapper,
            taskLogMapper,
            openListClient,
            openListProperties,
            new MovieSeriesFileRenameService(),
            authService,
            null,
            objectMapper,
            taskExecutor,
            new ControlledExecutor(),
            new ControlledExecutor()
    );

    private static OpenListProperties openListProperties() {
        OpenListProperties properties = new OpenListProperties();
        properties.setTimeout(Duration.ofSeconds(5));
        properties.setAdultRootPath("/adult");
        return properties;
    }

    @BeforeEach
    void setUp() {
        User admin = new User();
        admin.setId(9L);
        admin.setRole("ADMIN");
        authService.admin = admin;
        when(taskMapper.insert(any(AdultMagnetIngestTask.class))).thenAnswer(invocation -> {
            insertedTask.set(invocation.getArgument(0));
            return 1;
        });
        when(taskMapper.selectById(anyString())).thenAnswer(invocation -> insertedTask.get());
    }

    @Test
    void createsAdultRetryAsNewLinkedAttemptAndPersistsNormalizedBatch() {
        service.createRetryTask(
                "JAV",
                List.of(
                        "  magnet:?xt=urn:btih:firsthash  ",
                        "ed2k://|file|video.mkv|123|0123456789abcdef0123456789abcdef|/"
                ),
                new TaskRetryReference("adult-group-1", "ADULT", "adult-1")
        );

        AdultMagnetIngestTask task = insertedTask.get();
        assertThat(task.getId()).isNotEqualTo("adult-1");
        assertThat(task.getCategory()).isEqualTo("JAV");
        assertThat(task.getAttemptGroupId()).isEqualTo("adult-group-1");
        assertThat(task.getRetryOfTaskType()).isEqualTo("ADULT");
        assertThat(task.getRetryOfTaskId()).isEqualTo("adult-1");
        assertThat(task.getDownloadLinksJson()).isEqualTo(
                "[\"magnet:?xt=urn:btih:firsthash\",\"ed2k://|file|video.mkv|123|0123456789abcdef0123456789abcdef|/\"]"
        );
        assertThat(task.getMagnetCount()).isEqualTo(2);
    }

    @Test
    void removesNewAttemptWhenTaskSchedulingIsRejected() {
        taskExecutor.reject = true;

        assertThatThrownBy(() -> service.createRetryTask(
                "OTHER",
                List.of("magnet:?xt=urn:btih:firsthash"),
                new TaskRetryReference("adult-group-1", "ADULT", "adult-1")
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("调度失败");

        verify(taskLogMapper).delete(any());
        verify(taskMapper).deleteById(insertedTask.get().getId());
    }

    private static class TestAuthService extends AuthService {

        private User admin;

        TestAuthService() {
            super(mock(UserMapper.class), new AuthProperties(), mock(PasswordEncoder.class));
        }

        @Override
        public User requireAdminUser() {
            return admin;
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
            return true;
        }
    }
}
