package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.JavMetadataProperties;
import com.medianexus.orchestrator.integration.emby.*;
import com.medianexus.orchestrator.integration.javdb.*;
import com.medianexus.orchestrator.mapper.*;
import com.medianexus.orchestrator.mapper.JavMetadataQueueMapper.SourceRow;
import com.medianexus.orchestrator.mapper.JavMetadataQueueMapper.TargetRow;
import com.medianexus.orchestrator.service.settings.targets.IngestTargetRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalMatchers.aryEq;

class JavMetadataCompletionTest {
    private final JavMetadataProperties props = properties();
    private final JavMetadataQueueMapper queue = mock(JavMetadataQueueMapper.class);
    private final SystemSettingMapper settings = mock(SystemSettingMapper.class);
    private final AdultMagnetIngestTaskLogMapper logs = mock(AdultMagnetIngestTaskLogMapper.class);
    private final JavdbMetadataSource source = mock(JavdbMetadataSource.class);
    private final EmbyJavMetadataWriter writer = mock(EmbyJavMetadataWriter.class);
    private final EmbyClient emby = mock(EmbyClient.class);
    private final IngestTargetRegistry targets = mock(IngestTargetRegistry.class);
    private final ObjectMapper json = new ObjectMapper();
    private final JavMetadataCompletion worker = new JavMetadataCompletion(props, queue, settings, logs, source, writer, emby, targets, json);
    private static final String PATH = "/strm/JAV/11.22/JUQ-986-C/JUQ-986-C.strm";

    @AfterEach void close() { worker.close(); }

    @Test void mapsExactFileAndRejectsSiblingRoot() {
        when(targets.bindingForPath(eq("ADULT"), anyString())).thenReturn(Optional.empty());
        assertThat(worker.strmPath("/adult/JAV/11.22/JUQ-986-C/JUQ-986-C.mp4")).isEqualTo(PATH);
        assertThatThrownBy(() -> worker.strmPath("/adult-other/JAV/JUQ-986.mp4"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void manualMagnetKeepsSubtitleVersionWithoutExtraInput() {
        worker.registerDownload("task", "hash", "magnet:?xt=urn:btih:abcdef&dn=JUQ-986-C", null);
        verify(queue).insertSource(anyString(), eq("task"), eq("hash"), eq("JUQ-986"), isNull(), eq(true), eq(false));
    }

    @Test void advertisementAndConflictingMovieNeverBecomeMetadataTargets() {
        worker.publishedFile("task", "hash", "magnet:?dn=JUQ-986-C", null, "/adult/JAV/JUQ-986/广告.mp4");
        worker.publishedFile("task", "hash", "magnet:?dn=JUQ-986-C", null, "/adult/JAV/JUQ-986/MIKR-051.mp4");
        verify(queue, never()).insertTarget(anyString(), anyString(), anyString(), any());
        verifyNoInteractions(writer, emby);
    }

    @Test void preparesPersistentBytesBeforeEmbyIsPresent() throws Exception {
        SourceRow row = new SourceRow("source", "task", "hash", "JUQ-986", null, true, false,
                null, null, null, null, "NEW", 0, LocalDateTime.now());
        when(queue.dueSources()).thenReturn(List.of(row));
        when(settings.selectSettingValue(anyString())).thenReturn("private-cookie");
        var snapshot = snapshot();
        when(source.fetch("JUQ-986", null, "private-cookie")).thenReturn(snapshot);
        when(writer.preparePrimary(snapshot, true)).thenReturn(new byte[]{1});
        when(writer.prepareThumb(snapshot)).thenReturn(new byte[]{2});
        when(writer.prepareBackdrop(snapshot)).thenReturn(new byte[]{3});
        worker.prepareDue();
        verify(queue).ready(eq("source"), eq(json.writeValueAsString(snapshot)), aryEq(new byte[]{1}), aryEq(new byte[]{2}), aryEq(new byte[]{3}));
        verifyNoInteractions(emby);
    }

    @Test void noItemDefersButExactPathTriggersImmediatelyWithoutDailySync() throws Exception {
        TargetRow row = target("WAITING_EMBY");
        when(queue.dueTargets()).thenReturn(List.of(row));
        when(queue.source("source")).thenReturn(sourceRow("READY"));
        when(emby.findVideoItemsByPath(PATH)).thenReturn(List.of(new EmbyItem("wrong", "JUQ-986", "Movie", "/other.strm", null, null)));
        worker.completeDue();
        verifyNoInteractions(writer);
        when(emby.findVideoItemsByPath(PATH)).thenReturn(List.of(new EmbyItem("123", "wrong old title", "Movie", PATH, null, null)));
        worker.completeDue();
        verify(writer).writeAndVerify(eq("123"), eq(PATH), eq(snapshot()), eq(true), eq(false), any());
        verify(queue).targetState(eq("target"), eq("VERIFYING"), eq(0), eq("123"), isNull(), any(), isNull());
    }

    @Test void recreatedWorkerResumesPersistedVerification() throws Exception {
        when(queue.dueTargets()).thenReturn(List.of(target("VERIFYING")));
        when(queue.source("source")).thenReturn(sourceRow("READY"));
        when(emby.findVideoItemsByPath(PATH)).thenReturn(List.of(new EmbyItem("123", "old", "Movie", PATH, null, null)));
        try (var resumed = new CloseableWorker(new JavMetadataCompletion(props, queue, settings, logs, source, writer, emby, targets, json))) {
            resumed.worker.completeDue();
        }
        verify(queue).targetState(eq("target"), eq("COMPLETE"), eq(0), eq("123"), isNull(), any(), notNull());
        verify(writer).verify(eq("123"), eq(PATH), eq(snapshot()), eq(true), eq(false), any());
        verify(writer, never()).writeAndVerify(anyString(), anyString(), any(), anyBoolean(), anyBoolean(), any());
    }

    @Test void sourceErrorsHaveFiniteRetriesAndNeverChangeDownloadOutcome() {
        when(queue.dueSources()).thenReturn(List.of(new SourceRow("source", "task", "hash", "JUQ-986", null,
                true, false, null, null, null, null, "NEW", 0, LocalDateTime.now())));
        when(source.fetch(anyString(), isNull(), isNull())).thenThrow(new IllegalStateException("private upstream response"));
        props.setMaxAttempts(1);
        worker.prepareDue();
        verify(queue).sourceFailed(eq("source"), eq("FAILED"), eq("IllegalStateException"), any());
        verifyNoInteractions(emby);
    }

    @Test void partialArtworkSurvivesAndOnlyMissingRolesAreRetried() throws Exception {
        SourceRow row = new SourceRow("source", "task", "hash", "JUQ-986", null, true, false,
                json.writeValueAsString(snapshot()), new byte[]{1}, null, null, "NEW", 1, LocalDateTime.now());
        when(queue.dueSources()).thenReturn(List.of(row));
        when(writer.prepareThumb(snapshot())).thenReturn(new byte[]{2});
        when(writer.prepareBackdrop(snapshot())).thenThrow(new IllegalStateException("unavailable"));
        worker.prepareDue();
        verifyNoInteractions(source);
        verify(writer, never()).preparePrimary(any(), anyBoolean());
        verify(queue).saveProgress(eq("source"), anyString(), aryEq(new byte[]{1}), aryEq(new byte[]{2}), isNull());
        verify(queue).sourceFailed(eq("source"), eq("NEW"), anyString(), any());
    }

    @Test void fc2UsesMemberCredential() {
        when(queue.dueSources()).thenReturn(List.of(new SourceRow("source", "task", "hash", "FC2-PPV-1234567", null,
                false, false, null, null, null, null, "NEW", 0, LocalDateTime.now())));
        when(settings.selectSettingValue("javdb_top_cookie")).thenReturn("member");
        when(source.fetch("FC2-PPV-1234567", null, "member")).thenThrow(new IllegalStateException("not found"));
        worker.prepareDue();
        verify(source).fetch("FC2-PPV-1234567", null, "member");
        verify(settings, never()).selectSettingValue("javdb_automation_cookie");
    }

    @Test void availableMetadataIsWrittenWhileArtworkIsStillPending() {
        when(queue.dueTargets()).thenReturn(List.of(target("WAITING_EMBY")));
        when(queue.source("source")).thenReturn(sourceRow("NEW"));
        when(emby.findVideoItemsByPath(PATH)).thenReturn(List.of(new EmbyItem("123", "old", "Movie", PATH, null, null)));
        worker.completeDue();
        verify(writer).writeMetadataAndVerify("123", PATH, snapshot(), true, false);
        verify(queue).targetState(eq("target"), eq("METADATA_WRITTEN"), eq(0), eq("123"), isNull(), any(), isNull());
        verify(writer, never()).writeAndVerify(anyString(), anyString(), any(), anyBoolean(), anyBoolean(), any());
    }

    private SourceRow sourceRow(String state) {
        try { return new SourceRow("source", "task", "hash", "JUQ-986", null, true, false,
                json.writeValueAsString(snapshot()), new byte[]{1}, new byte[]{2}, new byte[]{3}, state, 0, LocalDateTime.now()); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private TargetRow target(String state) { return new TargetRow("target", "source", PATH, state, 0, null, LocalDateTime.now().plusMinutes(30)); }
    private JavdbMetadataSnapshot snapshot() { return new JavdbMetadataSnapshot("JUQ-986", "abc", "https://javdb.com/v/abc", "title", "https://c.jdbstatic.com/a.jpg", "2025-01-01", List.of("actor"), List.of(), List.of(), List.of(), 8.0); }
    private static JavMetadataProperties properties() {
        var p = new JavMetadataProperties(); p.setEnabled(true); p.setLegacyIngestRoot("/adult"); p.setLegacyStrmRoot("/strm");
        p.setPollInterval(Duration.ofSeconds(5)); p.setCompletionTimeout(Duration.ofMinutes(30)); p.setMaxAttempts(4); return p;
    }
    private record CloseableWorker(JavMetadataCompletion worker) implements AutoCloseable { public void close() { worker.close(); } }
}
