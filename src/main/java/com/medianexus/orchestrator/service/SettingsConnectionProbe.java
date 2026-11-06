package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.config.*;
import com.medianexus.orchestrator.dto.settings.SettingsDtos.Probe;
import com.medianexus.orchestrator.dto.settings.SettingsDtos.Resource;
import com.medianexus.orchestrator.integration.onboarding.AutoSymlinkAccountClient;
import com.medianexus.orchestrator.integration.onboarding.CloudDriveAccountClient;
import com.medianexus.orchestrator.integration.quark.QuarkDirectClient;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 只读/认证探针；不把回调、下载、扫描、消息或文件修改当成连接测试。 */
@Component
public class SettingsConnectionProbe {
    private final ObjectMapper json;
    public SettingsConnectionProbe(ObjectMapper json) { this.json = json; }

    public Probe check(String id, Object candidate, String source) {
        try {
            if ((candidate instanceof AutoSymlinkProperties as && as.isDisabled())
                    || (candidate instanceof ProwlarrProperties prowlarr && prowlarr.isDisabled())
                    || (candidate instanceof PanSouProperties pansou && pansou.isDisabled())) {
                return result("NOT_CONFIGURED", "此集成已明确停用，已有凭据保留", source, List.of());
            }
            return switch (id) {
                case "openlist" -> openlist((OpenListProperties) candidate, source);
                case "emby" -> emby((EmbyProperties) candidate, source);
                case "prowlarr" -> prowlarr((ProwlarrProperties) candidate, source);
                case "pansou" -> pansou((PanSouProperties) candidate, source);
                case "qas" -> quark((QasProperties) candidate, source);
                case "smartstrm" -> result("UNSUPPORTED", "回调仅显示配置状态；连接检查不会调用整理回调", source, List.of());
                case "wecom" -> wecom((WeComProperties) candidate, source);
                case "anirss" -> anirss((AniRssProperties) candidate, source);
                case "telegram-worker" -> telegram((TelegramWorkerProperties) candidate, source);
                case "autosymlink" -> autosymlink((AutoSymlinkProperties) candidate, source);
                case "clouddrive2" -> clouddrive((CloudDrive2Properties) candidate, source);
                default -> result("UNSUPPORTED", "此组没有只读连接探针", source, List.of());
            };
        } catch (ProbeFailure failure) {
            return result(failure.status, failure.getMessage(), source, List.of());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return result("FAILED", "连接检查已中断", source, List.of());
        } catch (Exception exception) {
            return result("FAILED", "连接检查失败；请核对地址、凭据、权限与软件版本", source, List.of());
        }
    }

    private Probe openlist(OpenListProperties p, String source) throws Exception {
        configured(p.getBaseUrl(), p.getAuthorization());
        JsonNode root = request(p.getBaseUrl(), "/api/admin/storage/list?page=1&per_page=500", p.getTimeout(),
                Map.of("Authorization", p.getAuthorization()), null);
        apiSuccess(root);
        JsonNode content = root.path("data").path("content");
        array(content);
        List<Resource> resources = new ArrayList<>();
        for (JsonNode storage : content) {
            Map<String, Object> fields = allow(storage, "driver", "disabled", "mount_path");
            fields.put("status", "work".equalsIgnoreCase(storage.path("status").asText()) ? "work" : "ERROR");
            JsonNode capacity = storage.path("mount_details");
            for (String key : List.of("total_space", "used_space", "free_space")) if (capacity.path(key).isNumber()) fields.put(key, capacity.path(key).numberValue());
            resources.add(new Resource(storage.path("id").asText(), storage.path("mount_path").asText(), fields));
        }
        return result("OK", "已读取真实存储；读取成功不代表已取得新增存储权限", source, resources);
    }

    private Probe emby(EmbyProperties p, String source) throws Exception {
        configured(p.getBaseUrl(), p.getApiKey());
        JsonNode folders = request(p.getBaseUrl(), "/Library/VirtualFolders", p.getTimeout(), Map.of("X-Emby-Token", p.getApiKey()), null);
        array(folders);
        List<Resource> resources = new ArrayList<>();
        for (JsonNode folder : folders) {
            Map<String, Object> fields = allow(folder, "CollectionType");
            JsonNode locations = folder.path("Locations");
            if (locations.isArray()) fields.put("locations", strings(locations));
            resources.add(new Resource(folder.path("ItemId").asText(), folder.path("Name").asText(), fields));
        }
        return result("OK", "已读取媒体库及目录；未刷新或修改媒体库", source, resources);
    }

    private Probe prowlarr(ProwlarrProperties p, String source) throws Exception {
        configured(p.getBaseUrl(), p.getApiKey());
        Map<String, String> headers = Map.of("X-Api-Key", p.getApiKey());
        JsonNode system = request(p.getBaseUrl(), "/api/v1/system/status", p.getTimeout(), headers, null);
        if (!system.path("version").isTextual()) throw unsupported();
        JsonNode indexers = request(p.getBaseUrl(), "/api/v1/indexer", p.getTimeout(), headers, null);
        array(indexers);
        List<Resource> resources = new ArrayList<>();
        resources.add(new Resource("system", "Prowlarr", allow(system, "version")));
        for (JsonNode indexer : indexers) resources.add(new Resource(indexer.path("id").asText(), indexer.path("name").asText(), allow(indexer, "enable", "protocol", "priority")));
        return result("OK", "已读取版本与索引器概览；未搜索、重建或改写索引器", source, resources);
    }

    private Probe pansou(PanSouProperties p, String source) throws Exception {
        configured(p.getBaseUrl(), p.getUsername(), p.getPassword());
        JsonNode body = json.valueToTree(Map.of("username", p.getUsername(), "password", p.getPassword()));
        JsonNode response = request(p.getBaseUrl(), "/api/auth/login", p.getTimeout(), Map.of(), body);
        if (!StringUtils.hasText(response.path("token").asText())) throw new ProbeFailure("UNAUTHORIZED", "PanSou 未返回有效认证令牌");
        return result("OK", "PanSou 认证通过；未执行搜索或链接操作", source, List.of());
    }

    private Probe quark(QasProperties p, String source) {
        configured(p.getQuarkApiBaseUrl(), p.getQuarkCookie());
        if (!new QuarkDirectClient(p, json).ownedPathExists("/")) throw new ProbeFailure("FAILED", "夸克根目录不可读取");
        return result("UNSUPPORTED", "夸克目录读取通过；QAS 当前客户端无独立只读认证端点，不能宣称完整认证已验证", source,
                List.of(new Resource("quark-check", "夸克", Map.of("status", "OK")),
                        new Resource("qas-check", "QAS", Map.of("status", "UNSUPPORTED", "reason", "没有已核实的独立只读认证端点"))));
    }

    private Probe wecom(WeComProperties p, String source) throws Exception {
        if (!p.isEnabled()) return result("NOT_CONFIGURED", "企业微信已明确停用，已有凭据保留", source, List.of());
        configured(p.getApiBaseUrl(), p.getCorpId(), p.getAppSecret());
        JsonNode response = request(p.getApiBaseUrl(), "/cgi-bin/gettoken?corpid=" + encode(p.getCorpId()) + "&corpsecret=" + encode(p.getAppSecret()), p.getTimeout(), Map.of(), null);
        if (response.path("errcode").asInt(0) != 0 || !StringUtils.hasText(response.path("access_token").asText())) throw new ProbeFailure("UNAUTHORIZED", "企业微信拒绝应用认证");
        return result("OK", "应用认证通过；未发送真实消息", source, List.of());
    }

    private Probe anirss(AniRssProperties p, String source) throws Exception {
        configured(p.getBaseUrl(), p.getApiKey());
        JsonNode response = request(p.getBaseUrl(), "/api/config", p.getTimeout(), Map.of("x-api-key", p.getApiKey()), json.createObjectNode());
        apiSuccess(response);
        JsonNode configuration = response.has("data") ? response.path("data") : response;
        return result("OK", "已只读获取本站连接与追更配置，未修改订阅", source,
                List.of(new Resource("download-paths", "追更保存位置", allow(configuration, "downloadPathTemplate", "uploadPathTarget"))));
    }

    private Probe telegram(TelegramWorkerProperties p, String source) throws Exception {
        configured(p.getBaseUrl());
        JsonNode response = request(p.getBaseUrl(), "/health", p.getTimeout(), Map.of(), null);
        if (!response.path("status").isTextual() || !response.path("authorized").isBoolean()
                || !response.path("stateWritable").isBoolean()) throw unsupported();
        if (!response.path("authorized").booleanValue()) {
            return result("UNAUTHORIZED", "Worker 可达，但 Telegram 会话未授权，请重新登录", source, List.of());
        }
        if (!"ok".equals(response.path("status").textValue()) || !response.path("stateWritable").booleanValue()) {
            return result("FAILED", "Worker 可达且会话已授权，但健康状态异常或状态目录不可写", source, List.of());
        }
        return result("OK", "Worker 可达、会话已授权且状态目录可写；未修改 Bot、收件箱或转发", source, List.of());
    }

    private Probe clouddrive(CloudDrive2Properties p, String source) {
        configured(p.getHost(), p.getApiToken());
        CloudDriveAccountClient client = new CloudDriveAccountClient();
        try {
            var actual = client.readResources(p);
            // 消费完整目录流后才返回成功；流中途失败不能被当作可读空目录。
            for (String path : Stream.concat(actual.clouds().stream().map(CloudDriveAccountClient.CloudView::path),
                    actual.mounts().stream().map(CloudDriveAccountClient.MountView::sourceDir))
                    .filter(StringUtils::hasText).distinct().toList()) {
                client.verifyDirectory(p, path);
            }
            List<Resource> resources = new ArrayList<>();
            for (var cloud : actual.clouds()) {
                Map<String, Object> fields = new LinkedHashMap<>(Map.of("type", "cloud", "locked", cloud.locked()));
                if (StringUtils.hasText(cloud.path())) {
                    fields.put("path", cloud.path()); fields.put("pathStatus", "OK");
                }
                resources.add(new Resource("cloud:" + (cloud.path() == null ? cloud.name() : cloud.path()), cloud.name(), fields));
            }
            for (var mount : actual.mounts()) {
                Map<String, Object> fields = new LinkedHashMap<>(Map.of("type", "mount", "mountPoint", mount.mountPoint(),
                        "sourceDir", mount.sourceDir(), "mounted", mount.mounted(), "readOnly", mount.readOnly()));
                if (StringUtils.hasText(mount.sourceDir())) fields.put("pathStatus", "OK");
                resources.add(new Resource("mount:" + mount.mountPoint(), mount.mountPoint(), fields));
            }
            resources.add(new Resource("license", "新增资源许可", Map.of("canAddCloud", actual.canAddCloud(), "canAddMount", actual.canAddMount())));
            return result("OK", "已读取 CD2 云盘、挂载、许可及返回路径；未创建挂载或修改文件，许可不代表写权限", source, resources);
        } catch (BusinessException failure) {
            throw accountReadFailure(failure);
        }
    }

    private Probe autosymlink(AutoSymlinkProperties p, String source) {
        configured(p.getBaseUrl(), p.getCookie());
        try {
            List<Resource> resources = new AutoSymlinkAccountClient(json).readTasks(p).stream()
                    .map(task -> new Resource(task.uuid(), task.taskName(), Map.of("uuid", task.uuid(),
                            "taskName", task.taskName(), "source", task.mediaDir(), "output", task.symlinkDir())))
                    .toList();
            return result("OK", "已读取 AS 任务身份、来源与输出；未创建或运行扫描任务", source, resources);
        } catch (BusinessException failure) {
            throw accountReadFailure(failure);
        }
    }

    private ProbeFailure accountReadFailure(BusinessException failure) {
        if (failure.getHttpStatus() == HttpStatus.UNAUTHORIZED || failure.getHttpStatus() == HttpStatus.FORBIDDEN) {
            return new ProbeFailure("UNAUTHORIZED", "上游拒绝认证或只读权限");
        }
        // adapter 尚未为 AS 页面兼容性提供独立错误码；仅识别其已知契约，不回显错误原文。
        String message = failure.getMessage();
        if (message.startsWith("CD2_UNIMPLEMENTED:") || List.of(
                "AS 任务列表不可读取，认证失效或页面版本不兼容",
                "AS 配置页面缺少必要路径或身份字段",
                "AS 配置缺少完整运行字段，当前版本不支持",
                "上游协议请求被拒绝，HTTP 404", "上游协议请求被拒绝，HTTP 405").contains(message)) {
            return unsupported();
        }
        return new ProbeFailure("FAILED", "上游资源或路径读取失败；请核对地址、凭据、权限与软件版本");
    }

    private JsonNode request(String base, String path, Duration timeout, Map<String, String> headers, JsonNode body) throws Exception {
        return json.readTree(text(base, path, timeout, headers, body));
    }

    private String text(String base, String path, Duration timeout, Map<String, String> headers, JsonNode body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base.replaceAll("/+$", "") + path)).timeout(timeout);
        headers.forEach(request::header);
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build()
                .send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        try (var input = response.body()) {
            if (response.statusCode() == 401 || response.statusCode() == 403) throw new ProbeFailure("UNAUTHORIZED", "上游拒绝认证或只读权限");
            if (response.statusCode() == 404 || response.statusCode() == 405) throw unsupported();
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new ProbeFailure("FAILED", "上游只读接口返回失败状态");
            byte[] bytes = input.readNBytes(5 * 1024 * 1024 + 1);
            if (bytes.length > 5 * 1024 * 1024) throw new ProbeFailure("FAILED", "只读响应超出检查大小限制");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private Map<String, Object> allow(JsonNode node, String... keys) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : keys) if (node.hasNonNull(key) && node.path(key).isValueNode()) result.put(key, json.convertValue(node.path(key), Object.class));
        return result;
    }
    private List<String> strings(JsonNode array) {
        List<String> result = new ArrayList<>();
        for (JsonNode value : array) if (value.isTextual()) result.add(value.asText());
        return result;
    }
    private void configured(String... values) {
        for (String value : values) if (!StringUtils.hasText(value)) throw new ProbeFailure("NOT_CONFIGURED", "此软件的必要连接或凭据尚未配置");
    }
    private void array(JsonNode value) { if (!value.isArray()) throw unsupported(); }
    private void apiSuccess(JsonNode response) {
        if (response.has("code") && response.path("code").asInt(-1) != 200 && response.path("code").asInt(-1) != 0) throw new ProbeFailure("FAILED", "上游只读接口未返回成功业务状态");
    }
    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private Probe result(String status, String message, String source, List<Resource> resources) { return new Probe(status, message, Instant.now().toString(), source, resources); }
    private ProbeFailure unsupported() { return new ProbeFailure("UNSUPPORTED", "上游版本未提供预期的只读接口或响应结构"); }
    private static final class ProbeFailure extends RuntimeException {
        private final String status;
        private ProbeFailure(String status, String message) { super(message); this.status = status; }
    }
}
