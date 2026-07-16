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
import java.util.Map;
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
    void preparesAdultTempDirectoriesAsOneOpenListBatch() throws Exception {
        String targetPath = "/adult/JAV/7.15";
        String firstTempPath = targetPath + "/adult-task-batch-01";
        String secondTempPath = targetPath + "/adult-task-batch-02";
        OpenListClient client = mock(OpenListClient.class);
        when(client.normalizePath(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        AdultMagnetIngestService directoryService = new AdultMagnetIngestService(
                taskMapper,
                taskLogMapper,
                client,
                openListProperties,
                new MovieSeriesFileRenameService(),
                new RecordingLibraryOrganizer(),
                authService,
                null,
                objectMapper,
                new ControlledExecutor(),
                new ControlledExecutor()
        );
        AdultMagnetIngestTask task = new AdultMagnetIngestTask();
        task.setId("task-1");
        task.setTargetPath(targetPath);
        Class<?> itemType = Class.forName(AdultMagnetIngestService.class.getName() + "$AdultMagnetItem");
        Constructor<?> itemConstructor = itemType.getDeclaredConstructor(
                int.class,
                String.class,
                String.class,
                String.class,
                String.class
        );
        itemConstructor.setAccessible(true);
        Object firstItem = itemConstructor.newInstance(1, "magnet:?xt=urn:btih:first", "first", targetPath, firstTempPath);
        Object secondItem = itemConstructor.newInstance(2, "magnet:?xt=urn:btih:second", "second", targetPath, secondTempPath);
        Method prepareTargetDirectories = AdultMagnetIngestService.class.getDeclaredMethod(
                "prepareTargetDirectories",
                AdultMagnetIngestTask.class,
                List.class,
                String.class
        );
        prepareTargetDirectories.setAccessible(true);

        prepareTargetDirectories.invoke(directoryService, task, List.of(firstItem, secondItem), "/adult");

        verify(client).ensureDirectoryReady(targetPath, "/adult");
        verify(client).ensureChildDirectoriesReady(
                targetPath,
                List.of("adult-task-batch-01", "adult-task-batch-02")
        );
    }

    @Test
    void plansAdultCleanupBeforeTopLevelPromotionAndKeepsExactHundredMibVideo() throws Exception {
        String targetPath = "/adult/JAV/7.13";
        String tempPath = targetPath + "/adult-task-test";
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
        Class<?> preparedType = Class.forName(AdultMagnetIngestService.class.getName() + "$AdultPreparedItem");
        Method prepare = AdultMagnetIngestService.class.getDeclaredMethod(
                "prepareTempDirectory",
                String.class,
                itemType
        );
        prepare.setAccessible(true);
        Method markPrepared = itemType.getDeclaredMethod("markPrepared", preparedType);
        markPrepared.setAccessible(true);
        Method planPromotionBatch = AdultMagnetIngestService.class.getDeclaredMethod(
                "planPromotionBatch",
                String.class,
                String.class,
                List.class
        );
        planPromotionBatch.setAccessible(true);

        Object prepared = prepare.invoke(planningService, "task-1", item);
        markPrepared.invoke(item, prepared);
        Object batch = planPromotionBatch.invoke(planningService, "task-1", targetPath, List.of(item));
        Method batchPlan = batch.getClass().getDeclaredMethod("plan");
        batchPlan.setAccessible(true);
        LibraryOrganizationPlan promotionPlan = (LibraryOrganizationPlan) batchPlan.invoke(batch);

        assertThat(organizer.plans).hasSize(1);
        assertThat(organizer.plans.get(0).deletions())
                .extracting(LibraryOrganizationPlan.DeleteOperation::path)
                .containsExactly(tempPath + "/Release/small.mkv");
        assertThat(promotionPlan.moves())
                .extracting(LibraryOrganizationPlan.MoveOperation::sourcePath)
                .containsExactly(tempPath + "/Release");
        assertThat(promotionPlan.expectedTargetNames()).containsExactly("Release");
    }

    @Test
    void publishesPreparedItemsFromDifferentTempDirectoriesInOnePlan() throws Exception {
        String targetPath = "/adult/JAV/7.15";
        String firstTempPath = targetPath + "/adult-task-batch-01";
        String secondTempPath = targetPath + "/adult-task-batch-02";
        RecordingLibraryOrganizer organizer = new RecordingLibraryOrganizer();
        FakeAdultOpenListClient client = new FakeAdultOpenListClient(
                openListProperties,
                objectMapper,
                targetPath,
                Map.of(firstTempPath, "Release-A", secondTempPath, "Release-B"),
                List.of("adult-task-batch-01", "adult-task-batch-02")
        );
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
                new ControlledExecutor()
        );
        Class<?> itemType = Class.forName(AdultMagnetIngestService.class.getName() + "$AdultMagnetItem");
        Class<?> preparedType = Class.forName(AdultMagnetIngestService.class.getName() + "$AdultPreparedItem");
        Constructor<?> itemConstructor = itemType.getDeclaredConstructor(
                int.class,
                String.class,
                String.class,
                String.class,
                String.class
        );
        itemConstructor.setAccessible(true);
        Object firstItem = itemConstructor.newInstance(1, "magnet:?xt=urn:btih:first", "first", targetPath, firstTempPath);
        Object secondItem = itemConstructor.newInstance(2, "magnet:?xt=urn:btih:second", "second", targetPath, secondTempPath);
        Method prepare = AdultMagnetIngestService.class.getDeclaredMethod("prepareTempDirectory", String.class, itemType);
        prepare.setAccessible(true);
        Method markPrepared = itemType.getDeclaredMethod("markPrepared", preparedType);
        markPrepared.setAccessible(true);
        markPrepared.invoke(firstItem, prepare.invoke(planningService, "task-1", firstItem));
        markPrepared.invoke(secondItem, prepare.invoke(planningService, "task-1", secondItem));
        Method planPromotionBatch = AdultMagnetIngestService.class.getDeclaredMethod(
                "planPromotionBatch",
                String.class,
                String.class,
                List.class
        );
        planPromotionBatch.setAccessible(true);

        Object batch = planPromotionBatch.invoke(
                planningService,
                "task-1",
                targetPath,
                List.of(firstItem, secondItem)
        );
        Method batchPlan = batch.getClass().getDeclaredMethod("plan");
        batchPlan.setAccessible(true);
        LibraryOrganizationPlan batchPlanValue = (LibraryOrganizationPlan) batchPlan.invoke(batch);

        assertThat(organizer.plans).hasSize(2);
        assertThat(batchPlanValue.moves())
                .extracting(LibraryOrganizationPlan.MoveOperation::sourcePath)
                .containsExactlyInAnyOrder(firstTempPath + "/Release-A", secondTempPath + "/Release-B");
        assertThat(batchPlanValue.expectedTargetNames()).containsExactlyInAnyOrder("Release-A", "Release-B");
    }

    @Test
    void keepsLaterSourceWhenPreparedItemsContainTheSameTopLevelName() throws Exception {
        String targetPath = "/adult/JAV/7.15";
        String firstTempPath = targetPath + "/adult-task-batch-01";
        String secondTempPath = targetPath + "/adult-task-batch-02";
        FakeAdultOpenListClient client = new FakeAdultOpenListClient(
                openListProperties,
                objectMapper,
                targetPath,
                Map.of(firstTempPath, "Same-Release", secondTempPath, "Same-Release"),
                List.of("adult-task-batch-01", "adult-task-batch-02")
        );
        AdultMagnetIngestService planningService = new AdultMagnetIngestService(
                taskMapper,
                taskLogMapper,
                client,
                openListProperties,
                new MovieSeriesFileRenameService(),
                new RecordingLibraryOrganizer(),
                authService,
                null,
                objectMapper,
                new ControlledExecutor(),
                new ControlledExecutor()
        );
        Class<?> itemType = Class.forName(AdultMagnetIngestService.class.getName() + "$AdultMagnetItem");
        Class<?> preparedType = Class.forName(AdultMagnetIngestService.class.getName() + "$AdultPreparedItem");
        Constructor<?> itemConstructor = itemType.getDeclaredConstructor(
                int.class,
                String.class,
                String.class,
                String.class,
                String.class
        );
        itemConstructor.setAccessible(true);
        Constructor<?> preparedConstructor = preparedType.getDeclaredConstructor(
                int.class,
                int.class,
                int.class,
                List.class
        );
        preparedConstructor.setAccessible(true);
        Method markPrepared = itemType.getDeclaredMethod("markPrepared", preparedType);
        markPrepared.setAccessible(true);
        Object firstItem = itemConstructor.newInstance(1, "magnet:?xt=urn:btih:first", "first", targetPath, firstTempPath);
        Object secondItem = itemConstructor.newInstance(2, "magnet:?xt=urn:btih:second", "second", targetPath, secondTempPath);
        markPrepared.invoke(firstItem, preparedConstructor.newInstance(1, 0, 0, List.of()));
        markPrepared.invoke(secondItem, preparedConstructor.newInstance(1, 0, 0, List.of()));
        Method planPromotionBatch = AdultMagnetIngestService.class.getDeclaredMethod(
                "planPromotionBatch",
                String.class,
                String.class,
                List.class
        );
        planPromotionBatch.setAccessible(true);

        Object batch = planPromotionBatch.invoke(
                planningService,
                "task-1",
                targetPath,
                List.of(firstItem, secondItem)
        );
        Method batchPlan = batch.getClass().getDeclaredMethod("plan");
        batchPlan.setAccessible(true);
        LibraryOrganizationPlan plan = (LibraryOrganizationPlan) batchPlan.invoke(batch);

        assertThat(plan.moves())
                .extracting(LibraryOrganizationPlan.MoveOperation::sourcePath)
                .containsExactly(firstTempPath + "/Same-Release");
        assertThat(plan.deletions()).isEmpty();
        assertThat(plan.expectedTargetNames()).containsExactly("Same-Release");
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
        private final Map<String, String> releaseNameByTempPath;
        private final List<String> targetNames;

        FakeAdultOpenListClient(
                OpenListProperties properties,
                ObjectMapper objectMapper,
                String targetPath,
                String tempPath
        ) {
            this(properties, objectMapper, targetPath, Map.of(tempPath, "Release"), List.of("adult-task-test"));
        }

        FakeAdultOpenListClient(
                OpenListProperties properties,
                ObjectMapper objectMapper,
                String targetPath,
                Map<String, String> releaseNameByTempPath,
                List<String> targetNames
        ) {
            super(properties, objectMapper);
            this.targetPath = targetPath;
            this.releaseNameByTempPath = releaseNameByTempPath;
            this.targetNames = targetNames;
        }

        @Override
        public List<OpenListFileInfo> findFiles(String path, boolean refresh) {
            String releaseName = releaseNameByTempPath.get(path);
            return List.of(
                    new OpenListFileInfo("kept.mkv", HUNDRED_MIB, false, path + "/" + releaseName),
                    new OpenListFileInfo("small.mkv", HUNDRED_MIB - 1, false, path + "/" + releaseName)
            );
        }

        @Override
        public List<OpenListFileInfo> listFiles(String path, boolean refresh) {
            if (releaseNameByTempPath.containsKey(path)) {
                return List.of(new OpenListFileInfo(releaseNameByTempPath.get(path), 0L, true, path));
            }
            if (path.equals(targetPath)) {
                return targetNames.stream()
                        .map(name -> new OpenListFileInfo(name, 0L, true, targetPath))
                        .toList();
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
