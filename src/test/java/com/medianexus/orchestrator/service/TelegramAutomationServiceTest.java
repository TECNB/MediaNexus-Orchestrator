package com.medianexus.orchestrator.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.dto.telegram.TelegramAutomationContract.BackfillRequest;
import com.medianexus.orchestrator.dto.telegram.TelegramAutomationContract.RunResponse;
import com.medianexus.orchestrator.integration.clouddrive.TelegramCloudInboxMover;
import com.medianexus.orchestrator.integration.telegram.TelegramWorkerClient;
import com.medianexus.orchestrator.integration.telegram.TelegramWorkerClientException;
import com.medianexus.orchestrator.mapper.SystemSettingMapper;
import com.medianexus.orchestrator.mapper.TelegramAutomationRunMapper;
import com.medianexus.orchestrator.model.TelegramAutomationRun;
import com.medianexus.orchestrator.model.User;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

class TelegramAutomationServiceTest {

    @Test
    void forceBackfillPassesDedupBypassToWorker() throws Exception {
        AuthService authService = mock(AuthService.class);
        SystemSettingMapper settingMapper = mock(SystemSettingMapper.class);
        TelegramAutomationRunMapper runMapper = mock(TelegramAutomationRunMapper.class);
        TelegramWorkerClient workerClient = mock(TelegramWorkerClient.class);
        TelegramCloudInboxMover inboxMover = mock(TelegramCloudInboxMover.class);
        AutoSymlinkRefreshService refreshService = mock(AutoSymlinkRefreshService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        User admin = new User();
        admin.setId(7L);
        when(authService.requireAdminUser()).thenReturn(admin);
        when(settingMapper.selectSettingValue("telegram_automation_config")).thenReturn("""
                {
                  "enabled": true,
                  "target": "@PikPak_Bot",
                  "channels": [{
                    "id": "channel-1",
                    "source_id": -100123,
                    "source_title": "测试频道",
                    "enabled": true,
                    "percentile": 0.9,
                    "resource_mode": "group",
                    "min_video_duration": 300,
                    "min_views": 5000,
                    "min_forwards": 10,
                    "min_age_hours": 24
                  }]
                }
                """);
        when(runMapper.selectOne(any())).thenReturn(null);
        when(workerClient.backfill(any())).thenReturn(objectMapper.readTree("""
                {
                  "selectedResourceCount": 1,
                  "duplicateResourceCount": 0,
                  "forwardedResourceCount": 1,
                  "forwardedMessageCount": 1,
                  "selectedResources": []
                }
                """));
        when(inboxMover.drainInbox(any(), any()))
                .thenReturn(new TelegramCloudInboxMover.MoveOutcome(1, 1));
        when(refreshService.refreshAdult()).thenReturn(new AutoSymlinkRefreshService.RefreshOutcome(
                AutoSymlinkRefreshService.Status.SUBMITTED, "已提交", "task=adult"
        ));
        TelegramAutomationService service = new TelegramAutomationService(
                authService, settingMapper, runMapper, workerClient,
                Optional.of(inboxMover), refreshService, objectMapper
        );

        RunResponse run = service.requestBackfillExecution(
                "channel-1", new BackfillRequest(5, 180, 5000, "latest", true)
        );

        ArgumentCaptor<ObjectNode> body = ArgumentCaptor.forClass(ObjectNode.class);
        verify(workerClient, timeout(1000)).backfill(body.capture());
        assertEquals("BACKFILL_FORCE", run.executionMode());
        assertEquals(true, body.getValue().path("forceResend").asBoolean());
    }

    @Test
    void formalFollowDrainsVisibleInboxWithoutExpectedCountAndRefreshesAdultAutoSymlink() throws Exception {
        AuthService authService = mock(AuthService.class);
        SystemSettingMapper settingMapper = mock(SystemSettingMapper.class);
        TelegramAutomationRunMapper runMapper = mock(TelegramAutomationRunMapper.class);
        TelegramWorkerClient workerClient = mock(TelegramWorkerClient.class);
        TelegramCloudInboxMover inboxMover = mock(TelegramCloudInboxMover.class);
        AutoSymlinkRefreshService refreshService = mock(AutoSymlinkRefreshService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        User admin = new User();
        admin.setId(7L);
        when(authService.requireAdminUser()).thenReturn(admin);
        when(settingMapper.selectSettingValue("telegram_automation_config")).thenReturn("""
                {
                  "enabled": true,
                  "target": "@PikPak_Bot",
                  "channels": [{
                    "id": "channel-1",
                    "source_id": -100123,
                    "source_title": "测试频道",
                    "source_username": "test_channel",
                    "enabled": true,
                    "percentile": 0.9,
                    "resource_mode": "group",
                    "min_video_duration": 300,
                    "min_views": 5000,
                    "min_forwards": 10,
                    "min_age_hours": 24
                  }]
                }
                """);
        when(runMapper.selectOne(any())).thenReturn(null);
        when(workerClient.forwardUnread(any())).thenReturn(objectMapper.readTree("""
                {
                  "selectedResourceCount": 2,
                  "duplicateResourceCount": 0,
                  "forwardedResourceCount": 2,
                  "forwardedMessageCount": 3,
                  "selectedResources": []
                }
                """));
        when(inboxMover.drainInbox(any(), any()))
                .thenReturn(new TelegramCloudInboxMover.MoveOutcome(2, 5));
        when(refreshService.refreshAdult()).thenReturn(new AutoSymlinkRefreshService.RefreshOutcome(
                AutoSymlinkRefreshService.Status.SUBMITTED,
                "AutoSymlink 刷新任务已提交",
                "task=adult"
        ));
        AtomicReference<TelegramAutomationRun> persisted = new AtomicReference<>();
        when(runMapper.insert(any(TelegramAutomationRun.class))).thenAnswer(invocation -> {
            persisted.set(invocation.getArgument(0));
            return 1;
        });
        when(runMapper.updateById(any(TelegramAutomationRun.class))).thenAnswer(invocation -> {
            persisted.set(invocation.getArgument(0));
            return 1;
        });
        TelegramAutomationService service = new TelegramAutomationService(
                authService, settingMapper, runMapper, workerClient,
                Optional.of(inboxMover), refreshService, objectMapper
        );

        service.requestFollowExecution();
        for (int attempt = 0; attempt < 100 && !"SUCCEEDED".equals(persisted.get().getStatus()); attempt++) {
            Thread.sleep(10);
        }

        assertEquals("SUCCEEDED", persisted.get().getStatus());
        verify(inboxMover).drainInbox(any(), any());
        verify(refreshService).refreshAdult();
    }

    @Test
    void resolveSourceMapsWorkerValidationFailureToBadRequest() {
        AuthService authService = mock(AuthService.class);
        SystemSettingMapper settingMapper = mock(SystemSettingMapper.class);
        TelegramAutomationRunMapper runMapper = mock(TelegramAutomationRunMapper.class);
        TelegramWorkerClient workerClient = mock(TelegramWorkerClient.class);
        String source = "https://t.me/c/3789958298/602";
        when(workerClient.resolveSource(source)).thenThrow(new TelegramWorkerClientException(
                "无法访问来源频道，请确认当前 Telegram 账号已加入该频道", false, null
        ));
        TelegramAutomationService service = new TelegramAutomationService(
                authService, settingMapper, runMapper, workerClient,
                Optional.empty(), mock(AutoSymlinkRefreshService.class), new ObjectMapper()
        );

        BusinessException exception = assertThrows(
                BusinessException.class, () -> service.resolveSource(source)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getHttpStatus());
        assertEquals("无法访问来源频道，请确认当前 Telegram 账号已加入该频道", exception.getMessage());
    }

    @Test
    void followDryRunUsesStoredChannelRulesAndPersistsWorkerSummary() throws Exception {
        AuthService authService = mock(AuthService.class);
        SystemSettingMapper settingMapper = mock(SystemSettingMapper.class);
        TelegramAutomationRunMapper runMapper = mock(TelegramAutomationRunMapper.class);
        TelegramWorkerClient workerClient = mock(TelegramWorkerClient.class);
        ObjectMapper objectMapper = new ObjectMapper();
        User admin = new User();
        admin.setId(7L);
        when(authService.requireAdminUser()).thenReturn(admin);
        when(settingMapper.selectSettingValue("telegram_automation_config")).thenReturn("""
                {
                  "enabled": true,
                  "target": "@PikPak_Bot",
                  "channels": [{
                    "id": "channel-1",
                    "source_id": -100123,
                    "source_title": "测试频道",
                    "source_username": "test_channel",
                    "enabled": true,
                    "percentile": 0.85,
                    "resource_mode": "group",
                    "min_video_duration": 300,
                    "min_views": 5000,
                    "min_forwards": 10,
                    "min_age_hours": 24
                  }]
                }
                """);
        when(runMapper.selectOne(any())).thenReturn(null);
        when(workerClient.forwardUnread(any())).thenReturn(objectMapper.readTree("""
                {
                  "selectedResourceCount": 4,
                  "duplicateResourceCount": 1,
                  "forwardedResourceCount": 0,
                  "forwardedMessageCount": 0,
                  "threshold": 0.0125,
                  "selectedResources": []
                }
                """));
        AtomicReference<TelegramAutomationRun> persisted = new AtomicReference<>();
        when(runMapper.insert(any(TelegramAutomationRun.class))).thenAnswer(invocation -> {
            persisted.set(invocation.getArgument(0));
            return 1;
        });
        when(runMapper.updateById(any(TelegramAutomationRun.class))).thenAnswer(invocation -> {
            persisted.set(invocation.getArgument(0));
            return 1;
        });

        TelegramAutomationService service = new TelegramAutomationService(
                authService, settingMapper, runMapper, workerClient,
                Optional.of(mock(TelegramCloudInboxMover.class)),
                mock(AutoSymlinkRefreshService.class), objectMapper
        );
        RunResponse accepted = service.requestFollowDryRun();
        for (int attempt = 0; attempt < 100 && !"SUCCEEDED".equals(persisted.get().getStatus()); attempt++) {
            Thread.sleep(10);
        }

        assertEquals("FOLLOW_DRY_RUN", accepted.executionMode());
        assertEquals("SUCCEEDED", persisted.get().getStatus());
        assertEquals(4, persisted.get().getSelectedResourceCount());
        assertNotNull(persisted.get().getResultJson());
        ArgumentCaptor<ObjectNode> body = ArgumentCaptor.forClass(ObjectNode.class);
        verify(workerClient).forwardUnread(body.capture());
        assertEquals(-100123, body.getValue().path("source").asLong());
        assertEquals(0.85, body.getValue().path("percentile").asDouble());
        assertEquals(true, body.getValue().path("dryRun").asBoolean());
    }
    @Test
    void dailyRunDrainsInboxEvenWhenNoChannelsAreScheduled() throws Exception {
        AuthService auth = mock(AuthService.class);
        SystemSettingMapper settings = mock(SystemSettingMapper.class);
        TelegramAutomationRunMapper runs = mock(TelegramAutomationRunMapper.class);
        TelegramWorkerClient worker = mock(TelegramWorkerClient.class);
        TelegramCloudInboxMover mover = mock(TelegramCloudInboxMover.class);
        AutoSymlinkRefreshService refresh = mock(AutoSymlinkRefreshService.class);
        when(settings.selectSettingValue("telegram_automation_config"))
                .thenReturn("{\"enabled\":true,\"target\":\"@PikPak_Bot\",\"channels\":[]}");
        when(mover.drainInbox(any(), any())).thenReturn(new TelegramCloudInboxMover.MoveOutcome(1, 2));
        when(refresh.refreshAdult()).thenReturn(new AutoSymlinkRefreshService.RefreshOutcome(
                AutoSymlinkRefreshService.Status.SUBMITTED, "已提交", "adult"));
        AtomicReference<TelegramAutomationRun> persisted = new AtomicReference<>();
        when(runs.insert(any(TelegramAutomationRun.class))).thenAnswer(call -> {
            persisted.set(call.getArgument(0)); return 1;
        });
        TelegramAutomationService service = new TelegramAutomationService(auth, settings, runs, worker,
                Optional.of(mover), refresh, new ObjectMapper());
        service.runDaily();
        verify(refresh, timeout(1000)).refreshAdult();
        for (int i = 0; i < 100 && !"SUCCEEDED".equals(persisted.get().getStatus()); i++) Thread.sleep(10);
        assertEquals("SUCCEEDED", persisted.get().getStatus());
        assertEquals(0, persisted.get().getChannelCount());
        verify(mover).drainInbox(any(), any());
        org.mockito.Mockito.verify(worker, org.mockito.Mockito.never()).forwardUnread(any());
    }

    @Test
    void nextDailyRunRecoversCaptionFromPreviousReportWithoutResending() throws Exception {
        SystemSettingMapper settings = mock(SystemSettingMapper.class);
        TelegramAutomationRunMapper runs = mock(TelegramAutomationRunMapper.class);
        TelegramWorkerClient worker = mock(TelegramWorkerClient.class);
        TelegramCloudInboxMover mover = mock(TelegramCloudInboxMover.class);
        AutoSymlinkRefreshService refresh = mock(AutoSymlinkRefreshService.class);
        ObjectMapper json = new ObjectMapper();
        when(settings.selectSettingValue("telegram_automation_config"))
                .thenReturn("{\"enabled\":true,\"target\":\"@PikPak_Bot\",\"channels\":[]}");
        TelegramAutomationRun previous = new TelegramAutomationRun();
        previous.setResultJson("""
                {"channels":[],"media_delivery":{"pending_files":[{"key":"channel:1","original_name":"late.mp4",
                "size":100,"caption":"系列标题","saved_name":"系列标题 - late.mp4","status":"PENDING_MOVE"}]}}
                """);
        when(runs.selectOne(any())).thenReturn(null, previous);
        AtomicReference<TelegramAutomationRun> persisted = new AtomicReference<>();
        when(runs.insert(any(TelegramAutomationRun.class))).thenAnswer(call -> {
            persisted.set(call.getArgument(0)); return 1;
        });
        when(mover.drainInbox(any(), any())).thenAnswer(call -> {
            java.util.List<TelegramCloudInboxMover.ExpectedVideo> expected = call.getArgument(0);
            assertEquals("系列标题", expected.get(0).caption());
            assertEquals("系列标题 - late.mp4", expected.get(0).savedName());
            java.util.function.Consumer<TelegramCloudInboxMover.FileDelivery> record = call.getArgument(1);
            record.accept(new TelegramCloudInboxMover.FileDelivery("channel:1", "late.mp4", "系列标题 - late.mp4",
                    "/inbox/late.mp4", "/library/系列标题 - late.mp4", "MOVED", null));
            return new TelegramCloudInboxMover.MoveOutcome(1, 1);
        });
        when(refresh.refreshAdult()).thenReturn(new AutoSymlinkRefreshService.RefreshOutcome(
                AutoSymlinkRefreshService.Status.SUBMITTED, "已提交", "adult"));
        var service = new TelegramAutomationService(mock(AuthService.class), settings, runs, worker,
                Optional.of(mover), refresh, json);
        service.runDaily();
        verify(refresh, timeout(1000)).refreshAdult();
        for (int i = 0; i < 100 && !"SUCCEEDED".equals(persisted.get().getStatus()); i++) Thread.sleep(10);
        assertEquals("SUCCEEDED", persisted.get().getStatus());
        assertEquals(0, json.readTree(persisted.get().getResultJson()).path("media_delivery").path("pending_files").size());
        verify(worker, org.mockito.Mockito.never()).forwardUnread(any());
    }

}
