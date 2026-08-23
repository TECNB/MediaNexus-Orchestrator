package com.medianexus.orchestrator.integration.qas;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medianexus.orchestrator.config.QasProperties;
import com.medianexus.orchestrator.integration.qas.QasClientException.Reason;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * QAS v0.8.7 HTTP adapter. It owns token transport, upstream payloads and the
 * long-lived SSE response used by immediate task execution.
 */
@Component
public class QasClient {

    private static final Logger log = LoggerFactory.getLogger(QasClient.class);
    private static final int ERROR_BODY_LIMIT = 8 * 1024;
    private static final int MAX_SHARE_DEPTH = 4;
    private static final int MAX_SHARE_NODES = 500;

    private final QasProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ExecutorService streamExecutor;

    public QasClient(QasProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.streamExecutor = Executors.newCachedThreadPool(new QasStreamThreadFactory());
    }

    public QasCreatedTask createTask(QasTaskCreateCommand command) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("taskname", command.taskName());
        payload.put("shareurl", command.shareUrl());
        payload.put("savepath", command.savePath());
        payload.put("pattern", command.pattern());
        payload.put("replace", command.replace());

        HttpResponse<String> response = sendText("/api/add_task", payload);
        JsonNode root = parseResponse(response.body());
        if (response.statusCode() == 401 || isAuthenticationFailure(root)) {
            throw new QasClientException(Reason.AUTHENTICATION, upstreamMessage(root, "QAS authentication failed"));
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300 || !root.path("success").asBoolean(false)) {
            throw new QasClientException(Reason.UPSTREAM, upstreamMessage(root, "QAS task creation failed"));
        }

        JsonNode taskDocument = root.get("data");
        if (taskDocument == null || !taskDocument.isObject()) {
            throw new QasClientException(Reason.INVALID_RESPONSE, "QAS task response has no data object");
        }
        return new QasCreatedTask(command.taskName(), command.savePath(), taskDocument.deepCopy());
    }

    /**
     * Reads the complete share tree while keeping QAS' temporary stoken inside
     * this adapter. The returned domain tree never contains that credential.
     */
    public QasShareTree inspectShare(String shareUrl) {
        InspectionState state = new InspectionState(
                false,
                0
        );
        List<QasShareNode> entries = inspectDirectory(shareUrl, "", 0, state);
        return new QasShareTree(shareUrl, entries);
    }

    /**
     * Starts only the newly created task. The method waits for QAS to accept
     * the SSE request, then drains the stream in a daemon worker so the caller
     * can return without waiting for the Quark transfer to finish.
     */
    public void triggerTaskNow(QasCreatedTask task) {
        triggerTasksNow(List.of(task));
    }

    /** Starts all newly created tasks in one QAS SSE request. */
    public void triggerTasksNow(List<QasCreatedTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalArgumentException("QAS immediate execution task list cannot be empty");
        }
        ObjectNode payload = objectMapper.createObjectNode();
        ArrayNode taskList = payload.putArray("tasklist");
        tasks.forEach(task -> taskList.add(task.document()));
        HttpRequest request = jsonRequest("/run_script_now", payload).build();

        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                try (InputStream body = response.body()) {
                    String errorBody = new String(body.readNBytes(ERROR_BODY_LIMIT), StandardCharsets.UTF_8);
                    JsonNode root = parseOptionalResponse(errorBody);
                    if (response.statusCode() == 401 || isAuthenticationFailure(root)) {
                        throw new QasClientException(
                                Reason.AUTHENTICATION,
                                upstreamMessage(root, "QAS authentication failed")
                        );
                    }
                    throw new QasClientException(
                            Reason.UPSTREAM,
                            upstreamMessage(root, "QAS immediate execution request failed")
                    );
                }
            }
            String executionLabel = tasks.size() == 1 ? tasks.get(0).taskName() : tasks.size() + " tasks";
            streamExecutor.execute(() -> drainExecutionStream(executionLabel, response.body()));
        } catch (IOException exception) {
            throw new QasClientException(Reason.UPSTREAM, "QAS immediate execution request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new QasClientException(Reason.UPSTREAM, "QAS immediate execution request interrupted", exception);
        }
    }

    private List<QasShareNode> inspectDirectory(
            String shareUrl,
            String stoken,
            int depth,
            InspectionState state
    ) {
        if (depth > MAX_SHARE_DEPTH) {
            throw inspectionFailure(
                    Reason.INVALID_RESPONSE,
                    "QAS 分享目录深度超过 " + MAX_SHARE_DEPTH,
                    state
            );
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("shareurl", shareUrl);
        if (StringUtils.hasText(stoken)) {
            payload.put("stoken", stoken);
        }

        HttpResponse<String> response;
        try {
            response = sendText("/get_share_detail", payload, timeout());
        } catch (QasClientException exception) {
            boolean timedOut = exception.getCause() instanceof HttpTimeoutException
                    || (exception instanceof QasShareInspectionException inspection && inspection.isTimedOut());
            boolean complexObserved = state.complexStructureObserved
                    || (exception instanceof QasShareInspectionException inspection
                    && inspection.isComplexStructureObserved());
            throw new QasShareInspectionException(
                    exception.getReason(),
                    exception.getMessage(),
                    complexObserved,
                    timedOut,
                    exception
            );
        }
        JsonNode root;
        try {
            root = parseResponse(response.body());
        } catch (QasClientException exception) {
            throw new QasShareInspectionException(
                    exception.getReason(),
                    exception.getMessage(),
                    state.complexStructureObserved,
                    false,
                    exception
            );
        }
        if (response.statusCode() == 401 || isAuthenticationFailure(root)) {
            throw inspectionFailure(Reason.AUTHENTICATION, "QAS authentication failed", state);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300 || !root.path("success").asBoolean(false)) {
            throw inspectionFailure(
                    Reason.UPSTREAM,
                    shareDetailMessage(root, "QAS share inspection failed"),
                    state
            );
        }
        JsonNode data = root.path("data");
        JsonNode list = data.path("list");
        if (!data.isObject() || !list.isArray()) {
            throw inspectionFailure(Reason.INVALID_RESPONSE, "QAS share detail has no data.list array", state);
        }

        long directoryCount = 0;
        for (JsonNode item : list) {
            if (item.path("dir").asBoolean(false)) {
                directoryCount++;
            }
        }
        if (directoryCount > 1) {
            state.complexStructureObserved = true;
        }

        String nextStoken = data.path("stoken").asText(stoken);
        List<QasShareNode> nodes = new ArrayList<>();
        for (JsonNode item : list) {
            state.nodeCount++;
            if (state.nodeCount > MAX_SHARE_NODES) {
                throw inspectionFailure(
                        Reason.INVALID_RESPONSE,
                        "QAS 分享节点数超过 " + MAX_SHARE_NODES,
                        state
                );
            }
            String fid = item.path("fid").asText("");
            String name = item.path("file_name").asText("");
            boolean directory = item.path("dir").asBoolean(false);
            if (!StringUtils.hasText(fid) || !StringUtils.hasText(name)) {
                throw inspectionFailure(Reason.INVALID_RESPONSE, "QAS share entry is missing fid or file_name", state);
            }
            List<QasShareNode> children = directory
                    ? inspectDirectory(QasShareUrl.withDirectoryFid(shareUrl, fid), nextStoken, depth + 1, state)
                    : List.of();
            nodes.add(new QasShareNode(
                    fid,
                    name,
                    directory,
                    item.path("obj_category").asText(null),
                    item.path("size").asLong(0),
                    children
            ));
        }
        return List.copyOf(nodes);
    }

    private QasShareInspectionException inspectionFailure(
            Reason reason,
            String message,
            InspectionState state
    ) {
        return new QasShareInspectionException(reason, message, state.complexStructureObserved);
    }

    private String shareDetailMessage(JsonNode root, String fallback) {
        String error = root.path("data").path("error").asText("").trim();
        return StringUtils.hasText(error) ? error : upstreamMessage(root, fallback);
    }

    private HttpResponse<String> sendText(String path, JsonNode payload) {
        return sendText(path, payload, timeout());
    }

    private HttpResponse<String> sendText(String path, JsonNode payload, Duration requestTimeout) {
        try {
            return httpClient.send(
                    jsonRequest(path, payload, requestTimeout).build(),
                    HttpResponse.BodyHandlers.ofString()
            );
        } catch (HttpTimeoutException exception) {
            throw new QasClientException(
                    Reason.UPSTREAM,
                    "QAS request timed out",
                    exception
            );
        } catch (IOException exception) {
            throw new QasClientException(Reason.UPSTREAM, "QAS request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new QasClientException(Reason.UPSTREAM, "QAS request interrupted", exception);
        }
    }

    private HttpRequest.Builder jsonRequest(String path, JsonNode payload) {
        return jsonRequest(path, payload, timeout());
    }

    private HttpRequest.Builder jsonRequest(String path, JsonNode payload, Duration requestTimeout) {
        String body;
        try {
            body = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new QasClientException(Reason.INVALID_RESPONSE, "QAS request serialization failed", exception);
        }
        return HttpRequest.newBuilder(endpoint(path))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private URI endpoint(String path) {
        String baseUrl = clean(properties.getBaseUrl()).replaceAll("/+$", "");
        String apiToken = clean(properties.getApiToken());
        if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(apiToken)) {
            throw new QasClientException(Reason.CONFIGURATION, "QAS configuration is incomplete");
        }
        String normalizedBaseUrl = baseUrl.toLowerCase(Locale.ROOT);
        if (!normalizedBaseUrl.startsWith("http://") && !normalizedBaseUrl.startsWith("https://")) {
            throw new QasClientException(Reason.CONFIGURATION, "QAS base URL must start with http:// or https://");
        }
        try {
            return URI.create(baseUrl + path + "?token=" + URLEncoder.encode(apiToken, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException exception) {
            throw new QasClientException(Reason.CONFIGURATION, "QAS base URL is invalid", exception);
        }
    }

    private JsonNode parseResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null || !root.isObject()) {
                throw new QasClientException(Reason.INVALID_RESPONSE, "QAS returned a non-object response");
            }
            return root;
        } catch (JsonProcessingException exception) {
            throw new QasClientException(Reason.INVALID_RESPONSE, "QAS response parsing failed", exception);
        }
    }

    private JsonNode parseOptionalResponse(String body) {
        if (!StringUtils.hasText(body)) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private boolean isAuthenticationFailure(JsonNode root) {
        String message = root == null ? "" : root.path("message").asText("");
        return message.contains("未登录") || message.toLowerCase(Locale.ROOT).contains("token");
    }

    private String upstreamMessage(JsonNode root, String fallback) {
        String message = root == null ? "" : root.path("message").asText("").trim();
        return StringUtils.hasText(message) ? message : fallback;
    }

    private void drainExecutionStream(String taskName, InputStream inputStream) {
        try (InputStream stream = inputStream) {
            stream.transferTo(OutputStreamSink.INSTANCE);
            log.info("QAS immediate execution stream completed taskName={}", taskName);
        } catch (IOException exception) {
            log.warn("QAS immediate execution stream ended unexpectedly taskName={}", taskName);
        }
    }

    private Duration timeout() {
        return properties.getTimeout();
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    @PreDestroy
    public void close() {
        streamExecutor.shutdownNow();
    }

    private static final class QasStreamThreadFactory implements ThreadFactory {

        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "qas-stream-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class OutputStreamSink extends java.io.OutputStream {

        private static final OutputStreamSink INSTANCE = new OutputStreamSink();

        @Override
        public void write(int value) {
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
        }
    }

    private static final class InspectionState {

        private boolean complexStructureObserved;
        private int nodeCount;

        private InspectionState(boolean complexStructureObserved, int nodeCount) {
            this.complexStructureObserved = complexStructureObserved;
            this.nodeCount = nodeCount;
        }
    }
}
