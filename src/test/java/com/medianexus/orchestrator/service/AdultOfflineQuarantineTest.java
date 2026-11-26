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
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AdultOfflineQuarantineTest {
    private final AdultMagnetIngestTaskMapper tasks = mock(AdultMagnetIngestTaskMapper.class);
    private final OpenListClient client = mock(OpenListClient.class);
    private final AdultMagnetIngestService service = new AdultMagnetIngestService(tasks, null, client,
            new OpenListProperties(), null, null, null, new com.fasterxml.jackson.databind.ObjectMapper(), null);

    private AdultMagnetIngestTask unresolved() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "adult"), AdultMagnetIngestTask.class);
        ReflectionTestUtils.setField(service, "tablesReady", true);
        var task = new AdultMagnetIngestTask();
        task.setId("original"); task.setStatus("PARTIAL_SUCCESS"); task.setTargetPath("/adult/old-day");
        task.setMagnetCount(3); task.setSubmittedCount(3); task.setSucceededCount(2); task.setFailedCount(1);
        task.setMagnetHashes("a".repeat(40) + "\n" + "b".repeat(40) + "\n" + "c".repeat(40));
        task.setFailedMagnetsJson("[{\"number\":\"ABC-123\",\"magnet\":\"magnet:?xt=urn:btih:" + "a".repeat(40) + "\"}]");
        task.setErrorMessage("[OFFLINE_UNRESOLVED] original timeout");
        when(tasks.selectList(any())).thenReturn(List.of(task));
        when(client.offlineDirectoryForPath(anyString())).thenAnswer(call ->
                new OpenListClient.OfflineDirectory("account", "root", call.getArgument(0)));
        return task;
    }

    @Test void terminalKnownFailureKeepsLocalClaimsAndAllowsUnrelatedDownloadAcrossRestarts() throws Exception {
        var original = unresolved();
        for (int restart = 0; restart < 2; restart++) {
            try (var scheduler = new OfflineIngestScheduler(client)) {
                ReflectionTestUtils.setField(service, "offlineScheduler", scheduler);
                service.markUnfinishedTasksInterrupted();
                assertThat(scheduler.submissionPauseReason()).isNull();
                assertThat(scheduler.alternativeReleaseAvailable("/adult/new-day", "a".repeat(40))).isFalse();
                assertThat(scheduler.alternativeReleaseAvailable("/adult/old-day/child", "d".repeat(40))).isFalse();
                assertThat(scheduler.alternativeReleaseAvailable("/adult/new-day", "d".repeat(40))).isTrue();
                var submitted = new CountDownLatch(1);
                scheduler.enqueue("/adult/new-day", "d".repeat(40), Duration.ofSeconds(1), Duration.ofSeconds(1),
                        new OfflineIngestScheduler.Download() {
                            public void prepare() { }
                            public String submit() { submitted.countDown(); return "new-upstream"; }
                            public void submitted(String id) { }
                            public void progress(com.medianexus.orchestrator.integration.openlist.OpenListOfflineTaskInfo info) { }
                            public void organize(String id) { }
                            public void failed(RuntimeException failure) { }
                            public void stage(String message) { }
                        });
                assertThat(submitted.await(2, TimeUnit.SECONDS)).isTrue();
            }
        }
        assertThat(original.getStatus()).isEqualTo("PARTIAL_SUCCESS");
        assertThat(original.getSucceededCount()).isEqualTo(2);
        assertThat(original.getFailedCount()).isEqualTo(1);
        assertThat(original.getErrorMessage()).isEqualTo("[OFFLINE_UNRESOLVED] original timeout");
        verify(client, never()).cancelOfflineTask(anyString());
        verify(client, never()).deleteOfflineTask(anyString());
        verify(client, never()).retryOfflineTask(anyString());
    }

    @Test void unknownFailureIdentityStillPausesGlobally() {
        var original = unresolved(); original.setFailedMagnetsJson("[]");
        try (var scheduler = new OfflineIngestScheduler(client)) {
            ReflectionTestUtils.setField(service, "offlineScheduler", scheduler);
            service.markUnfinishedTasksInterrupted();
            assertThat(scheduler.submissionPauseReason()).isNotBlank();
        }
    }

    @Test void incompleteOrConflictingEvidenceNeverNarrowsThePause() {
        List<java.util.function.Consumer<AdultMagnetIngestTask>> corruptions = List.of(
                task -> task.setFailedMagnetsJson("not-json"),
                task -> task.setFailedCount(2),
                task -> task.setSubmittedCount(2),
                task -> task.setSucceededCount(1),
                task -> task.setMagnetHashes("b".repeat(40)),
                task -> task.setTargetPath(null),
                task -> task.setStatus(null));
        for (var corrupt : corruptions) {
            var original = unresolved(); corrupt.accept(original);
            try (var scheduler = new OfflineIngestScheduler(client)) {
                ReflectionTestUtils.setField(service, "offlineScheduler", scheduler);
                service.markUnfinishedTasksInterrupted();
                assertThat(scheduler.submissionPauseReason()).isNotBlank();
            }
        }
    }

    @Test void activeWritesStillPauseGlobally() {
        unresolved(); when(tasks.selectCount(any())).thenReturn(1L);
        try (var scheduler = new OfflineIngestScheduler(client)) {
            ReflectionTestUtils.setField(service, "offlineScheduler", scheduler);
            service.markUnfinishedTasksInterrupted();
            assertThat(scheduler.submissionPauseReason()).isNotBlank();
        }
    }
}
