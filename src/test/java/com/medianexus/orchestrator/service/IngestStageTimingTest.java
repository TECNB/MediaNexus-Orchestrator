package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class IngestStageTimingTest {
    @Test
    void recordsAbsoluteBoundariesAndCarriesTargetAcrossParallelWorkersWithoutLeaking() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(IngestStageTiming.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start(); logger.addAppender(events);
        var pool = Executors.newSingleThreadExecutor();
        try {
            try (var binding = IngestStageTiming.target("operation-1", 123L)) {
                var context = IngestStageTiming.context();
                pool.submit(() -> {
                    try (var worker = IngestStageTiming.bind(context);
                         var span = IngestStageTiming.start("search_query", "PanSou", "片名")) {
                        span.finish("SUCCEEDED", "raw=2");
                    }
                }).get();
            }
            pool.submit(() -> {
                try (var span = IngestStageTiming.start("search_query", "PanSou", "unrelated")) {
                    span.finish("SUCCEEDED", "raw=0");
                }
            }).get();
            assertThat(events.list).hasSize(2);
            ObjectMapper json = new ObjectMapper();
            var start = json.readTree(events.list.get(0).getFormattedMessage().substring("INGEST_TIMING ".length()));
            var end = json.readTree(events.list.get(1).getFormattedMessage().substring("INGEST_TIMING ".length()));
            assertThat(start.path("operationId").asText()).isEqualTo("operation-1");
            assertThat(end.path("subjectId").asLong()).isEqualTo(123L);
            assertThat(start.path("event").asText()).isEqualTo("START");
            assertThat(end.path("event").asText()).isEqualTo("END");
            assertThat(end.path("spanId").asText()).isEqualTo(start.path("spanId").asText());
            assertThat(end.path("startedAt").asText()).isEqualTo(start.path("startedAt").asText());
            assertThat(end.path("endedAt").asText()).isNotEmpty();
            assertThat(end.path("durationMs").asLong()).isGreaterThanOrEqualTo(0);
            assertThat(end.path("precision").asText()).isEqualTo("SERVER_EVENT_MS");
            assertThat(end.path("result").asText()).isEqualTo("SUCCEEDED");
        } finally {
            pool.shutdownNow(); logger.detachAppender(events); events.stop();
        }
    }
    @Test
    void unfinishedSpanRemainsUnknownAndTaskEventsKeepTheirOwnIdentity() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(IngestStageTiming.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start(); logger.addAppender(events);
        try {
            try (var binding = IngestStageTiming.task("MOVIE", "task-1");
                 var span = IngestStageTiming.start("organize", "library", null)) {
                // Missing completion evidence must not become a successful zero-duration stage.
            }
            var end = new ObjectMapper().readTree(events.list.get(1).getFormattedMessage().substring("INGEST_TIMING ".length()));
            assertThat(end.path("result").asText()).isEqualTo("UNKNOWN");
            assertThat(end.path("taskId").asText()).isEqualTo("task-1");
            assertThat(end.path("taskType").asText()).isEqualTo("MOVIE");
            assertThat(end.path("operationId").isNull()).isTrue();
            assertThat(IngestStageTiming.context()).isNull();
        } finally { logger.detachAppender(events); events.stop(); }
    }
    @Test
    void nestedEvidenceRequestsRetainTheirParentCandidateSpan() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(IngestStageTiming.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start(); logger.addAppender(events);
        try {
            try (var binding = IngestStageTiming.target("op", 1);
                 var parent = IngestStageTiming.start("evidence", "site", "candidate");
                 var children = parent.children();
                 var request = IngestStageTiming.start("evidence_request", "metadata", null)) {
                request.finish("FAILED", "AUTHENTICATION"); parent.finish("FAILED", "AUTHENTICATION");
            }
            ObjectMapper json = new ObjectMapper();
            var parent = json.readTree(events.list.get(0).getFormattedMessage().substring("INGEST_TIMING ".length()));
            var child = json.readTree(events.list.get(1).getFormattedMessage().substring("INGEST_TIMING ".length()));
            assertThat(child.path("parentSpanId").asText()).isEqualTo(parent.path("spanId").asText());
        } finally { logger.detachAppender(events); events.stop(); }
    }
}
