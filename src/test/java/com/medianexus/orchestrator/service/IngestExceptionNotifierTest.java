package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.config.WeComProperties;
import com.medianexus.orchestrator.integration.wecom.WeComClient;
import com.medianexus.orchestrator.mapper.IngestExceptionNoticeMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class IngestExceptionNotifierTest {
    private final WeComClient.Recipient recipient = new WeComClient.Recipient(new WeComClient.Scope("corp", 1L), "member");
    @Test void deduplicatesDurablyAndSendsWithoutAnyMediaImageDependency() {
        WeComProperties properties = new WeComProperties(); properties.setEnabled(true); properties.setFrontendBaseUrl("https://media.example");
        WeComClient client = mock(WeComClient.class); IngestExceptionNoticeMapper mapper = mock(IngestExceptionNoticeMapper.class);
        when(mapper.claim("event")).thenReturn(1, 0); when(mapper.status("event")).thenReturn("SENT");
        IngestExceptionNotifier notifier = new IngestExceptionNotifier(properties, client, mapper, mock(WeComBindings.class));
        assertEquals("SENT", notifier.send(recipient, "event", "下载超时", "请前往任务中心", "/tasks/series/task-1"));
        assertEquals("SENT", new IngestExceptionNotifier(properties, client, mapper, mock(WeComBindings.class)).send(recipient, "event", "下载超时", "请前往任务中心", "/tasks/series/task-1"));
        verify(client, times(1)).sendText(recipient, "下载超时\n请前往任务中心\nhttps://media.example/tasks/series/task-1");
    }
    @Test void acceptedMessageIsNotReportedFailedWhenSavingReceiptFails() {
        WeComProperties properties = new WeComProperties(); properties.setEnabled(true); properties.setFrontendBaseUrl("https://media.example");
        WeComClient client = mock(WeComClient.class); IngestExceptionNoticeMapper mapper = mock(IngestExceptionNoticeMapper.class);
        when(mapper.claim("event")).thenReturn(1);
        doThrow(new IllegalStateException("database")).when(mapper).update("event", "SENT");
        IngestExceptionNotifier notifier = new IngestExceptionNotifier(properties, client, mapper, mock(WeComBindings.class));
        assertEquals("SENT", notifier.send(recipient, "event", "标题", "说明", "/tasks"));
        when(mapper.status("event")).thenThrow(new IllegalStateException("database"));
        assertEquals("UNKNOWN", notifier.status("event"));
        verify(mapper,never()).update("event","FAILED");
    }

    @Test void notificationFailureReturnsIndependentStateAndDisabledDoesNotSend() {
        WeComProperties properties = new WeComProperties(); properties.setFrontendBaseUrl("https://media.example");
        WeComClient client = mock(WeComClient.class); IngestExceptionNoticeMapper mapper = mock(IngestExceptionNoticeMapper.class);
        IngestExceptionNotifier notifier = new IngestExceptionNotifier(properties, client, mapper, mock(WeComBindings.class));
        assertEquals("DISABLED", notifier.send(recipient, "event", "标题", "说明", "/tasks")); verifyNoInteractions(client, mapper);
        properties.setEnabled(true); when(mapper.claim("event")).thenReturn(1); doThrow(new IllegalStateException("upstream")).when(client).sendText(eq(recipient), anyString());
        assertEquals("FAILED", notifier.send(recipient, "event", "标题", "说明", "/tasks")); verify(mapper).update("event", "FAILED");
    }
}
