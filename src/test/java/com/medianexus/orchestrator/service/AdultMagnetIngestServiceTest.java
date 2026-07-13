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
import com.medianexus.orchestrator.integration.openlist.OpenListFileInfo;
import com.medianexus.orchestrator.mapper.AdultMagnetIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.AdultMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.UserMapper;
import com.medianexus.orchestrator.model.AdultMagnetIngestTask;
import com.medianexus.orchestrator.model.User;
import com.medianexus.orchestrator.service.organization.LibraryOrganizer;
import com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
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

    @Test
    void plansAdultCleanupBeforeTopLevelPromotionAndKeepsExactHundredMibVideo() throws Exception {
        String targetPath = "/adult/JAV/7.13";
        String tempPath = targetPath + "/.adult-task-test";
        RecordingLibraryOrganizer organizer = new RecordingLibraryOrganizer();
        FakeAdultOpenListClient client = new FakeAdultOpenListClient(openListProperties, objectMapper, targetPath, tempPath);
        AdultMagnetIngestService planningService = new AdultMagnetIngestService(
                taskMapper,
                taskLogMapper,
                client,
                openListProperties,
                new MovieSeriesFileRenameService(),
                organizer,
                authService,
                null,
                objectMapper,
                new ControlledExecutor(),
                new ControlledExecutor(),
                new ControlledExecutor()
        );
        Class<?> itemType = Class.forName(AdultMagnetIngestService.class.getName() + "$AdultMagnetItem");
        Constructor<?> itemConstructor = itemType.getDeclaredConstructor(
                int.class,
                String.class,
                String.class,
                String.class,
                String.class
        );
        itemConstructor.setAccessible(true);
        Object item = itemConstructor.newInstance(1, "magnet:?xt=urn:btih:test", "test", targetPath, tempPath);
        Method organize = AdultMagnetIngestService.class.getDeclaredMethod(
                "cleanAndPromoteTempDirectory",
                String.class,
                itemType
        );
        organize.setAccessible(true);

        organize.invoke(planningService, "task-1", item);

        assertThat(organizer.plans).hasSize(2);
        assertThat(organizer.plans.get(0).deletions())
                .extracting(LibraryOrganizationPlan.DeleteOperation::path)
                .containsExactly(tempPath + "/Release/small.mkv");
        assertThat(organizer.plans.get(1).moves())
                .extracting(LibraryOrganizationPlan.MoveOperation::sourcePath)
                .containsExactly(tempPath + "/Release");
        assertThat(organizer.plans.get(1).expectedTargetNames()).containsExactly("Release");
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

    private static class RecordingLibraryOrganizer implements LibraryOrganizer {

        private final List<LibraryOrganizationPlan> plans = new ArrayList<>();

        @Override
        public void organize(
                LibraryOrganizationPlan plan,
                com.medianexus.orchestrator.service.organization.LibraryOrganizationProgressObserver progressObserver
        ) {
            plans.add(plan);
        }
    }

    private static class FakeAdultOpenListClient extends OpenListClient {

        private static final long HUNDRED_MIB = 100L * 1024L * 1024L;
        private final String targetPath;
        private final String tempPath;

        FakeAdultOpenListClient(
                OpenListProperties properties,
                ObjectMapper objectMapper,
                String targetPath,
                String tempPath
        ) {
            super(properties, objectMapper);
            this.targetPath = targetPath;
            this.tempPath = tempPath;
        }

        @Override
        public List<OpenListFileInfo> findFiles(String path, boolean refresh) {
            return List.of(
                    new OpenListFileInfo("kept.mkv", HUNDRED_MIB, false, tempPath + "/Release"),
                    new OpenListFileInfo("small.mkv", HUNDRED_MIB - 1, false, tempPath + "/Release")
            );
        }

        @Override
        public List<OpenListFileInfo> listFiles(String path, boolean refresh) {
            if (path.equals(tempPath)) {
                return List.of(new OpenListFileInfo("Release", 0L, true, tempPath));
            }
            if (path.equals(targetPath)) {
                return List.of(new OpenListFileInfo(".adult-task-test", 0L, true, targetPath));
            }
            return List.of();
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
