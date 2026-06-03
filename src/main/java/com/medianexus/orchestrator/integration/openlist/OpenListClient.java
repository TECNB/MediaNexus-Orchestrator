package com.medianexus.orchestrator.integration.openlist;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.OpenListProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * OpenList API 客户端。
 *
 * 本客户端只暴露整季 magnet 导入所需的完整操作：离线下载任务、文件枚举、
 * 批量重命名、移动、删除和目录创建。所有路径在请求前都会规范化为 OpenList
 * 使用的绝对路径格式。
 */
@Component
public class OpenListClient {

    private final OpenListProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenListClient(OpenListProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout())
                .build();
    }

    /**
     * 创建 OpenList 离线下载任务并返回上游任务 id。
     */
    public String addOfflineDownload(String path, String magnet) {
        JsonNode data = post("fs/add_offline_download", Map.of(
                "path", normalizePath(path),
                "urls", List.of(magnet),
                "tool", offlineTool(),
                "delete_policy", deletePolicy()
        ));

        JsonNode tasks = data.path("tasks");
        if (tasks.isArray() && tasks.size() > 0) {
            String id = tasks.get(0).path("id").asText("");
            if (StringUtils.hasText(id)) {
                return id;
            }
        }
        throw new OpenListClientException("OpenList offline task id missing");
    }

    /**
     * 查询 OpenList 离线下载任务状态。
     *
     * state 的业务解释留给任务编排层处理，本方法只负责保留上游原始状态码和错误文本。
     */
    public OpenListOfflineTaskInfo offlineTaskInfo(String taskId) {
        JsonNode data = post("task/offline_download/info?tid=" + encode(taskId), Map.of());
        return new OpenListOfflineTaskInfo(
                data.path("id").asText(taskId),
                data.path("state").isInt() ? data.path("state").asInt() : null,
                data.path("error").asText("")
        );
    }

    /**
     * 触发 OpenList 对离线下载任务执行重试。
     */
    public void retryOfflineTask(String taskId) {
        postForm("task/offline_download/retry", "tid=" + encode(taskId));
    }

    /**
     * 删除 OpenList 离线下载任务记录；不删除已经保存到文件系统的内容。
     */
    public void deleteOfflineTask(String taskId) {
        post("task/offline_download/delete_some", List.of(taskId));
    }

    /**
     * 列出单层目录内容，返回结果按文件大小倒序排序，便于优先处理主体视频文件。
     */
    public List<OpenListFileInfo> listFiles(String path) {
        JsonNode data = post("fs/list", Map.of(
                "path", normalizePath(path),
                "page", 1,
                "per_page", 0,
                "refresh", false
        ));
        JsonNode content = data.path("content");
        if (!content.isArray()) {
            return List.of();
        }

        List<OpenListFileInfo> files = new ArrayList<>();
        for (JsonNode item : content) {
            files.add(new OpenListFileInfo(
                    item.path("name").asText(""),
                    item.path("size").isNumber() ? item.path("size").asLong() : null,
                    item.path("is_dir").asBoolean(item.path("isDir").asBoolean(false)),
                    normalizePath(path)
            ));
        }
        files.sort(Comparator.comparing(file -> Long.MAX_VALUE - (file.size() == null ? 0L : file.size())));
        return files;
    }

    /**
     * 递归查找目录下所有文件，不返回目录自身。
     */
    public List<OpenListFileInfo> findFiles(String path) {
        List<OpenListFileInfo> children = listFiles(path);
        List<OpenListFileInfo> files = new ArrayList<>();
        for (OpenListFileInfo child : children) {
            if (Boolean.TRUE.equals(child.isDir())) {
                files.addAll(findFiles(joinPath(path, child.name())));
            } else {
                files.add(child);
            }
        }
        files.sort(Comparator.comparing(file -> Long.MAX_VALUE - (file.size() == null ? 0L : file.size())));
        return files;
    }

    /**
     * 创建目录；OpenList 返回“已存在”时视为成功，保证路径准备操作幂等。
     */
    public void mkdir(String path) {
        JsonNode root = postRoot("fs/mkdir", Map.of("path", normalizePath(path)));
        if (!isSuccess(root) && !message(root).contains("exist") && !message(root).contains("已存在")) {
            throw new OpenListClientException("OpenList mkdir failed: " + message(root));
        }
    }

    /**
     * 将同一来源目录下的一组文件移动到目标目录。
     */
    public void move(String srcDir, String dstDir, List<String> names) {
        if (names.isEmpty()) {
            return;
        }
        post("fs/move", Map.of(
                "src_dir", normalizePath(srcDir),
                "dst_dir", normalizePath(dstDir),
                "names", names
        ));
    }

    /**
     * 在同一目录内批量重命名文件。
     */
    public void batchRename(String srcDir, Map<String, String> renameMap) {
        if (renameMap.isEmpty()) {
            return;
        }
        List<Map<String, String>> renameObjects = renameMap.entrySet().stream()
                .map(entry -> Map.of("src_name", entry.getKey(), "new_name", entry.getValue()))
                .toList();
        post("fs/batch_rename", Map.of(
                "src_dir", normalizePath(srcDir),
                "rename_objects", renameObjects
        ));
    }

    /**
     * 删除同一目录下的一组文件或空目录。
     */
    public void remove(String dir, List<String> names) {
        if (names.isEmpty()) {
            return;
        }
        post("fs/remove", Map.of(
                "dir", normalizePath(dir),
                "names", names
        ));
    }

    /**
     * 拼接 OpenList 路径片段，并保持结果为规范化绝对路径。
     */
    public String joinPath(String base, String segment) {
        String normalizedBase = normalizePath(base);
        String cleanSegment = segment == null ? "" : segment.replace("\\", "/");
        while (cleanSegment.startsWith("/")) {
            cleanSegment = cleanSegment.substring(1);
        }
        return normalizedBase.endsWith("/") ? normalizedBase + cleanSegment : normalizedBase + "/" + cleanSegment;
    }

    /**
     * 规范化 OpenList 路径：去除多余斜杠、补齐开头斜杠，并移除非根路径末尾斜杠。
     */
    public String normalizePath(String path) {
        String normalized = StringUtils.hasText(path) ? path.trim().replace("\\", "/") : "/";
        normalized = normalized.replaceAll("/{2,}", "/");
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private JsonNode post(String action, Object body) {
        JsonNode root = postRoot(action, body);
        if (!isSuccess(root)) {
            throw new OpenListClientException("OpenList returned non-success payload");
        }
        JsonNode data = root.get("data");
        return data == null || data.isNull() ? objectMapper.createObjectNode() : data;
    }

    private JsonNode postRoot(String action, Object body) {
        validateConfiguration();
        HttpRequest request = requestBuilder(action)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(body)))
                .build();
        return send(request, action);
    }

    private JsonNode postForm(String action, String body) {
        validateConfiguration();
        HttpRequest request = requestBuilder(action)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        JsonNode root = send(request, action);
        if (!isSuccess(root)) {
            throw new OpenListClientException("OpenList returned non-success payload");
        }
        return root.path("data");
    }

    private HttpRequest.Builder requestBuilder(String action) {
        String baseUrl = cleanConfigValue(properties.getBaseUrl()).replaceAll("/+$", "");
        String normalizedAction = action.startsWith("/") ? action.substring(1) : action;
        return HttpRequest.newBuilder(URI.create(baseUrl + "/api/" + normalizedAction))
                .timeout(timeout())
                .header("Authorization", cleanConfigValue(properties.getAuthorization()));
    }

    private JsonNode send(HttpRequest request, String action) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new OpenListClientException("OpenList returned non-success status for " + action);
            }
            return objectMapper.readTree(response.body());
        } catch (IOException exception) {
            throw new OpenListClientException("OpenList request failed for " + action, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new OpenListClientException("OpenList request interrupted for " + action, exception);
        }
    }

    private String writeJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException exception) {
            throw new OpenListClientException("OpenList request serialization failed", exception);
        }
    }

    private boolean isSuccess(JsonNode root) {
        return root != null && root.path("code").asInt(-1) == 200;
    }

    private String message(JsonNode root) {
        return root == null ? "" : root.path("message").asText("");
    }

    private void validateConfiguration() {
        if (!StringUtils.hasText(cleanConfigValue(properties.getBaseUrl()))
                || !StringUtils.hasText(cleanConfigValue(properties.getAuthorization()))) {
            throw new OpenListClientException("OpenList configuration is incomplete");
        }
    }

    private String offlineTool() {
        String value = cleanConfigValue(properties.getOfflineTool());
        return StringUtils.hasText(value) ? value : "PikPak";
    }

    private String deletePolicy() {
        String value = cleanConfigValue(properties.getDeletePolicy());
        return StringUtils.hasText(value) ? value : "delete_on_upload_succeed";
    }

    private Duration timeout() {
        Duration timeout = properties.getTimeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return Duration.ofSeconds(30);
        }
        return timeout;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String cleanConfigValue(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.trim();
        if (cleaned.length() >= 2
                && ((cleaned.startsWith("'") && cleaned.endsWith("'"))
                || (cleaned.startsWith("\"") && cleaned.endsWith("\"")))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        return cleaned;
    }
}
