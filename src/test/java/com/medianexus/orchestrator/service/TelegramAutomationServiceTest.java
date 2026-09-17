package com.medianexus.orchestrator.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medianexus.orchestrator.dto.telegram.TelegramAutomationContract.RunResponse;
import com.medianexus.orchestrator.integration.telegram.TelegramWorkerClient;
import com.medianexus.orchestrator.mapper.SystemSettingMapper;
import com.medianexus.orchestrator.mapper.TelegramAutomationRunMapper;
import com.medianexus.orchestrator.model.TelegramAutomationRun;
import com.medianexus.orchestrator.model.User;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TelegramAutomationServiceTest {

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
                authService, settingMapper, runMapper, workerClient, objectMapper
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
}
