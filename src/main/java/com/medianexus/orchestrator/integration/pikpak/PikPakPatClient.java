package com.medianexus.orchestrator.integration.pikpak;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.OpenListProperties;
import com.medianexus.orchestrator.config.PikPakPatProperties;
import com.medianexus.orchestrator.integration.openlist.OpenListClientException;
import com.medianexus.orchestrator.integration.openlist.OpenListOfflineTaskInfo;
import com.medianexus.orchestrator.integration.openlist.OpenListStorageStatus;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** PikPak 云盘离线下载。只在路径命中已配置令牌时使用。 */
@Component
public class PikPakPatClient {

    private static final String API_ROOT = "https://api-drive.mypikpak.com";
    private static final String COMPLETE_FILTER =
            "{\"phase\":{\"eq\":\"PHASE_TYPE_COMPLETE\"},\"trashed\":{\"eq\":false}}";
    private static final String OPEN_FILTER =
            "{\"phase\":{\"in\":\"PHASE_TYPE_RUNNING,PHASE_TYPE_PENDING\"}}";

    private final PikPakPatProperties properties;
    private final ObjectMapper objectMapper;
    private final Duration timeout;
    private final HttpClient httpClient;
    // ponytail: one folder-id map, no per-path lock; a failed create lists once more. Per-account locks if duplicate folders show up.
    private final ConcurrentHashMap<String, String> folderIds = new ConcurrentHashMap<>();

    public PikPakPatClient(
            PikPakPatProperties properties, OpenListProperties openListProperties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        Duration configured = openListProperties.getTimeout();
        this.timeout = configured == null || configured.isZero() || configured.isNegative()
                ? Duration.ofSeconds(120) : configured;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** 未命中已配置前缀时返回 null，由 OpenList 继续处理。 */
    public String submit(String path, String url) {
        Account account = match(normalize(path));
        if (account == null) {
            return null;
        }
        if (url == null || url.isBlank()) {
            throw new OpenListClientException("离线链接为空");
        }
        HttpResult result = send("POST", "/drive/v1/files", account.token, write(Map.of(
                "kind", "drive#file",
                "upload_type", "UPLOAD_TYPE_URL",
                "url", Map.of("url", url),
                "parent_id", resolveParent(account, path))));
        if (result.status < 200 || result.status >= 300 || result.body == null) {
            throw new OpenListClientException("PikPak PAT 提交离线任务失败: " + errorText(result));
        }
        String taskId = createdTaskId(result.body);
        if (taskId.isEmpty()) {
            throw new OpenListClientException("PikPak PAT 任务 id 缺失");
        }
        return "pat:" + account.key + ":" + taskId;
    }

    public boolean matches(String path) {
        return match(normalize(path)) != null;
    }

    public OpenListStorageStatus storageStatus(String path) {
        Account account = match(normalize(path));
        if (account == null) {
            throw new OpenListClientException("PikPak PAT 路径未配置");
        }
        HttpResult result = send("GET", "/drive/v1/about", account.token, null);
        if (result.status != 200 || result.body == null) {
            throw new OpenListClientException("PikPak PAT 容量查询失败: " + errorText(result));
        }
        JsonNode quota = result.body.path("quota");
        long limit = requiredLong(quota.get("limit"));
        long usage = requiredLong(quota.get("usage"));
        return new OpenListStorageStatus("PikPak", true, usage, limit, Math.max(0L, limit - usage));
    }

    /** 只计 RUNNING 与 PENDING。接口失败必须抛出，不能当成 0。 */
    public int unfinishedCount() {
        int count = 0;
        for (Account account : accounts()) {
            count += countOpen(account);
        }
        return count;
    }

    public OpenListOfflineTaskInfo task(String taskId) {
        Parsed parsed = parse(taskId);
        HttpResult result = send("GET", "/drive/v1/tasks/" + enc(parsed.rawId), parsed.account.token, null);
        if (result.status == 404) {
            return new OpenListOfflineTaskInfo(taskId, 4, "missing", 0, null, "");
        }
        if (result.status != 200 || result.body == null) {
            throw new OpenListClientException("PikPak PAT 查询任务失败: " + errorText(result));
        }
        return mapTask(taskId, taskBody(result.body));
    }

    public void deleteTask(String taskId) {
        Parsed parsed = parse(taskId);
        HttpResult result = send(
                "DELETE",
                "/drive/v1/tasks?task_ids=" + enc(parsed.rawId) + "&delete_files=false",
                parsed.account.token,
                null);
        if (result.status == 404 || (result.status >= 200 && result.status < 300)) {
            return;
        }
        throw new OpenListClientException("PikPak PAT 删除任务失败: " + errorText(result));
    }

    public void retry(String taskId) {
        parse(taskId);
        throw new OpenListClientException("PikPak PAT 没有已核实的任务重试接口，拒绝静默重发");
    }

    private int countOpen(Account account) {
        int count = 0;
        String page = "";
        Set<String> seen = new HashSet<>();
        while (true) {
            if (!seen.add(page) || seen.size() > 100) {
                throw new OpenListClientException("PikPak PAT 任务分页未推进，暂停新提交");
            }
            HttpResult result = send("GET", tasksQuery(page), account.token, null);
            if (result.status != 200 || result.body == null || !result.body.path("tasks").isArray()) {
                throw new OpenListClientException("PikPak PAT 无法核实遗留任务，暂停新提交");
            }
            for (JsonNode task : result.body.path("tasks")) {
                String phase = task.path("phase").asText("");
                if ("PHASE_TYPE_RUNNING".equals(phase) || "PHASE_TYPE_PENDING".equals(phase)) {
                    count++;
                }
            }
            String next = result.body.path("next_page_token").asText("");
            if (next.isEmpty()) {
                return count;
            }
            page = next;
        }
    }

    private String resolveParent(Account account, String path) {
        String normalized = normalize(path);
        String relative = normalized.equals(account.prefix) ? "" : normalized.substring(account.prefix.length() + 1);
        String parent = "";
        String walked = account.prefix;
        if (relative.isEmpty()) {
            return "";
        }
        for (String segment : relative.split("/", -1)) {
            if (segment.isEmpty()) {
                continue;
            }
            if (".".equals(segment) || "..".equals(segment)) {
                throw new OpenListClientException("入库路径无效");
            }
            walked = walked + "/" + segment;
            parent = folder(account, parent, segment, walked);
        }
        return parent;
    }

    private String folder(Account account, String parentId, String name, String walked) {
        String cacheKey = account.key + "\n" + walked;
        String cached = folderIds.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        String id = findFolder(account, parentId, name);
        if (id == null) {
            id = createFolder(account, parentId, name);
        }
        folderIds.put(cacheKey, id);
        return id;
    }

    private String findFolder(Account account, String parentId, String name) {
        String page = "";
        Set<String> seen = new HashSet<>();
        List<String> hits = new ArrayList<>();
        while (true) {
            if (!seen.add(page) || seen.size() > 100) {
                throw new OpenListClientException("PikPak PAT 列目录分页未推进");
            }
            HttpResult result = send("GET", filesQuery(parentId, page), account.token, null);
            if (result.status != 200 || result.body == null) {
                throw new OpenListClientException("PikPak PAT 列目录失败: " + errorText(result));
            }
            for (JsonNode file : result.body.path("files")) {
                if (name.equals(file.path("name").asText()) && "drive#folder".equals(file.path("kind").asText())) {
                    String id = file.path("id").asText("");
                    if (!id.isEmpty()) {
                        hits.add(id);
                    }
                }
            }
            String next = result.body.path("next_page_token").asText("");
            if (next.isEmpty()) {
                break;
            }
            page = next;
        }
        if (hits.size() > 1) {
            throw new OpenListClientException("PikPak PAT 目录名重复: " + name);
        }
        return hits.isEmpty() ? null : hits.get(0);
    }

    private String createFolder(Account account, String parentId, String name) {
        HttpResult result = send("POST", "/drive/v1/files", account.token, write(Map.of(
                "kind", "drive#folder",
                "parent_id", parentId,
                "name", name)));
        if (result.status >= 200 && result.status < 300) {
            String id = createdFolderId(result.body);
            if (id != null) {
                return id;
            }
        }
        String existing = findFolder(account, parentId, name);
        if (existing != null) {
            return existing;
        }
        throw new OpenListClientException("PikPak PAT 建目录失败: " + errorText(result));
    }

    private OpenListOfflineTaskInfo mapTask(String taskId, JsonNode task) {
        String phase = task.path("phase").asText("");
        Integer progress = progress(task.path("progress"));
        String error = "";
        int state = switch (phase) {
            case "PHASE_TYPE_COMPLETE" -> {
                progress = 100;
                yield 2;
            }
            case "PHASE_TYPE_RUNNING" -> 1;
            case "PHASE_TYPE_PENDING" -> 0;
            case "PHASE_TYPE_ERROR" -> {
                error = task.path("message").asText("");
                if (error.isBlank()) {
                    error = phase;
                }
                yield 7;
            }
            default -> 1;
        };
        return new OpenListOfflineTaskInfo(
                taskId, state, phase.isEmpty() ? "unknown" : phase, progress, fileSize(task), error);
    }

    private Account match(String path) {
        List<Account> matched = accounts().stream()
                .filter(account -> path.equals(account.prefix) || path.startsWith(account.prefix + "/"))
                .toList();
        if (matched.isEmpty()) {
            return null;
        }
        int longest = matched.stream().mapToInt(account -> account.prefix.length()).max().orElse(0);
        List<Account> winners = matched.stream().filter(account -> account.prefix.length() == longest).toList();
        if (winners.size() != 1) {
            throw new OpenListClientException("PikPak PAT 路径前缀冲突");
        }
        return winners.get(0);
    }

    private List<Account> accounts() {
        List<Account> accounts = new ArrayList<>();
        add(accounts, "media-b", properties.getMediaBPrefix(), properties.getMediaBToken());
        add(accounts, "adult", properties.getAdultPrefix(), properties.getAdultToken());
        return accounts;
    }

    private void add(List<Account> accounts, String key, String prefix, String token) {
        String cleanedToken = token(token);
        String rawPrefix = clean(prefix);
        if (cleanedToken.isEmpty() || rawPrefix.isEmpty()) {
            return;
        }
        String cleanedPrefix = normalize(rawPrefix);
        if ("/".equals(cleanedPrefix)) {
            throw new OpenListClientException("PikPak PAT 前缀不能是根路径");
        }
        accounts.add(new Account(key, cleanedPrefix, cleanedToken));
    }

    private Parsed parse(String taskId) {
        String[] parts = taskId == null ? new String[0] : taskId.split(":", 3);
        if (parts.length < 3 || !"pat".equals(parts[0]) || parts[1].isBlank() || parts[2].isBlank()) {
            throw new OpenListClientException("PikPak PAT 任务 id 无效");
        }
        Account account = accounts().stream()
                .filter(item -> item.key.equals(parts[1]))
                .findFirst()
                .orElseThrow(() -> new OpenListClientException("PikPak PAT 账号未配置: " + parts[1]));
        return new Parsed(account, parts[2]);
    }

    private HttpResult send(String method, String path, String token, String json) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(apiBase() + path))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + token);
            switch (method) {
                case "GET" -> builder.GET();
                case "DELETE" -> builder.DELETE();
                case "POST" -> builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
                default -> throw new IllegalArgumentException(method);
            }
            HttpResponse<String> response = httpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new HttpResult(response.statusCode(), readJson(response.body()));
        } catch (HttpTimeoutException exception) {
            throw new OpenListClientException("PikPak PAT 请求超时", exception);
        } catch (IOException exception) {
            throw new OpenListClientException("PikPak PAT 请求失败", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new OpenListClientException("PikPak PAT 请求被中断", exception);
        } catch (IllegalArgumentException exception) {
            throw new OpenListClientException("PikPak PAT 地址无效", exception);
        }
    }

    private String apiBase() {
        String value = clean(properties.getApiBase());
        if (value.isEmpty()) {
            value = API_ROOT;
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String filesQuery(String parentId, String page) {
        return "/drive/v1/files?parent_id=" + enc(parentId) + "&limit=100&page_token=" + enc(page)
                + "&filters=" + enc(COMPLETE_FILTER);
    }

    private String tasksQuery(String page) {
        return "/drive/v1/tasks?type=offline&limit=10000&page_token=" + enc(page)
                + "&filters=" + enc(OPEN_FILTER);
    }

    private String write(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException exception) {
            throw new OpenListClientException("PikPak PAT 请求序列化失败", exception);
        }
    }

    private JsonNode readJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(raw);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private String createdTaskId(JsonNode node) {
        JsonNode nested = node.path("task");
        if (nested.isObject() && !nested.path("id").asText("").isEmpty()) {
            return nested.path("id").asText();
        }
        return node.has("phase") ? node.path("id").asText("") : "";
    }

    private JsonNode taskBody(JsonNode node) {
        JsonNode nested = node.path("task");
        if (nested.isObject() && (nested.hasNonNull("id") || nested.has("phase"))) {
            return nested;
        }
        return node;
    }

    private String createdFolderId(JsonNode node) {
        if (node == null || (node.has("kind") && !"drive#folder".equals(node.path("kind").asText()))) {
            return null;
        }
        String id = node.path("id").asText("");
        return id.isEmpty() ? null : id;
    }

    private Integer progress(JsonNode node) {
        if (!node.isNumber()) {
            return null;
        }
        return Math.min(100, Math.max(0, node.asInt()));
    }

    private Long fileSize(JsonNode task) {
        JsonNode node = task.get("file_size");
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            long value = node.isNumber() ? node.longValue() : Long.parseLong(node.asText("").trim());
            return value < 0 ? null : value;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private long requiredLong(JsonNode node) {
        if (node == null || node.isNull()) {
            throw new OpenListClientException("PikPak PAT 容量字段缺失");
        }
        try {
            return node.isNumber() ? node.longValue() : Long.parseLong(node.asText("").trim());
        } catch (NumberFormatException exception) {
            throw new OpenListClientException("PikPak PAT 容量字段无效");
        }
    }

    private String errorText(HttpResult result) {
        String description = "";
        if (result.body != null) {
            description = result.body.path("error_description").asText("");
            if (description.isBlank()) {
                description = result.body.path("error").asText("");
            }
            if (description.isBlank()) {
                description = result.body.path("message").asText("");
            }
            description = description.replaceAll("[\\r\\n\\t]+", " ").trim();
            if (description.length() > 200) {
                description = description.substring(0, 200);
            }
        }
        return description.isEmpty() ? "HTTP " + result.status : "HTTP " + result.status + " " + description;
    }

    private static String normalize(String path) {
        String normalized = path == null || path.isBlank() ? "/" : path.trim().replace('\\', '/');
        normalized = normalized.replaceAll("/{2,}", "/");
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String clean(String value) {
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

    private static String token(String value) {
        String cleaned = clean(value);
        return cleaned.regionMatches(true, 0, "Bearer ", 0, 7) ? cleaned.substring(7).trim() : cleaned;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private record Account(String key, String prefix, String token) {
    }

    private record Parsed(Account account, String rawId) {
    }

    private record HttpResult(int status, JsonNode body) {
    }
}
