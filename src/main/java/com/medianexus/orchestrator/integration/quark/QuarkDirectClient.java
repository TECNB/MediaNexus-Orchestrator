package com.medianexus.orchestrator.integration.quark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medianexus.orchestrator.config.QasProperties;
import com.medianexus.orchestrator.integration.qas.QasClientException;
import com.medianexus.orchestrator.integration.qas.QasShareNode;
import com.medianexus.orchestrator.integration.qas.QasShareTree;
import com.medianexus.orchestrator.service.QasTaskPlan;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Direct Quark transfer adapter used for non-subscription ingest tasks. */
@Component
public class QuarkDirectClient {

    private static final String DEFAULT_BASE_URL = "https://drive-pc.quark.cn";
    private static final int BATCH_SIZE = 100;
    private static final int MAX_RETRIES = 2;
    private static final int MAX_TASK_RETRIES = 2;
    private static final Pattern SHARE_PATH = Pattern.compile("/s/([^/?#]+)(?:/([^/?#]+))?");
    private static final Pattern FID = Pattern.compile("/([0-9a-fA-F]{32})(?:[-/?#]|$)");

    private final QasProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final AtomicReference<String> cookie;

    public QuarkDirectClient(QasProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.getTimeout() == null ? Duration.ofSeconds(15) : properties.getTimeout())
                .build();
        this.cookie = new AtomicReference<>(properties.getQuarkCookie() == null ? "" : properties.getQuarkCookie().trim());
    }

    public void transfer(QasTaskPlan plan, QasShareTree tree, Consumer<String> progress) {
        if (!StringUtils.hasText(properties.getQuarkCookie())) {
            throw new QasClientException(QasClientException.Reason.AUTHENTICATION,
                    "夸克登录状态已失效，请联系管理员更新夸克登录凭证");
        }
        ShareRef ref = parse(plan.sourceUrl());
        String stoken = fetchStoken(ref.shareId(), ref.passcode());
        List<QasShareNode> files = loadShareFiles(ref.shareId(), stoken, ref.startFid(), 0);
        if (StringUtils.hasText(plan.pattern())) {
            Pattern selectionPattern = Pattern.compile(plan.pattern());
            files.removeIf(file -> !selectionPattern.matcher(file.name()).matches());
        }
        if (files.isEmpty()) {
            throw new QasClientException(QasClientException.Reason.INVALID_RESPONSE, "分享中没有可转存文件");
        }
        String targetFid = ensureDirectory(plan.savePath());
        for (int offset = 0; offset < files.size(); offset += BATCH_SIZE) {
            List<QasShareNode> batch = files.subList(offset, Math.min(files.size(), offset + BATCH_SIZE));
            List<String> fids = batch.stream().map(QasShareNode::fid).toList();
            List<String> tokens = batch.stream().map(QasShareNode::shareFidToken).toList();
            if (tokens.stream().anyMatch(token -> !StringUtils.hasText(token))) {
                throw new QasClientException(QasClientException.Reason.INVALID_RESPONSE,
                        "分享文件缺少转存凭证，请重新检查分享目录");
            }
            ObjectNode savePayload = objectMapper.createObjectNode();
            savePayload.putPOJO("fid_list", fids);
            savePayload.putPOJO("fid_token_list", tokens);
            savePayload.put("to_pdir_fid", targetFid);
            savePayload.put("pwd_id", ref.shareId());
            savePayload.put("stoken", stoken);
            savePayload.put("pdir_fid", "0");
            savePayload.put("scene", "link");
            JsonNode saved = request("POST", savePath("/1/clouddrive/share/sharepage/save"), savePayload);
            String taskId = extractTaskId(saved);
            if (StringUtils.hasText(taskId)) {
                awaitTaskWithRetry(taskId, savePayload, progress);
            }
            progress.accept("Quark 已提交转存 " + Math.min(files.size(), offset + BATCH_SIZE) + "/" + files.size());
        }
        renameTransferred(plan, targetFid, progress);
    }

    private void renameTransferred(QasTaskPlan plan, String targetFid, Consumer<String> progress) {
        if (!StringUtils.hasText(plan.pattern()) || !StringUtils.hasText(plan.replace())) {
            return;
        }
        JsonNode listed = request("GET", "/1/clouddrive/file/sort?pr=ucpro&fr=pc&uc_param_str="
                + "&_page=1&_size=500&_fetch_total=1&_fetch_sub_dirs=0&fetch_all_file=1&pdir_fid="
                + enc(targetFid), null);
        JsonNode files = listed.path("data").path("list");
        if (!files.isArray()) return;
        Pattern pattern = Pattern.compile(plan.pattern());
        String replacement = convertReplacement(plan.replace());
        for (JsonNode file : files) {
            String name = file.path("file_name").asText("");
            String fid = file.path("fid").asText("");
            Matcher matcher = pattern.matcher(name);
            if (!matcher.matches() || !StringUtils.hasText(fid)) continue;
            String target;
            try {
                target = matcher.replaceFirst(replacement);
            } catch (RuntimeException ignored) {
                continue;
            }
            if (name.equals(target)) continue;
            ObjectNode payload = objectMapper.createObjectNode().put("fid", fid).put("file_name", target);
            request("POST", "/1/clouddrive/file/rename?pr=ucpro&fr=pc&uc_param_str=", payload);
            progress.accept("重命名：" + name + " → " + target);
        }
    }

    private String ensureDirectory(String path) {
        String parentFid = "0";
        StringBuilder currentPath = new StringBuilder();
        for (String segment : path.split("/")) {
            if (!StringUtils.hasText(segment)) continue;
            currentPath.append('/').append(segment);
            ObjectNode mkdirPayload = objectMapper.createObjectNode()
                    .put("pdir_fid", parentFid)
                    .put("file_name", segment)
                    .put("dir_path", "")
                    .put("dir_init_lock", false);
            String fid;
            try {
                JsonNode result = request("POST", "/1/clouddrive/file?pr=ucpro&fr=pc&uc_param_str=", mkdirPayload);
                fid = result.path("data").path("fid").asText("");
            } catch (QasClientException exception) {
                ObjectNode listPayload = objectMapper.createObjectNode();
                listPayload.putPOJO("file_path", List.of(currentPath.toString()));
                listPayload.put("namespace", "0");
                JsonNode listed = request("POST", "/1/clouddrive/file/info/path_list?pr=ucpro&fr=pc&uc_param_str=", listPayload);
                fid = listed.path("data").path(0).path("fid").asText("");
            }
            if (!StringUtils.hasText(fid)) {
                throw new QasClientException(QasClientException.Reason.UPSTREAM, "无法创建或定位 Quark 保存目录：" + currentPath);
            }
            parentFid = fid;
        }
        return parentFid;
    }

    private void awaitTaskWithRetry(String taskId, ObjectNode savePayload, Consumer<String> progress) {
        QasClientException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_TASK_RETRIES + 1; attempt++) {
            try {
                awaitTask(taskId, progress);
                return;
            } catch (QasClientException exception) {
                lastFailure = exception;
                if (attempt > MAX_TASK_RETRIES || !isRetryableTaskFailure(exception)) {
                    throw exception;
                }
                progress.accept("Quark 转存失败，正在重试 " + attempt + "/" + MAX_TASK_RETRIES);
                sleep(1000L * attempt);
                JsonNode retried = request("POST", savePath("/1/clouddrive/share/sharepage/save"), savePayload);
                String retryTaskId = extractTaskId(retried);
                if (!StringUtils.hasText(retryTaskId)) {
                    throw exception;
                }
                taskId = retryTaskId;
            }
        }
        throw lastFailure == null
                ? new QasClientException(QasClientException.Reason.UPSTREAM, "Quark 转存失败")
                : lastFailure;
    }

    private void awaitTask(String taskId, Consumer<String> progress) {
        long deadline = System.nanoTime() + Duration.ofMinutes(30).toNanos();
        int retries = 0;
        while (System.nanoTime() < deadline) {
            JsonNode result;
            try {
                result = request("GET", taskPath(taskId, retries), null);
            } catch (QasClientException exception) {
                if (retries++ >= MAX_RETRIES) throw exception;
                sleep(Math.min(10000L, 1000L * retries));
                continue;
            }
            JsonNode task = result.path("data");
            int status = task.path("status").asInt(-1);
            String statusText = firstText(task, "status_text", "statusText", "state", "status", "message");
            if (status == 2 || task.path("success").asBoolean(false)
                    || "success".equalsIgnoreCase(statusText)
                    || "completed".equalsIgnoreCase(statusText)
                    || "complete".equalsIgnoreCase(statusText)) return;
            if (status >= 3 || "failed".equalsIgnoreCase(statusText)
                    || "error".equalsIgnoreCase(statusText)) {
                throw new QasClientException(QasClientException.Reason.UPSTREAM,
                        firstText(task, "message", "error", "error_msg", "errorMessage"));
            }
            progress.accept("Quark 转存处理中");
            sleep(2000L);
        }
        throw new QasClientException(QasClientException.Reason.UPSTREAM, "Quark 转存超时");
    }

    private static boolean isRetryableTaskFailure(QasClientException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase();
        return message.contains("timeout") || message.contains("超时")
                || message.contains("tempor") || message.contains("network")
                || message.contains("busy") || message.contains("系统");
    }

    private String fetchStoken(String shareId, String passcode) {
        JsonNode result = request("POST", "/1/clouddrive/share/sharepage/token?pr=ucpro&fr=pc",
                objectMapper.createObjectNode().put("pwd_id", shareId).put("passcode", passcode));
        String token = result.path("data").path("stoken").asText("");
        if (!StringUtils.hasText(token)) {
            throw new QasClientException(QasClientException.Reason.AUTHENTICATION,
                    "夸克登录状态已失效，请联系管理员更新夸克登录凭证");
        }
        return token;
    }

    private List<QasShareNode> loadShareFiles(String shareId, String stoken, String directoryFid, int depth) {
        if (depth > 4) {
            throw new QasClientException(QasClientException.Reason.INVALID_RESPONSE, "夸克分享目录深度超过 4");
        }
        List<QasShareNode> files = new ArrayList<>();
        int page = 1;
        while (true) {
            String path = "/1/clouddrive/share/sharepage/detail?pr=ucpro&fr=pc"
                    + "&pwd_id=" + enc(shareId)
                    + "&stoken=" + enc(stoken)
                    + "&pdir_fid=" + enc(directoryFid)
                    + "&force=0&_page=" + page + "&_size=50&_fetch_banner=0&_fetch_share=0&_fetch_total=1"
                    + "&_sort=file_type:asc,updated_at:desc&ver=2&fetch_share_full_path=0";
            JsonNode data = request("GET", path, null).path("data");
            JsonNode entries = data.path("list");
            if (!entries.isArray()) {
                throw new QasClientException(QasClientException.Reason.INVALID_RESPONSE,
                        "夸克分享目录响应缺少文件列表");
            }
            for (JsonNode entry : entries) {
                String fid = entry.path("fid").asText("");
                String name = entry.path("file_name").asText("");
                if (!StringUtils.hasText(fid) || !StringUtils.hasText(name)) continue;
                if (entry.path("dir").asBoolean(false)) {
                    files.addAll(loadShareFiles(shareId, stoken, fid, depth + 1));
                } else {
                    files.add(new QasShareNode(
                            fid,
                            name,
                            false,
                            entry.path("obj_category").asText(null),
                            entry.path("size").asLong(0),
                            List.of(),
                            entry.path("share_fid_token").asText(null)
                    ));
                }
            }
            int total = data.path("metadata").path("_total").asInt(0);
            if (entries.size() < 50 || (total > 0 && page * 50 >= total)) break;
            page++;
        }
        return files;
    }

    private static String savePath(String path) {
        return path + "?pr=ucpro&fr=pc&uc_param_str=&app=clouddrive&__dt="
                + ThreadLocalRandom.current().nextLong(60000L, 300001L)
                + "&__t=" + (System.currentTimeMillis() / 1000.0d);
    }

    private static String taskPath(String taskId, int retryIndex) {
        return "/1/clouddrive/task?pr=ucpro&fr=pc&uc_param_str=&task_id=" + enc(taskId)
                + "&retry_index=" + retryIndex
                + "&__dt=" + ThreadLocalRandom.current().nextLong(60000L, 300001L)
                + "&__t=" + (System.currentTimeMillis() / 1000.0d);
    }

    private JsonNode request(String method, String path, ObjectNode body) {
        for (int attempt = 0; ; attempt++) {
            try {
                String baseUrl = StringUtils.hasText(properties.getQuarkApiBaseUrl())
                        ? properties.getQuarkApiBaseUrl().trim()
                        : DEFAULT_BASE_URL;
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .timeout(timeout())
                        .header("Accept", "application/json, text/plain, */*")
                        .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                        .header("Origin", "https://pan.quark.cn")
                        .header("Referer", "https://pan.quark.cn/")
                        .header("X-Quark-Cookie", cookie.get())
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/151.0.0.0 Safari/537.36");
                if ("POST".equals(method)) {
                    builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body.toString()));
                } else {
                    builder.GET();
                }
                HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                JsonNode root = objectMapper.readTree(response.body());
                mergeResponseCookies(response);
                int code = root == null ? -1 : root.path("code").asInt(0);
                if (response.statusCode() == 401 || response.statusCode() == 403 || code == 401 || code == 403) {
                    String reason = root == null ? "" : root.path("message").asText("");
                    throw new QasClientException(QasClientException.Reason.AUTHENTICATION,
                            StringUtils.hasText(reason)
                                    ? "夸克接口拒绝请求：" + reason
                                    : "夸克登录状态已失效，请联系管理员更新夸克登录凭证");
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300 || (root != null && code != 0 && !root.path("success").asBoolean(false))) {
                    throw new QasClientException(QasClientException.Reason.UPSTREAM,
                            root == null ? "Quark API 请求失败" : root.path("message").asText("Quark API 请求失败"));
                }
                return root;
            } catch (QasClientException exception) {
                if (exception.getReason() == QasClientException.Reason.AUTHENTICATION
                        || exception.getReason() == QasClientException.Reason.INVALID_RESPONSE) {
                    throw exception;
                }
                if (attempt >= MAX_RETRIES) throw exception;
                sleep(Math.min(10000L, 1000L * (attempt + 1)));
            } catch (IOException exception) {
                if (attempt >= MAX_RETRIES) {
                    throw new QasClientException(QasClientException.Reason.UPSTREAM, "Quark API 请求失败", exception);
                }
                sleep(Math.min(10000L, 1000L * (attempt + 1)));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new QasClientException(QasClientException.Reason.UPSTREAM, "Quark API 请求被中断", exception);
            }
        }
    }

    private static String extractTaskId(JsonNode root) {
        String value = root.path("data").path("task_id").asText("");
        if (StringUtils.hasText(value)) return value;
        return root.path("data").path("taskId").asText("");
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("").trim();
            if (StringUtils.hasText(value)) return value;
        }
        return "Quark 转存失败";
    }

    private void mergeResponseCookies(HttpResponse<String> response) {
        List<String> setCookies = response.headers().allValues("set-cookie");
        if (setCookies.isEmpty()) return;
        java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
        for (String part : cookie.get().split(";\\s*")) {
            int separator = part.indexOf('=');
            if (separator > 0) values.put(part.substring(0, separator), part.substring(separator + 1));
        }
        for (String setCookie : setCookies) {
            String pair = setCookie.split(";", 2)[0];
            int separator = pair.indexOf('=');
            if (separator > 0) values.put(pair.substring(0, separator), pair.substring(separator + 1));
        }
        cookie.set(values.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "; " + right).orElse(""));
    }

    private static void collectFiles(List<QasShareNode> nodes, List<QasShareNode> result) {
        for (QasShareNode node : nodes) {
            if (node.directory()) collectFiles(node.children(), result); else result.add(node);
        }
    }

    private static QasShareNode findSelected(List<QasShareNode> nodes, String fid) {
        if ("0".equals(fid)) return null;
        for (QasShareNode node : nodes) {
            if (fid.equalsIgnoreCase(node.fid())) return node;
            QasShareNode child = findSelected(node.children(), fid);
            if (child != null) return child;
        }
        return null;
    }

    private static ShareRef parse(String url) {
        Matcher matcher = SHARE_PATH.matcher(url);
        if (!matcher.find()) throw new QasClientException(QasClientException.Reason.INVALID_RESPONSE, "夸克分享链接格式无效");
        String fid = matcher.group(2);
        Matcher hash = FID.matcher(url.substring(Math.max(0, url.indexOf('#'))));
        if (hash.find()) fid = hash.group(1);
        String passcode = "";
        int query = url.indexOf("pwd=");
        if (query >= 0) passcode = url.substring(query + 4).split("[&#]", 2)[0];
        return new ShareRef(matcher.group(1), fid == null ? "0" : fid, passcode);
    }

    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private Duration timeout() {
        return properties.getTimeout() == null ? Duration.ofSeconds(15) : properties.getTimeout();
    }
    private static String convertReplacement(String value) {
        Matcher matcher = Pattern.compile("\\\\(\\d+)").matcher(value);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement("$" + matcher.group(1)));
        }
        matcher.appendTail(result);
        return result.toString();
    }
    private static void sleep(long millis) { try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new QasClientException(QasClientException.Reason.UPSTREAM, "Quark 请求被中断", e); } }
    private record ShareRef(String shareId, String startFid, String passcode) { }
}
