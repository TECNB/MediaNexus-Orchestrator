package com.medianexus.orchestrator.integration.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medianexus.orchestrator.config.EmbyProperties;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 既有库只追加目录，绝不将客户端缓存的 LibraryOptions 重发覆盖服务端。 */
@Component
public class EmbyAccountLibraryClient {
    private final ObjectMapper json;
    public EmbyAccountLibraryClient(ObjectMapper json) { this.json = json; }
    public record LibraryView(String id, String name, List<String> locations) {}

    public List<LibraryView> readLibraries(EmbyProperties connection) {
        List<LibraryView> result = new ArrayList<>();
        for (JsonNode library : libraries(http(connection))) { result.add(view(library)); }
        return List.copyOf(result);
    }

    /** 只调用当前实例自描述的只读路径验证，不启用 ValidateWriteable 或推测旧版本支持。 */
    public void verifyReadableDirectory(EmbyProperties connection, String path) {
        OnboardingHttp http = http(connection);
        JsonNode schema = http.read("/openapi.json");
        JsonNode operation = schema.path("paths").path("/Environment/ValidatePath").path("post");
        JsonNode body = operation.path("requestBody").path("content").path("application/json").path("schema");
        if (body.path("$ref").isTextual()) {
            String reference = body.path("$ref").asText();
            body = reference.startsWith("#/") ? schema.at(reference.substring(1)) : json.createObjectNode();
        }
        JsonNode fields = body.path("properties");
        if (!fields.path("Path").path("type").asText().equals("string")
                || !fields.path("ValidateWriteable").path("type").asText().equals("boolean")
                || !fields.path("IsFile").path("type").asText().equals("boolean")) {
            throw OnboardingHttp.unavailable("EMBY_PATH_VALIDATION_UNSUPPORTED: 当前 Emby 未公开已核实的只读路径验证协议");
        }
        http.post("/Environment/ValidatePath", json.createObjectNode().put("Path", path).put("ValidateWriteable", false).put("IsFile", false));
    }

    public LibraryView appendAndVerify(EmbyProperties connection, String libraryId, String path) {
        OnboardingHttp http = http(connection);
        JsonNode before = library(http, libraryId);
        JsonNode beforeOptions = nonPathOptions(before);
        LibraryView old = view(before);
        long count = old.locations().stream().filter(path::equals).count();
        if (count > 1) { throw OnboardingHttp.failure("Emby 目录已重复，请先核对已有库配置"); }
        if (count == 0) {
            ObjectNode request = json.createObjectNode().put("Id", libraryId).put("Path", path).put("RefreshLibrary", false);
            http.post("/Library/VirtualFolders/Paths", request);
        }
        JsonNode after = library(http, libraryId);
        LibraryView current = view(after);
        if (current.locations().stream().filter(path::equals).count() != 1
                || !new HashSet<>(current.locations()).containsAll(old.locations())) {
            throw OnboardingHttp.failure("Emby 追加目录尚未通过完整读回核对");
        }
        if (!beforeOptions.equals(nonPathOptions(after))) {
            throw OnboardingHttp.failure("Emby 非路径库选项在执行期间变化，请重新核对；未覆盖该变化");
        }
        for (JsonNode previous : before.path("LibraryOptions").path("PathInfos")) {
            boolean retained = false;
            for (JsonNode candidate : after.path("LibraryOptions").path("PathInfos")) {
                if (previous.equals(candidate)) { retained = true; break; }
            }
            if (!retained) { throw OnboardingHttp.failure("Emby 原有路径附加选项发生变化，请重新核对"); }
        }
        return current;
    }

    private JsonNode nonPathOptions(JsonNode library) {
        JsonNode options = library.path("LibraryOptions");
        if (!options.isObject()) { throw OnboardingHttp.failure("Emby 未返回完整库选项，不能确认保留契约"); }
        ObjectNode copy = ((ObjectNode) options).deepCopy();
        copy.remove("PathInfos");
        return copy;
    }
    private JsonNode library(OnboardingHttp http, String id) {
        List<JsonNode> matches = new ArrayList<>();
        for (JsonNode library : libraries(http)) {
            if (view(library).id().equals(id)) { matches.add(library); }
        }
        if (matches.size() != 1) { throw OnboardingHttp.failure("Emby 库身份不存在或无法唯一读回"); }
        return matches.get(0);
    }
    private JsonNode libraries(OnboardingHttp http) {
        JsonNode root = http.read("/Library/VirtualFolders/Query");
        if (!root.path("Items").isArray() || root.path("TotalRecordCount").asInt(-1) != root.path("Items").size()) {
            throw OnboardingHttp.failure("Emby 库列表未完整返回，当前分页协议需要核对");
        }
        return root.path("Items");
    }
    private LibraryView view(JsonNode library) {
        String id = library.path("ItemId").asText(library.path("Id").asText(""));
        if (id.isBlank() || !library.path("Locations").isArray()) { throw OnboardingHttp.failure("Emby 库结构不兼容"); }
        List<String> paths = new ArrayList<>();
        library.path("Locations").forEach(path -> paths.add(path.asText()));
        return new LibraryView(id, library.path("Name").asText(), List.copyOf(paths));
    }
    private OnboardingHttp http(EmbyProperties p) {
        if (!org.springframework.util.StringUtils.hasText(p.getBaseUrl()) || !org.springframework.util.StringUtils.hasText(p.getApiKey())) {
            throw OnboardingHttp.failure("Emby 有效地址或认证未配置");
        }
        return new OnboardingHttp(p.getBaseUrl(), Map.of("X-Emby-Token", p.getApiKey()), p.getTimeout(), json);
    }
}
