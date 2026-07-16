package com.medianexus.orchestrator.integration.openlist;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.OpenListProperties;
import com.medianexus.orchestrator.integration.openlist.OpenListDirectoryPrepareException.Reason;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * OpenList API 客户端。
 *
 * 本客户端暴露 magnet 导入所需的完整 OpenList 操作：离线下载任务、文件枚举、
 * 批量重命名、移动、删除和目录创建。所有路径在请求前都会规范化为 OpenList
 * 使用的绝对路径格式。
 */
@Component
public class OpenListClient {

    private static final Logger log = LoggerFactory.getLogger(OpenListClient.class);
    private static final int MAX_LOG_BODY_LENGTH = 500;
    private static final int MAX_UPLOAD_DNS_ATTEMPTS = 3;
    private static final int DIRECTORY_CREATE_PARALLELISM = 4;
    private static final List<String> NOT_FOUND_KEYWORDS = List.of(
            "not found",
            "does not exist",
            "object not found",
            "file not found",
            "目录不存在",
            "路径不存在"
    );

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
        return addOfflineDownload(path, magnet, offlineTool());
    }

    public String addOfflineDownload(String path, String magnet, String tool) {
        JsonNode data = post("fs/add_offline_download", Map.of(
                "path", normalizePath(path),
                "urls", List.of(magnet),
                "tool", cleanConfigValue(tool),
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
     * state 的业务解释和进度打印留给任务编排层处理，本方法只保留上游原始任务快照。
     */
    public OpenListOfflineTaskInfo offlineTaskInfo(String taskId) {
        JsonNode data = post("task/offline_download/info?tid=" + encode(taskId), Map.of());
        return objectMapper.convertValue(data, OpenListOfflineTaskInfo.class);
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
        return listFiles(path, false);
    }

    public List<OpenListFileInfo> listFiles(String path, boolean refresh) {
        JsonNode data = post("fs/list", Map.of(
                "path", normalizePath(path),
                "page", 1,
                "per_page", 0,
                "refresh", refresh
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
        return findFiles(path, false);
    }

    public List<OpenListFileInfo> findFiles(String path, boolean refresh) {
        List<OpenListFileInfo> children = listFiles(path, refresh);
        List<OpenListFileInfo> files = new ArrayList<>();
        for (OpenListFileInfo child : children) {
            if (Boolean.TRUE.equals(child.isDir())) {
                files.addAll(findFiles(joinPath(path, child.name()), refresh));
            } else {
                files.add(child);
            }
        }
        files.sort(Comparator.comparing(file -> Long.MAX_VALUE - (file.size() == null ? 0L : file.size())));
        return files;
    }

    /**
     * 确认 OpenList 目录可用：根路径必须已经存在，根以下层级按需创建。
     */
    public void ensureDirectoryReady(String fullPath, String rootPath) {
        String normalizedFullPath = normalizePath(fullPath);
        String normalizedRootPath = normalizePath(rootPath);

        if (!pathExists(normalizedRootPath)) {
            throw new OpenListDirectoryPrepareException(
                    Reason.ROOT_NOT_FOUND,
                    "OpenList root path does not exist: " + normalizedRootPath
            );
        }
        if (normalizedFullPath.equals(normalizedRootPath)) {
            return;
        }

        String rootPrefix = normalizedRootPath.endsWith("/")
                ? normalizedRootPath
                : normalizedRootPath + "/";
        if (!normalizedFullPath.startsWith(rootPrefix)) {
            throw new OpenListDirectoryPrepareException(
                    Reason.PATH_OUTSIDE_ROOT,
                    "OpenList target path is outside root path"
            );
        }
        if (pathExists(normalizedFullPath)) {
            return;
        }

        String currentPath = normalizedRootPath;
        String relativePath = normalizedFullPath.substring(rootPrefix.length());
        for (String segment : relativePath.split("/")) {
            if (!StringUtils.hasText(segment)) {
                continue;
            }
            currentPath = joinPath(currentPath, segment);
            if (pathExists(currentPath)) {
                continue;
            }
            try {
                mkdir(currentPath);
                refreshPath(parentPath(currentPath));
                if (waitUntilPathExists(currentPath)) {
                    continue;
                }
                throw new OpenListDirectoryPrepareException(
                        Reason.TARGET_CREATE_FAILED,
                        "OpenList target directory did not become visible: " + currentPath
                );
            } catch (OpenListDirectoryPrepareException exception) {
                throw exception;
            } catch (OpenListClientException exception) {
                throw new OpenListDirectoryPrepareException(
                        Reason.TARGET_CREATE_FAILED,
                        "OpenList target directory create failed: " + currentPath,
                        exception
                );
            }
        }
    }

    /**
     * 在已知父目录下准备一批直接子目录，并通过父目录清单统一确认全部可见。
     */
    public void ensureChildDirectoriesReady(String parentPath, List<String> childNames) {
        String normalizedParentPath = normalizePath(parentPath);
        Set<String> requiredNames = normalizeChildDirectoryNames(childNames);
        if (requiredNames.isEmpty()) {
            return;
        }

        try {
            createChildDirectories(normalizedParentPath, List.copyOf(requiredNames));
            if (waitUntilChildDirectoriesVisible(normalizedParentPath, requiredNames)) {
                return;
            }
            throw new OpenListDirectoryPrepareException(
                    Reason.TARGET_CREATE_FAILED,
                    "OpenList child directories did not become visible under: " + normalizedParentPath
            );
        } catch (OpenListDirectoryPrepareException exception) {
            throw exception;
        } catch (OpenListClientException exception) {
            throw new OpenListDirectoryPrepareException(
                    Reason.TARGET_CREATE_FAILED,
                    "OpenList child directory prepare failed under: " + normalizedParentPath,
                    exception
            );
        }
    }

    private Set<String> normalizeChildDirectoryNames(List<String> childNames) {
        Set<String> normalizedNames = new LinkedHashSet<>();
        for (String childName : childNames) {
            String normalizedName = childName == null ? "" : childName.trim();
            if (!StringUtils.hasText(normalizedName)
                    || normalizedName.contains("/")
                    || normalizedName.contains("\\")
                    || normalizedName.equals(".")
                    || normalizedName.equals("..")) {
                throw new OpenListDirectoryPrepareException(
                        Reason.PATH_OUTSIDE_ROOT,
                        "OpenList child directory name is invalid: " + childName
                );
            }
            normalizedNames.add(normalizedName);
        }
        return normalizedNames;
    }

    private void createChildDirectories(String parentPath, List<String> childNames) {
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(DIRECTORY_CREATE_PARALLELISM, childNames.size())
        );
        try {
            List<Future<Void>> futures = executor.invokeAll(childNames.stream()
                    .<Callable<Void>>map(name -> () -> {
                        mkdir(joinPath(parentPath, name));
                        return null;
                    })
                    .toList());
            for (Future<Void> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    if (cause instanceof OpenListClientException clientException) {
                        throw clientException;
                    }
                    throw new OpenListClientException("OpenList child directory create failed", cause);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new OpenListDirectoryPrepareException(
                    Reason.TARGET_CREATE_FAILED,
                    "OpenList child directory prepare interrupted",
                    exception
            );
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean waitUntilChildDirectoriesVisible(String parentPath, Set<String> requiredNames) {
        int attempts = 3;
        for (int index = 0; index < attempts; index++) {
            if (visibleDirectoryNames(parentPath).containsAll(requiredNames)) {
                return true;
            }
            if (index < attempts - 1) {
                sleep(Duration.ofMillis(300));
            }
        }
        return false;
    }

    private Set<String> visibleDirectoryNames(String parentPath) {
        return listFiles(parentPath, true).stream()
                .filter(file -> Boolean.TRUE.equals(file.isDir()))
                .map(OpenListFileInfo::name)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
    }

    /**
     * 确认绝对路径的完整目录层级可用；首级目录视为 OpenList 存储根，根以下层级按需创建。
     */
    public void ensureDirectoryHierarchy(String fullPath) {
        String normalizedFullPath = normalizePath(fullPath);
        String storageRoot = Arrays.stream(normalizedFullPath.split("/"))
                .filter(StringUtils::hasText)
                .findFirst()
                .map(segment -> "/" + segment)
                .orElse("/");
        ensureDirectoryReady(normalizedFullPath, storageRoot);
    }

    /**
     * 判断路径是否存在。OpenList 以非 200 业务码表示不存在时返回 false。
     */
    public boolean pathExists(String path) {
        JsonNode root = postRoot("fs/get", Map.of("path", normalizePath(path)));
        if (isSuccess(root)) {
            return true;
        }
        if (isNotFound(root)) {
            return false;
        }
        log.warn(
                "OpenList path check returned non-success code={} message={}",
                root == null ? null : root.path("code").asInt(-1),
                message(root)
        );
        throw new OpenListClientException("OpenList path check failed: " + message(root));
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
     * 通过 OpenList 文件上传 API 将本地文件写入指定远端路径。
     */
    public void uploadFile(Path localFile, String remotePath, boolean overwrite) {
        validateConfiguration();
        String normalizedRemotePath = normalizePath(remotePath);
        for (int attempt = 1; attempt <= MAX_UPLOAD_DNS_ATTEMPTS; attempt++) {
            try {
                HttpRequest request = requestBuilder("fs/put")
                        .header("Content-Type", "application/octet-stream")
                        .header("File-Path", encodeHeaderValue(normalizedRemotePath))
                        .header("Overwrite", Boolean.toString(overwrite))
                        .PUT(HttpRequest.BodyPublishers.ofFile(localFile))
                        .build();
                JsonNode root = send(request, "fs/put");
                if (isSuccess(root)) {
                    return;
                }
                String upstreamMessage = message(root);
                if (isRetryableUploadDnsFailure(upstreamMessage) && attempt < MAX_UPLOAD_DNS_ATTEMPTS) {
                    log.warn(
                            "OpenList upload DNS failure; retrying path={} attempt={}/{} message={}",
                            normalizedRemotePath,
                            attempt,
                            MAX_UPLOAD_DNS_ATTEMPTS,
                            upstreamMessage
                    );
                    sleepUploadRetry(Duration.ofSeconds(attempt));
                    continue;
                }
                log.warn(
                        "OpenList returned non-success payload action=fs/put path={} code={} message={}",
                        normalizedRemotePath,
                        root == null ? null : root.path("code").asInt(-1),
                        upstreamMessage
                );
                throw new OpenListClientException("OpenList upload failed: " + upstreamMessage);
            } catch (IOException exception) {
                throw new OpenListClientException("OpenList upload body prepare failed: " + localFile, exception);
            }
        }
        throw new OpenListClientException("OpenList upload failed after DNS retries");
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
            log.warn(
                    "OpenList returned non-success payload action={} code={} message={}",
                    action,
                    root == null ? null : root.path("code").asInt(-1),
                    message(root)
            );
            throw new OpenListClientException("OpenList returned non-success payload: " + message(root));
        }
        JsonNode data = root.get("data");
        return data == null || data.isNull() ? objectMapper.createObjectNode() : data;
    }

    private void refreshPath(String path) {
        try {
            JsonNode root = postRoot("fs/list", Map.of(
                    "path", normalizePath(path),
                    "page", 1,
                    "per_page", 1,
                    "refresh", true
            ));
            if (!isSuccess(root)) {
                log.warn("OpenList refresh path returned non-success path={} message={}", path, message(root));
            }
        } catch (OpenListClientException exception) {
            log.warn("OpenList refresh path failed path={}", path, exception);
        }
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
            log.warn(
                    "OpenList returned non-success payload action={} code={} message={}",
                    action,
                    root == null ? null : root.path("code").asInt(-1),
                    message(root)
            );
            throw new OpenListClientException("OpenList returned non-success payload: " + message(root));
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
                log.warn(
                        "OpenList request returned non-success status action={} status={} body={}",
                        action,
                        response.statusCode(),
                        truncateForLog(response.body())
                );
                throw new OpenListClientException("OpenList returned non-success status for " + action);
            }
            return objectMapper.readTree(response.body());
        } catch (HttpTimeoutException exception) {
            throw new OpenListClientException(
                    "OpenList 请求超时: " + action + " 超过 " + timeoutHint()
                            + "；长耗时的 PikPak/OpenList 操作可能已被客户端取消，请检查目录是否已部分完成后再重试",
                    exception
            );
        } catch (IOException exception) {
            throw new OpenListClientException(
                    "OpenList request failed for " + action + " ("
                            + exception.getClass().getSimpleName() + ": " + exception.getMessage() + ")",
                    exception
            );
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

    private boolean isNotFound(JsonNode root) {
        String normalizedMessage = message(root).toLowerCase();
        return NOT_FOUND_KEYWORDS.stream().anyMatch(normalizedMessage::contains);
    }

    private String message(JsonNode root) {
        return root == null ? "" : root.path("message").asText("");
    }

    private boolean waitUntilPathExists(String path) {
        int attempts = 3;
        for (int index = 0; index < attempts; index++) {
            if (pathExists(path)) {
                return true;
            }
            if (index < attempts - 1) {
                sleep(Duration.ofMillis(300));
            }
        }
        return false;
    }

    private String parentPath(String path) {
        String normalized = normalizePath(path);
        int index = normalized.lastIndexOf('/');
        return index <= 0 ? "/" : normalized.substring(0, index);
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new OpenListDirectoryPrepareException(
                    Reason.TARGET_CREATE_FAILED,
                    "OpenList directory prepare interrupted",
                    exception
            );
        }
    }

    private boolean isRetryableUploadDnsFailure(String upstreamMessage) {
        String normalizedMessage = upstreamMessage == null ? "" : upstreamMessage.toLowerCase();
        return normalizedMessage.contains("no such host")
                || normalizedMessage.contains("temporary failure in name resolution");
    }

    private void sleepUploadRetry(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new OpenListClientException("OpenList upload retry interrupted", exception);
        }
    }

    private String truncateForLog(String value) {
        if (value == null) {
            return value;
        }
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ");
        return normalized.length() <= MAX_LOG_BODY_LENGTH
                ? normalized
                : normalized.substring(0, MAX_LOG_BODY_LENGTH);
    }

    private void validateConfiguration() {
        if (!StringUtils.hasText(cleanConfigValue(properties.getBaseUrl()))
                || !StringUtils.hasText(cleanConfigValue(properties.getAuthorization()))) {
            throw new OpenListClientException("OpenList configuration is incomplete");
        }
    }

    private String offlineTool() {
        return cleanConfigValue(properties.getOfflineTool());
    }

    private String deletePolicy() {
        return cleanConfigValue(properties.getDeletePolicy());
    }

    private Duration timeout() {
        return properties.getTimeout();
    }

    private String timeoutHint() {
        Duration configuredTimeout = timeout();
        long millis = configuredTimeout.toMillis();
        String value = millis % 1000 == 0
                ? configuredTimeout.toSeconds() + "s"
                : millis + "ms";
        return "MEDIANEXUS_OPENLIST_TIMEOUT=" + value;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String encodeHeaderValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
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
