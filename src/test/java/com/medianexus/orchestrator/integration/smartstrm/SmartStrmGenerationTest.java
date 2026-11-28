package com.medianexus.orchestrator.integration.smartstrm;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medianexus.orchestrator.config.AutoSymlinkProperties;
import com.medianexus.orchestrator.config.SmartStrmProperties;
import com.medianexus.orchestrator.integration.autosymlink.AutoSymlinkClient;
import com.medianexus.orchestrator.integration.autosymlink.AutoSymlinkClientException;
import com.medianexus.orchestrator.integration.emby.EmbyClient;
import com.medianexus.orchestrator.integration.onboarding.AutoSymlinkAccountClient.TaskView;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class SmartStrmGenerationTest {
    @TempDir Path temporary;
    final ObjectMapper json = new ObjectMapper();
    final String uuid = "b3da792f-c45e-427c-852b-3c18b0cf627a";

    class Fixture implements AutoCloseable {
        final Path root;
        final Path output;
        final SmartStrmProperties p = new SmartStrmProperties();
        final SmartStrmClient client = mock(SmartStrmClient.class);
        final SmartStrmOutputs outputs = mock(SmartStrmOutputs.class);
        final SmartStrmAccountClient accounts = mock(SmartStrmAccountClient.class);
        final EmbyClient emby = mock(EmbyClient.class);
        final ObjectNode form;
        final SmartStrmGeneration generation;
        final AtomicInteger runs = new AtomicInteger();
        Fixture() throws Exception {
            root = temporary.toRealPath(); output = Files.createDirectories(root.resolve("Movies"));
            p.setPermittedOutputRoot(root.toString()); p.setCompletionTimeout(Duration.ofSeconds(2)); p.setPollInterval(Duration.ofMillis(20));
            form = json.createObjectNode().put("media_dir", "/mount/Movies").put("symlink_dir", output.toString())
                    .put("symlink_mode", "strm").put("strm_mode", "local").put("symlink_creator", true)
                    .put("metadata_copyer", false).put("symlink_ext", ".mkv,.mp4").put("metadata_ext", ".srt,.ass,.sup")
                    .put("symlink_size", "0").put("sync_enabled", true).put("sync_scheduled", true);
            when(accounts.readConfiguration(uuid)).thenAnswer(call -> form.deepCopy());
            var mapping = new SmartStrmAccountClient(client, outputs, json);
            when(accounts.taskConfiguration(eq(uuid), any())).thenAnswer(call -> mapping.taskConfiguration(uuid, call.getArgument(1)));
            when(outputs.verify(uuid, output.toString())).thenReturn(output);
            when(client.isEnabled()).thenReturn(true);
            when(client.tasks()).thenAnswer(call -> tasks(false));
            doAnswer(call -> { runs.incrementAndGet(); return null; }).when(client).run(any(ObjectNode.class));
            when(client.taskLog(uuid)).thenAnswer(call -> "任务完成".repeat(runs.get()));
            generation = new SmartStrmGeneration(client, accounts, outputs, p, emby);
        }
        ObjectNode request() { return json.createObjectNode().put("media_dir", "/mount/Movies"); }
        ObjectNode tasks(boolean running) { var tasks = json.createObjectNode(); tasks.putObject(uuid).put("running", running); return tasks; }
        @Override public void close() { generation.close(); }
    }

    @Test void submitsLocalSubdirectoryOnceAndNotifiesExistingAndNewOriginalPathsAfterUnknownWrite() throws Exception {
        try (var f = new Fixture()) {
            Path selected = Files.createDirectories(f.output.resolve("Film (2026)"));
            Path old = Files.writeString(selected.resolve("old.strm"), "/mount/old.mkv");
            Files.writeString(f.output.resolve("outside.strm"), "/mount/outside.mkv");
            var legacyProperties = new AutoSymlinkProperties(); legacyProperties.setDisabled(true); legacyProperties.setTimeout(Duration.ofSeconds(1));
            var legacy = new AutoSymlinkClient(legacyProperties, json); ReflectionTestUtils.setField(legacy, "smartStrm", f.generation);
            doAnswer(call -> {
                f.runs.incrementAndGet();
                Files.writeString(selected.resolve("Film (2026) Extended.strm"), "/mount/Film.mkv");
                throw new AutoSymlinkClientException("unknown run response");
            }).when(f.client).run(any(ObjectNode.class));
            assertThatThrownBy(() -> legacy.submitSyncTask(uuid, f.request().put("media_dir", "/mount/Movies/Film (2026)")))
                    .hasMessage("unknown run response");
            verify(f.emby, timeout(1000)).notifyMediaCreated(List.of(selected.resolve("Film (2026) Extended.strm").toString(), old.toString()));
            var task = ArgumentCaptor.forClass(ObjectNode.class); verify(f.client, times(1)).run(task.capture());
            assertThat(task.getValue().path("storage_path").asText()).isEqualTo("/Film (2026)");
            assertThat(task.getValue().path("incremental").asBoolean()).isTrue();
            assertThatThrownBy(() -> f.generation.submit(uuid, f.request().put("media_dir", "/mount/Movies/../Other"))).hasMessageContaining("相对跳转");
            assertThatThrownBy(() -> f.generation.submit(uuid, f.request().put("media_dir", "/mount/Movies-other"))).hasMessageContaining("超出");
            verify(f.client, times(1)).run(any(ObjectNode.class));
        }
    }

    @Test void idempotentAndSubtitleOnlyRunsNotifyExistingMediaAndFinish() throws Exception {
        try (var f = new Fixture()) {
            Path existing = Files.writeString(f.output.resolve("old.strm"), "/mount/old.mkv");
            Files.setLastModifiedTime(existing, java.nio.file.attribute.FileTime.fromMillis(1));
            f.generation.submit(uuid, f.request().put("metadata_copyer", true));
            verify(f.emby, timeout(1000)).notifyMediaCreated(List.of(existing.toString()));
            verify(f.client, after(100).times(2)).tasks();
            verify(f.emby, times(1)).notifyMediaCreated(any());
            var task = ArgumentCaptor.forClass(ObjectNode.class); verify(f.client).run(task.capture());
            assertThat(task.getValue().path("plugins").path("scan_filter").path("copy_ext").asText()).isEqualTo("srt,ass,sup");
            assertThat(f.form.path("metadata_copyer").asBoolean()).isFalse();
            verify(f.client, never()).saveTask(anyString(), any());
        }
    }

    @Test void idleWithOnlyHistoricalLogDoesNotNotifyQueuedWork() throws Exception {
        try (var f = new Fixture()) {
            Files.writeString(f.output.resolve("existing.strm"), "/mount/existing.mkv");
            when(f.client.taskLog(uuid)).thenReturn("11-28 01:00:00 任务完成");
            f.generation.submit(uuid, f.request());
            verify(f.client, timeout(1000).atLeast(4)).tasks();
            verifyNoInteractions(f.emby);
        }
    }

    @Test void doesNotFinishWhileAcceptedTaskIsStillQueued() throws Exception {
        try (var f = new Fixture()) {
            Path strm = Files.writeString(f.output.resolve("queued.strm"), "/mount/queued.mkv");
            AtomicInteger queuedPolls = new AtomicInteger();
            when(f.client.taskLog(uuid)).thenAnswer(call -> queuedPolls.get() >= 6 ? "旧任务完成\n新任务完成\n" : "旧任务完成\n");
            when(f.client.tasks()).thenAnswer(call -> {
                int poll = queuedPolls.incrementAndGet();
                if (poll < 5) { verifyNoInteractions(f.emby); return f.tasks(false); }
                if (poll == 5) return f.tasks(true);
                return f.tasks(false);
            });
            f.generation.submit(uuid, f.request());
            verify(f.emby, timeout(1000)).notifyMediaCreated(List.of(strm.toString()));
            assertThat(queuedPolls.get()).isGreaterThanOrEqualTo(7);
        }
    }

    @Test void waitsThroughInitialIdleAndRunningBeforeCreatedAndDeletedNotifications() throws Exception {
        try (var f = new Fixture()) {
            Path removed = Files.writeString(f.output.resolve("removed.strm"), "/mount/removed.mkv");
            Path kept = Files.writeString(f.output.resolve("kept.strm"), "/mount/kept.mkv");
            AtomicInteger polls = new AtomicInteger();
            when(f.client.tasks()).thenAnswer(call -> {
                int poll = polls.incrementAndGet();
                if (poll == 1) return f.tasks(false);
                if (poll == 2) { verifyNoInteractions(f.emby); Files.delete(removed); return f.tasks(true); }
                return f.tasks(false);
            });
            f.generation.submit(uuid, f.request());
            verify(f.emby, timeout(1000)).notifyMediaDeleted(List.of(removed.toString()));
            verify(f.emby).notifyMediaCreated(List.of(kept.toString()));
            assertThat(polls.get()).isEqualTo(4);
        }
    }
    @Test void emptyRunsFinishAndOverlappingSubmissionsShareOneObserver() throws Exception {
        try (var f = new Fixture()) {
            f.generation.submit(uuid, f.request());
            f.generation.submit(uuid, f.request());
            verify(f.client, timeout(1000).times(2)).tasks();
            verify(f.client, times(2)).run(any(ObjectNode.class));
            verify(f.client, after(100).times(2)).tasks();
            verifyNoInteractions(f.emby);
        }
    }

    @Test void chunksNotificationsAndNeverReplaysUnknownEmbyResponse() throws Exception {
        try (var f = new Fixture()) {
            for (int i = 0; i < 405; i++) Files.writeString(f.output.resolve(i + ".strm"), "/mount/" + i + ".mkv");
            doThrow(new IllegalStateException("unknown")).when(f.emby).notifyMediaCreated(any());
            f.generation.submit(uuid, f.request());
            verify(f.emby, timeout(1000).times(3)).notifyMediaCreated(any());
            verify(f.emby, after(100).times(3)).notifyMediaCreated(any());
            var chunks = ArgumentCaptor.forClass(List.class); verify(f.emby, times(3)).notifyMediaCreated(chunks.capture());
            assertThat(chunks.getAllValues()).extracting(List::size).containsExactly(200, 200, 5);
            verify(f.client, times(2)).tasks();
        }
    }

    @Test void overlappingScopesNotifyEachExistingMediaOnce() throws Exception {
        try (var f = new Fixture()) {
            Path film = Files.createDirectories(f.output.resolve("Film"));
            Path strm = Files.writeString(film.resolve("film.strm"), "/mount/film.mkv");
            f.generation.submit(uuid, f.request());
            f.generation.submit(uuid, f.request().put("media_dir", "/mount/Movies/Film"));
            verify(f.emby, timeout(1000)).notifyMediaCreated(List.of(strm.toString()));
            verify(f.emby, after(100).times(1)).notifyMediaCreated(any());
            verify(f.client, times(2)).run(any(ObjectNode.class));
            verify(f.client, times(2)).tasks();
        }
    }

    @Test void scheduledSyncOnlyRunsEligibleMigratedTasksWithCleanupAndPreservesAssets() throws Exception {
        try (var f = new Fixture()) {
            String disabled = "165ce086-ea8f-438e-83ae-a7b5e2d34d30";
            when(f.accounts.migratedTaskIds()).thenReturn(List.of(disabled, uuid));
            when(f.accounts.readConfiguration(disabled)).thenThrow(new AutoSymlinkClientException("配置漂移"));
            Path removed = Files.writeString(f.output.resolve("stale.strm"), "/mount/stale.mkv");
            Path asset = Files.writeString(f.output.resolve("poster.jpg"), "local asset");
            doAnswer(call -> { f.runs.incrementAndGet(); Files.delete(removed); return null; }).when(f.client).run(any(ObjectNode.class));
            f.generation.synchronizeAll();
            var task = ArgumentCaptor.forClass(ObjectNode.class); verify(f.client).run(task.capture());
            assertThat(task.getValue().path("name").asText()).isEqualTo(uuid);
            assertThat(task.getValue().path("storage_path").asText()).isEqualTo("/");
            assertThat(task.getValue().path("incremental").asBoolean()).isFalse();
            assertThat(task.getValue().path("keep_local_asset").asBoolean()).isTrue();
            verify(f.client, timeout(1000).times(2)).tasks();
            verify(f.emby, timeout(1000)).notifyMediaDeleted(List.of(removed.toString()));
            verify(f.emby, never()).notifyMediaCreated(any());
            assertThat(Files.readString(asset)).isEqualTo("local asset");
            when(f.client.isEnabled()).thenReturn(false); clearInvocations(f.accounts);
            f.generation.synchronizeAll(); verifyNoInteractions(f.accounts);
            var schedule = SmartStrmGeneration.class.getMethod("synchronizeAll").getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
            assertThat(schedule.cron()).isEqualTo("${medianexus.smartstrm.sync-cron}");
            assertThat(schedule.zone()).isEqualTo("${medianexus.smartstrm.sync-zone}");
        }
    }
    @Test void allowsAccountAliasInsidePermittedRootAndNotifiesOriginalPath() throws Exception {
        try (var f = new Fixture()) {
            Path alias = Files.createSymbolicLink(f.root.resolve("account-uuid"), f.output);
            f.form.put("symlink_dir", alias.toString());
            when(f.outputs.verify(uuid, alias.toString())).thenReturn(alias);
            doAnswer(call -> {
                f.runs.incrementAndGet();
                Files.createDirectories(f.output.resolve("Film"));
                Files.writeString(f.output.resolve("Film/film.strm"), "/mount/Movies/Film/film.mp4");
                return null;
            }).when(f.client).run(any(ObjectNode.class));
            f.generation.submit(uuid, f.request().put("media_dir", "/mount/Movies/Film"));
            verify(f.emby, timeout(1000)).notifyMediaCreated(List.of(alias.resolve("Film/film.strm").toString()));
            verify(f.client, times(1)).run(any(ObjectNode.class));
        }
    }
}
