package com.medianexus.orchestrator.integration.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medianexus.orchestrator.config.OpenListProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.medianexus.orchestrator.common.exception.BusinessException;
import org.springframework.stereotype.Component;

/** 存储按确切资源身份读回，授权失败但已入库时仍保留实际 storage id。 */
@Component
public class OpenListAccountClient {
    private final ObjectMapper json;
    public OpenListAccountClient(ObjectMapper json) { this.json = json; }
    public record StorageView(long id, String mountPath, String driver, boolean healthy,
                              Long totalBytes, Long usedBytes, Long freeBytes) {}

    public List<StorageView> readStorages(OpenListProperties connection) {
        return storageRows(http(connection)).stream().map(this::view).toList();
    }

    public JsonNode readPikPakSchema(OpenListProperties connection) {
        return successful(http(connection).read("/api/admin/driver/info?driver=PikPak"));
    }

    public StorageView createAndRead(OpenListProperties connection, String targetId, ObjectNode protectedStorage) {
        OnboardingHttp http = http(connection);
        String mountPath = protectedStorage.path("mount_path").asText();
        Optional<StorageView> existing = findCreatedStorage(connection, targetId, mountPath);
        if (existing.isPresent()) { return existing.get(); }
        ObjectNode request = protectedStorage.deepCopy().put("remark", "medianexus-target:" + targetId).put("driver", "PikPak");
        // 失败响应也可能已经持久化；HTTP 超时、非 JSON 和未知响应都先读回，不重发创建。
        try { http.post("/api/admin/storage/create", request); }
        catch (BusinessException ignored) {
            return findCreatedStorage(connection, targetId, mountPath).orElseThrow(() ->
                    OnboardingHttp.failure("OpenList 创建结果未确定，需处理授权或读回后继续；未重复创建"));
        }
        return findCreatedStorage(connection, targetId, mountPath).orElseThrow(() ->
                OnboardingHttp.failure("OpenList 创建后尚无可核对的存储，未重复创建"));
    }

    /** 恢复未决 intent 仅查询；零匹配不能证明之前的创建未到达服务端。 */
    public Optional<StorageView> findCreatedStorage(OpenListProperties connection, String targetId, String mountPath) {
        List<JsonNode> matches = storageRows(http(connection)).stream()
                .filter(row -> mountPath.equals(row.path("mount_path").asText())).toList();
        if (matches.size() > 1) { throw OnboardingHttp.failure("OpenList 目标存储存在重复身份"); }
        if (matches.isEmpty()) { return Optional.empty(); }
        JsonNode row = matches.get(0);
        if (!("medianexus-target:" + targetId).equals(row.path("remark").asText()) || !"PikPak".equals(row.path("driver").asText())) {
            throw OnboardingHttp.failure("OpenList 目标路径已被其他资源占用，未覆盖");
        }
        return Optional.of(view(row));
    }

    public StorageView readStorage(OpenListProperties connection, long id) {
        return storageRows(http(connection)).stream().filter(row -> row.path("id").asLong() == id)
                .findFirst().map(this::view).orElseThrow(() -> OnboardingHttp.failure("OpenList 存储身份无法读回"));
    }

    /** 仅在加密计划中保留模板；不复制旧账号 addition 或状态。 */
    public ObjectNode readStorageTemplate(OpenListProperties connection, long id) {
        JsonNode source = successful(http(connection).read("/api/admin/storage/get?id=" + id));
        if (source.path("id").asLong() != id || !"PikPak".equals(source.path("driver").asText())) {
            throw OnboardingHttp.failure("OpenList 存储模板身份不匹配");
        }
        ObjectNode result = json.createObjectNode();
        for (String key : List.of("order", "cache_expiration", "custom_cache_policies", "disable_index", "enable_sign",
                "order_by", "order_direction", "extract_folder", "web_proxy", "webdav_policy", "proxy_range", "down_proxy_url", "disable_proxy_sign")) {
            if (source.has(key)) { result.set(key, source.get(key).deepCopy()); }
        }
        return result;
    }

    public void verifyDirectory(OpenListProperties connection, String path) {
        successful(http(connection).post("/api/fs/list", json.createObjectNode().put("path", path)
                .put("page", 1).put("per_page", 1).put("refresh", true).put("password", "")));
    }

    /** 从确切存储根逐段准备；以父目录完整列表证明缺失，不解析上游错误文字。 */
    public void ensureDirectory(OpenListProperties connection, String root, String path) {
        if (!path.equals(root) && !path.startsWith(root + "/")) { throw OnboardingHttp.failure("媒体目录不属于本次存储根"); }
        verifyDirectory(connection, root);
        String current = root;
        if (path.equals(root)) { return; }
        OnboardingHttp http = http(connection);
        for (String name : path.substring(root.length() + 1).split("/")) {
            boolean exists = false; int count = 0;
            for (int page = 1; ; page++) {
                JsonNode data = successful(http.post("/api/fs/list", json.createObjectNode().put("path", current)
                        .put("page", page).put("per_page", 100).put("refresh", true).put("password", "")));
                JsonNode children = data.path("content");
                if (!(children.isArray() || children.isNull()) || !data.path("total").canConvertToInt()) {
                    throw OnboardingHttp.failure("OpenList 目录列表协议不兼容");
                }
                for (JsonNode child : children) {
                    if (name.equals(child.path("name").asText())) {
                        if (!child.path("is_dir").asBoolean()) { throw OnboardingHttp.failure("媒体目录路径已被文件占用"); }
                        exists = true;
                    }
                }
                count += children.size();
                if (count >= data.path("total").asInt()) { break; }
                if (children.isEmpty()) { throw OnboardingHttp.failure("OpenList 目录列表未完整返回"); }
            }
            current += "/" + name;
            if (!exists) {
                try { http.post("/api/fs/mkdir", json.createObjectNode().put("path", current)); }
                catch (BusinessException ignored) { /* 目录创建响应不确定时只读回，不以错误文本当成功。 */ }
            }
            verifyDirectory(connection, current);
        }
    }

    private List<JsonNode> storageRows(OnboardingHttp http) {
        List<JsonNode> rows = new ArrayList<>();
        for (int page = 1; ; page++) {
            JsonNode data = successful(http.read("/api/admin/storage/list?page=" + page + "&per_page=100"));
            if (!data.path("content").isArray() || !data.path("total").canConvertToInt()) {
                throw OnboardingHttp.failure("OpenList 存储列表协议不兼容");
            }
            data.path("content").forEach(rows::add);
            if (rows.size() >= data.path("total").asInt()) { return List.copyOf(rows); }
            if (data.path("content").isEmpty()) { throw OnboardingHttp.failure("OpenList 分页未返回完整存储列表"); }
        }
    }
    private JsonNode successful(JsonNode root) {
        if (root.path("code").asInt() != 200) { throw OnboardingHttp.failure("OpenList 管理读取失败，请检查授权和版本"); }
        return root.path("data");
    }
    private StorageView view(JsonNode row) {
        JsonNode capacity = row.path("mount_details");
        return new StorageView(row.path("id").asLong(), row.path("mount_path").asText(), row.path("driver").asText(),
                !row.path("disabled").asBoolean() && "work".equals(row.path("status").asText()),
                number(capacity, "total_space"), number(capacity, "used_space"), number(capacity, "free_space"));
    }
    private Long number(JsonNode node, String key) { return node.path(key).canConvertToLong() ? node.path(key).longValue() : null; }
    private OnboardingHttp http(OpenListProperties p) {
        if (!org.springframework.util.StringUtils.hasText(p.getBaseUrl()) || !org.springframework.util.StringUtils.hasText(p.getAuthorization())) {
            throw OnboardingHttp.failure("OpenList 有效地址或管理认证未配置");
        }
        return new OnboardingHttp(p.getBaseUrl(), Map.of("Authorization", p.getAuthorization()), p.getTimeout(), json);
    }
}
