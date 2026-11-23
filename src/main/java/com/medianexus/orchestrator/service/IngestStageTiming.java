package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 仅观测榜单真实调用边界；上下文须显式传给线程池，不传播认证或下载引用。 */
final class IngestStageTiming {
    private static final Logger log = LoggerFactory.getLogger(IngestStageTiming.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    record Context(String operationId, Long subjectId, String taskType, String taskId, String parentSpanId) {}

    static Binding target(String operationId, long subjectId) { return bind(new Context(operationId, subjectId, null, null, null)); }
    static Binding task(String taskType, String taskId) { return bind(new Context(null, null, taskType, taskId, null)); }
    static Context context() { return CURRENT.get(); }
    static Binding bind(Context context) { return new Binding(context); }
    static Span start(String stage, String source, String query) { return new Span(stage, source, query); }

    static final class Binding implements AutoCloseable {
        private final Context previous = CURRENT.get();
        private Binding(Context context) {
            if (context == null) CURRENT.remove(); else CURRENT.set(context);
        }
        @Override public void close() {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }

    static final class Span implements AutoCloseable {
        private final Context context = CURRENT.get();
        private final String id = UUID.randomUUID().toString();
        private final String stage;
        private final String source;
        private final String query;
        private final Instant startedAt = Instant.now();
        private final long startedNanos = System.nanoTime();
        private boolean finished;

        private Span(String stage, String source, String query) {
            this.stage = stage; this.source = source; this.query = query;
            emit("START", "PENDING", null);
        }
        Binding children() {
            return bind(context == null ? null : new Context(context.operationId(), context.subjectId(),
                    context.taskType(), context.taskId(), id));
        }
        void finish(String result, String evidence) {
            if (finished) return;
            finished = true;
            emit("END", result, evidence);
        }
        private void emit(String event, String result, String evidence) {
            if (context == null) return;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("schemaVersion", 1); row.put("operationId", context.operationId()); row.put("subjectId", context.subjectId());
            row.put("taskType", context.taskType()); row.put("taskId", context.taskId());
            row.put("parentSpanId", context.parentSpanId());
            row.put("spanId", id); row.put("stage", stage); row.put("source", source); row.put("query", query);
            row.put("event", event); row.put("startedAt", startedAt.toString());
            row.put("endedAt", finished ? Instant.now().toString() : null);
            row.put("durationMs", finished ? TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos) : null);
            row.put("precision", "SERVER_EVENT_MS"); row.put("result", result); row.put("evidence", evidence);
            try { log.info("INGEST_TIMING {}", JSON.writeValueAsString(row)); }
            catch (JsonProcessingException failure) { log.warn("阶段计时序列化失败 stage={}", stage); }
        }
        @Override public void close() { finish("UNKNOWN", "调用未提供完成证据；不推断成功或失败"); }
    }
}
