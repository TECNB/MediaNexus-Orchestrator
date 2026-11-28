package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.medianexus.orchestrator.config.OpenListProperties;
import com.medianexus.orchestrator.integration.openlist.OpenListClient;
import com.medianexus.orchestrator.mapper.AdultMagnetIngestTaskMapper;
import com.medianexus.orchestrator.model.AdultMagnetIngestTask;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AdultDownloadRestartTest {
    @Test void terminalHistoricalErrorsDoNotRebuildClaimsOrPauseDownloads() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "adult"), AdultMagnetIngestTask.class);
        var tasks = mock(AdultMagnetIngestTaskMapper.class);
        var client = mock(OpenListClient.class);
        when(client.offlineDirectoryForPath(anyString())).thenAnswer(call ->
                new OpenListClient.OfflineDirectory("account", "/", call.getArgument(0)));
        var service = new AdultMagnetIngestService(tasks, null, client,
                new OpenListProperties(), null, null, null, new com.fasterxml.jackson.databind.ObjectMapper(), null);
        ReflectionTestUtils.setField(service, "tablesReady", true);
        try (var scheduler = new OfflineIngestScheduler(client)) {
            ReflectionTestUtils.setField(service, "offlineScheduler", scheduler);
            service.markUnfinishedTasksInterrupted();
            assertThat(scheduler.submissionPauseReason()).isNull();
            assertThat(scheduler.alternativeReleaseAvailable("/adult/old-day", "original-hash")).isTrue();
            verify(tasks, never()).selectList(any());
            when(tasks.selectCount(any())).thenReturn(1L);
            service.markUnfinishedTasksInterrupted();
            assertThat(scheduler.submissionPauseReason()).isNotBlank();
            verify(client, never()).cancelOfflineTask(anyString());
        }
    }
}
