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
import com.medianexus.orchestrator.support.IngestTargetFixtures;
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
import org.mockito.ArgumentCaptor;
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
            IngestTargetFixtures.legacy()
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

    @Test void javCleanupSharesStandalonePromotionRuleInsteadOfTrustingLargeSize() {
        String ad = "【更多高品质资源请访问 www.Butailing.com】.MKV";
        assertThat((Boolean) org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "isQualifiedVideo",
                new OpenListFileInfo(ad, 500L * 1024 * 1024, false, "/adult"))).isFalse();
        assertThat((Boolean) org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "isQualifiedVideo",
                new OpenListFileInfo("ABC-123 " + ad, 500L * 1024 * 1024, false, "/adult"))).isTrue();
    }

    @Test
    void recentTasksQueryFiltersManualSourcesBeforeLimit() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(),
                        ""
                ),
                AdultMagnetIngestTask.class
        );
        service.listTasks();

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper> query =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(taskMapper).selectList(query.capture());
        assertThat(query.getValue().getSqlSegment())
                .contains("source_type", "ORDER BY created_at DESC", "LIMIT 20");
    }

    @Test
    void rejectsMalformedEd2kBeforeCreatingTask() {
        assertThatThrownBy(() -> service.createTask(new com.medianexus.orchestrator.dto.magnet.request.AdultMagnetIngestTaskCreateRequest(
                "JAV", List.of("ed2k://|file|ABC-123-LTX.mkv|123|invalid|/"))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("ED2K");
        org.mockito.Mockito.verify(taskMapper, org.mockito.Mockito.never()).insert(any(AdultMagnetIngestTask.class));
    }

    @Test
    void createsAdultRetryAsNewLinkedAttemptAndPersistsNormalizedBatch() {
        AdultMagnetIngestTask original = new AdultMagnetIngestTask();
        original.setId("adult-1");
        original.setCategory("JAV");
        original.setDateFolder("7.15");
        original.setTargetPath("/adult/JAV/7.15");
        when(taskMapper.selectById("adult-1")).thenReturn(original);
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
        assertThat(task.getTargetPath()).isEqualTo("/adult/JAV/7.15");
        assertThat(task.getDateFolder()).isEqualTo("7.15");
    }

    @Test
    void removesNewAttemptWhenTaskSchedulingIsRejected() {
        AdultMagnetIngestTask original = new AdultMagnetIngestTask();
        original.setCategory("OTHER");
        original.setTargetPath("/adult/Other/7.15");
        original.setDateFolder("7.15");
        when(taskMapper.selectById("adult-1")).thenReturn(original);
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
                new ControlledExecutor(),
                IngestTargetFixtures.legacy()
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
    void ed2kUsesNormalCleanupWithAdditionalFilesAndDifferentDeclaredNameOrSize() throws Exception {
        OpenListClient client = mock(OpenListClient.class);
        var video = new OpenListFileInfo("ABC-123-LTX-restored.mkv", 209715199L, false, "/adult/temp");
        var attachment = new OpenListFileInfo("advertisement.txt", 100L, false, "/adult/temp");
        when(client.findFiles("/adult/temp", true)).thenReturn(List.of(video, attachment));
        when(client.listFiles("/adult/temp", true)).thenReturn(List.of(video, attachment));
        when(client.normalizePath(anyString())).thenAnswer(call -> call.getArgument(0));
        var organizer = new RecordingLibraryOrganizer();
        AdultMagnetIngestService worker = new AdultMagnetIngestService(taskMapper, taskLogMapper, client,
                openListProperties, new MovieSeriesFileRenameService(), organizer, authService, null,
                objectMapper, new ControlledExecutor(), new ControlledExecutor(), IngestTargetFixtures.legacy());
        Class<?> itemType = Class.forName(AdultMagnetIngestService.class.getName() + "$AdultMagnetItem");
        Constructor<?> constructor = itemType.getDeclaredConstructor(int.class, String.class, String.class, String.class, String.class);
        constructor.setAccessible(true);
        Object item = constructor.newInstance(1, "ed2k://|file|ABC-123-LTX.mkv|209715200|0123456789abcdef0123456789abcdef|/",
                "ed2k:0123456789abcdef0123456789abcdef", "/adult", "/adult/temp");
        Object prepared = org.springframework.test.util.ReflectionTestUtils.invokeMethod(worker, "prepareTempDirectory", "task", item);
        assertThat(prepared).isNotNull();
        assertThat(organizer.plans).hasSize(1);
        assertThat(organizer.plans.get(0).deletions()).extracting(LibraryOrganizationPlan.DeleteOperation::path)
                .containsExactly("/adult/temp/advertisement.txt");
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
                new ControlledExecutor(),
                IngestTargetFixtures.legacy()
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
                new ControlledExecutor(),
                IngestTargetFixtures.legacy()
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
                new ControlledExecutor(),
                IngestTargetFixtures.legacy()
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void sharedSchedulerPersistsEachIdBeforeBatchEndsAndProtectsSiblingTempDirectories(boolean timeoutFirst) throws Exception {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), AdultMagnetIngestTask.class);
        String target = "/adult/JAV/day";
        String firstPath = target + "/first";
        String secondPath = target + "/second";
        AdultMagnetIngestTask task = new AdultMagnetIngestTask();
        task.setId("batch"); task.setTargetPath(target); task.setOpenlistTaskIds("");
        task.setSubmittedCount(0); task.setSucceededCount(0); task.setFailedCount(0);
        task.setKeptCount(0); task.setDeletedCount(0);
        insertedTask.set(task);
        java.util.concurrent.CountDownLatch firstSaved = new java.util.concurrent.CountDownLatch(1);
        var failureResponseLost = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
        when(taskMapper.update(any())).thenAnswer(call -> {
            com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<?> update = call.getArgument(0);
            String sql = update.getSqlSet();
            var parameters = update.getParamNameValuePairs();
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\w+)=#\\{ew.paramNameValuePairs.(MPGENVAL\\d+)\\}").matcher(sql);
            while (matcher.find()) {
                Object value = parameters.get(matcher.group(2));
                String property = switch (matcher.group(1)) {
                    case "openlist_task_ids" -> "openlistTaskIds";
                    case "submitted_count" -> "submittedCount";
                    case "succeeded_count" -> "succeededCount";
                    case "failed_count" -> "failedCount";
                    case "kept_count" -> "keptCount";
                    case "deleted_count" -> "deletedCount";
                    case "error_message" -> "errorMessage";
                    case "status" -> "status";
                    default -> null;
                };
                if (property != null) org.springframework.test.util.ReflectionTestUtils.setField(task, property, value);
            if (timeoutFirst && task.getErrorMessage() != null && task.getErrorMessage().startsWith("[DOWNLOAD_TIMEOUT]")
                    && failureResponseLost.compareAndSet(false, true))
                throw new org.springframework.jdbc.CannotGetJdbcConnectionException("response lost after failure saved");
            }
            if (task.getOpenlistTaskIds().contains("one")) firstSaved.countDown();
            if (List.of("SUCCEEDED", "FAILED", "PARTIAL_SUCCESS").contains(task.getStatus())) finished.countDown();
            return 1;
        });
        OpenListClient client = mock(OpenListClient.class);
        when(client.normalizePath(anyString())).thenAnswer(call -> call.getArgument(0));
        when(client.joinPath(anyString(), anyString())).thenAnswer(call -> call.getArgument(0) + "/" + call.getArgument(1));
        when(client.offlineDirectoryForPath(anyString())).thenReturn(new OpenListClient.OfflineDirectory("account", "root", "/day"));
        String ed2k = "ed2k://|file|video.mkv|209715200|0123456789abcdef0123456789abcdef|/";
        when(client.addOfflineDownload(anyString(), anyString(), anyString())).thenAnswer(call -> {
            if (firstPath.equals(call.getArgument(0))) return "one";
            assertThat((String) call.getArgument(1)).isEqualTo(ed2k);
            assertThat((String) call.getArgument(2)).isEqualTo("PikPak");
            assertThat(firstSaved.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(task.getSubmittedCount()).isEqualTo(1);
            return "two";
        });
        when(client.offlineTaskInfo(anyString(), any(Duration.class))).thenAnswer(call -> new com.medianexus.orchestrator.integration.openlist.OpenListOfflineTaskInfo(
                call.getArgument(0), timeoutFirst && "one".equals(call.getArgument(0)) ? 1 : 2, "done", 100, 0L, null));
        when(client.discardTimedOutDownload("one", target, firstPath))
                .thenReturn("cancel=ACKNOWLEDGED; cleanup=ACKNOWLEDGED; path=" + firstPath);
        when(client.findFiles(anyString(), any(Boolean.class))).thenAnswer(call -> List.of(
                new OpenListFileInfo("video.mkv", 200L * 1024 * 1024, false, call.getArgument(0))));
        when(client.listFiles(anyString(), any(Boolean.class))).thenAnswer(call -> target.equals(call.getArgument(0))
                ? List.of() : List.of(new OpenListFileInfo("video.mkv", 200L * 1024 * 1024, false, call.getArgument(0))));
        openListProperties.setOfflineTool("PikPak");
        openListProperties.setEd2kOfflineTool("PikPak");
        openListProperties.setAdultOfflineTimeout(timeoutFirst ? Duration.ofMillis(200) : Duration.ofSeconds(3));
        openListProperties.setPollInterval(Duration.ofMillis(5));
        List<LibraryOrganizationPlan> plans = new java.util.concurrent.CopyOnWriteArrayList<>();
        LibraryOrganizer organizer = (plan, observer) -> plans.add(plan);
        AutoSymlinkRefreshService refresh = mock(AutoSymlinkRefreshService.class);
        when(refresh.refreshForPath(anyString(), anyString())).thenReturn(AutoSymlinkRefreshService.RefreshOutcome.skipped("未启用"));
        AdultMagnetIngestService worker = new AdultMagnetIngestService(taskMapper, taskLogMapper, client,
                openListProperties, new MovieSeriesFileRenameService(), organizer, authService, refresh,
                objectMapper, new ControlledExecutor(), new ControlledExecutor(), IngestTargetFixtures.legacy());
        Class<?> itemType = Class.forName(AdultMagnetIngestService.class.getName() + "$AdultMagnetItem");
        Constructor<?> constructor = itemType.getDeclaredConstructor(int.class, String.class, String.class, String.class, String.class);
        constructor.setAccessible(true);
        Object first = constructor.newInstance(1, "magnet:?xt=urn:btih:one", "a", target, firstPath);
        Object second = constructor.newInstance(2, ed2k, "ed2k:0123456789abcdef0123456789abcdef", target, secondPath);
        try (OfflineIngestScheduler scheduler = new OfflineIngestScheduler(client)) {
            org.springframework.test.util.ReflectionTestUtils.setField(worker, "offlineScheduler", scheduler);
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(worker, "runTask", "batch", List.of(first, second), "/adult");
            assertThat(finished.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(task.getOpenlistTaskIds()).contains("a=one", "ed2k:0123456789abcdef0123456789abcdef=two");
            assertThat(task.getSubmittedCount()).isEqualTo(2);
            assertThat(task.getSucceededCount()).isEqualTo(timeoutFirst ? 1 : 2);
            assertThat(task.getFailedCount()).isEqualTo(timeoutFirst ? 1 : 0);
            assertThat(task.getStatus()).isEqualTo(timeoutFirst ? "PARTIAL_SUCCESS" : "SUCCEEDED");
            assertThat(plans.stream().filter(plan -> plan.targetDirectory().equals(target)).toList())
                    .hasSize(timeoutFirst ? 1 : 2).allSatisfy(plan -> assertThat(plan.cleanupExcludedDirectories()).contains(firstPath, secondPath));
            if (timeoutFirst) {
                assertThat(task.getErrorMessage()).contains("DOWNLOAD_TIMEOUT", "cleanup=ACKNOWLEDGED");
                verify(client).discardTimedOutDownload("one", target, firstPath);
                org.mockito.Mockito.verify(client, org.mockito.Mockito.never()).discardTimedOutDownload("two", target, secondPath);
                assertThat(scheduler.alternativeReleaseAvailable(target, "a")).isTrue();
            }
        }
    }

    @Test
    void registersExactPublicationBeforeMoveAndRefreshesAfterMove() throws Exception {
        String target = "/adult/JAV/day", temp = target + "/temp";
        AdultMagnetIngestTask task = new AdultMagnetIngestTask();
        task.setId("batch"); task.setCategory("JAV"); task.setTargetPath(target);
        insertedTask.set(task);
        OpenListClient client = mock(OpenListClient.class);
        when(client.normalizePath(target)).thenReturn(target);
        when(client.listFiles(target, true)).thenReturn(List.of());
        when(client.listFiles(temp, true)).thenReturn(List.of(new OpenListFileInfo("JUQ-986-C", null, true, temp)));
        when(client.findFiles(temp, true)).thenReturn(List.of(new OpenListFileInfo("JUQ-986-C.mp4", 200_000_000L, false, temp + "/JUQ-986-C")));
        when(client.joinPath(anyString(), anyString())).thenAnswer(call -> call.getArgument(0) + "/" + call.getArgument(1));
        JavMetadataCompletion metadata = mock(JavMetadataCompletion.class);
        var registered = new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.doAnswer(call -> { registered.set(true); return null; }).when(metadata)
                .publishedFile("batch", "hash", "magnet:?dn=JUQ-986-C", null, target + "/JUQ-986-C/JUQ-986-C.mp4");
        LibraryOrganizer organizer = (plan, observer) -> assertThat(registered.get()).isTrue();
        AutoSymlinkRefreshService refresh = mock(AutoSymlinkRefreshService.class);
        when(refresh.refreshForPath("ADULT", target)).thenReturn(AutoSymlinkRefreshService.RefreshOutcome.submitted("test"));
        AdultMagnetIngestService worker = new AdultMagnetIngestService(taskMapper, taskLogMapper, client,
                openListProperties, new MovieSeriesFileRenameService(), organizer, authService, refresh,
                objectMapper, new ControlledExecutor(), new ControlledExecutor(), IngestTargetFixtures.legacy());
        var properties = new com.medianexus.orchestrator.config.JavMetadataProperties(); properties.setEnabled(true);
        org.springframework.test.util.ReflectionTestUtils.setField(worker, "javMetadataCompletion", metadata);
        org.springframework.test.util.ReflectionTestUtils.setField(worker, "javMetadataProperties", properties);
        Class<?> type = Class.forName(AdultMagnetIngestService.class.getName() + "$AdultMagnetItem");
        Class<?> prepared = Class.forName(AdultMagnetIngestService.class.getName() + "$AdultPreparedItem");
        Constructor<?> constructor = type.getDeclaredConstructor(int.class, String.class, String.class, String.class, String.class);
        constructor.setAccessible(true);
        Object item = constructor.newInstance(1, "magnet:?dn=JUQ-986-C", "hash", target, temp);
        Constructor<?> preparation = prepared.getDeclaredConstructor(int.class, int.class, int.class, List.class);
        preparation.setAccessible(true);
        Method mark = type.getDeclaredMethod("markPrepared", prepared); mark.setAccessible(true);
        mark.invoke(item, preparation.newInstance(1, 0, 0, List.of()));
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(worker, "promotePreparedItems", "batch", List.of(item));
        assertThat(registered.get()).isTrue();
        verify(refresh).refreshForPath("ADULT", target);
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
