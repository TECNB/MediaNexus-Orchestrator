package com.medianexus.orchestrator.integration.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medianexus.orchestrator.config.AutoSymlinkProperties;
import com.medianexus.orchestrator.common.exception.BusinessException;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import org.springframework.stereotype.Component;

/** 真实任务列表与 HTML form 的版本适配；写接口与读取接口分开，不伪造创建 UUID。 */
@Component
public class AutoSymlinkAccountClient {
    private static final Pattern TASK_LINK = Pattern.compile("/sync_list/([0-9a-fA-F-]{36})");
    private static final Set<String> RUN_FIELDS = Set.of("task_name", "media_dir", "symlink_creator", "metadata_copyer",
            "num_threads", "symlink_dir_checker", "symlink_checker", "metadata_checker", "symlink_size", "symlink_ext", "metadata_ext");
    static final Set<String> CONFIG_STRINGS = Set.of("task_name", "media_dir", "symlink_dir", "exclude_folder", "sync_time",
            "metadata_copyer_mode", "num_threads", "observer_time", "backup_time", "backup_ext", "symlink_mode", "symlink_size",
            "strm_mode", "cloud_type", "cloud_url", "clouddrive2_path", "alist_path", "symlink_ext", "metadata_ext", "sign_file", "sign_file_url");
    static final Set<String> CONFIG_BOOLEANS = Set.of("sync_enabled", "restart_sync_enabled", "sync_scheduled", "symlink_creator",
            "metadata_copyer", "metadata_covered", "metadata_skipped", "symlink_dir_checker", "symlink_checker", "metadata_checker",
            "observer_enabled", "observer_symlink_creator", "observer_metadata_copyer", "observer_symlink_checker", "observer_metadata_checker",
            "backup_scheduled", "cloud_checker");
    private final ObjectMapper json;

    public AutoSymlinkAccountClient(ObjectMapper json) { this.json = json; }

    public record TaskView(String uuid, String taskName, String mediaDir, String symlinkDir) {}
    public record TaskPaths(String taskName, String mediaDir, String symlinkDir, String sourcePrefix) {}

    /** 当前已取证的 CD2/local STRM 表单；通用参数保持，来源/输出/本地挂载根作为一组替换。 */
    public ObjectNode configurationForNewTarget(ObjectNode source, TaskPaths paths) {
        if (!source.path("cloud_type").asText().equals("cd2") || !source.path("symlink_mode").asText().equals("strm")
                || !source.path("strm_mode").asText().equals("local") || source.path("cloud_checker").asBoolean(true)
                || !source.path("sign_file").asText().isEmpty() || !source.path("sign_file_url").asText().isEmpty()
                || !source.path("alist_path").asText().isEmpty()) {
            throw OnboardingHttp.failure("AS 模板不属于已核实的 CD2/local STRM 版本，或包含需要重新准备的掉盘探针");
        }
        if (!paths.mediaDir().startsWith(paths.sourcePrefix() + "/")) {
            throw OnboardingHttp.failure("AS 新扫描路径不在新目标挂载根内");
        }
        String cloudUrl = source.path("cloud_url").asText();
        if (!cloudUrl.isBlank()) {
            try {
                java.net.URI uri = java.net.URI.create(cloudUrl);
                String path = uri.getPath();
                if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                        || path == null || !(path.isEmpty() || path.equals("/") || path.replaceAll("/+$", "").endsWith("/False"))) {
                    throw OnboardingHttp.failure("AS cloud_url 不是已核实的通用服务根，不能复制为新账号映射");
                }
            } catch (IllegalArgumentException exception) { throw OnboardingHttp.failure("AS cloud_url 格式不支持"); }
        }
        ObjectNode copy = configurationFields(source);
        copy.put("task_name", paths.taskName()).put("media_dir", paths.mediaDir()).put("symlink_dir", paths.symlinkDir())
                .put("clouddrive2_path", paths.sourcePrefix());
        manualRequest(copy);
        return copy;
    }

    public List<TaskView> readTasks(AutoSymlinkProperties connection) {
        OnboardingHttp http = http(connection);
        List<TaskView> tasks = new ArrayList<>();
        for (String uuid : taskIds(http.readText("/"))) {
            ObjectNode form = readConfiguration(http, uuid);
            tasks.add(new TaskView(uuid, required(form, "task_name"), required(form, "media_dir"), required(form, "symlink_dir")));
        }
        return List.copyOf(tasks);
    }

    /** 目录选择器在 AS 命名空间读取父目录和目录自身，不能用后端 Files.exists 代替。 */
    public void verifyReadableDirectory(AutoSymlinkProperties connection, String path) {
        OnboardingHttp http = http(connection);
        String parent = path.substring(0, path.lastIndexOf('/'));
        JsonNode siblings = http.post("/get_sub_dir", json.createObjectNode().put("folder_path", parent.isEmpty() ? "/" : parent));
        if (!siblings.isArray()) { throw OnboardingHttp.unavailable("AS_DIRECTORY_SCHEMA_UNSUPPORTED: AS 目录读取协议尚未核实"); }
        boolean found = false;
        for (JsonNode child : siblings) {
            if (child.path("id").isTextual() && child.path("id").asText().equals(path)) { found = true; }
        }
        if (!found) { throw OnboardingHttp.failure("AS 容器内未读回计划目录，需核对挂载传播"); }
        if (!http.post("/get_sub_dir", json.createObjectNode().put("folder_path", path)).isArray()) {
            throw OnboardingHttp.failure("AS 容器内目录当前无法读取");
        }
    }

    /** 仅在服务端使用；完整配置可能含 cloud_url/sign_file_url，禁止直接作为管理响应。 */
    public ObjectNode readConfiguration(AutoSymlinkProperties connection, String uuid) {
        requireUuid(uuid);
        return readConfiguration(http(connection), uuid);
    }

    public String createAndReadUuid(AutoSymlinkProperties connection, ObjectNode protectedConfiguration) {
        OnboardingHttp http = http(connection);
        JsonNode required = http.read("/openapi.json").path("components").path("schemas").path("SyncConfigFormData").path("required");
        Set<String> actualRequired = new LinkedHashSet<>();
        required.forEach(field -> actualRequired.add(field.asText()));
        Set<String> expected = new LinkedHashSet<>(CONFIG_STRINGS); expected.addAll(CONFIG_BOOLEANS);
        if (!required.isArray() || !actualRequired.equals(expected)) { throw OnboardingHttp.failure("AS 创建 schema 与已核实版本不兼容"); }
        ObjectNode configuration = configurationFields(protectedConfiguration);
        Optional<String> existing = find(http, configuration);
        if (existing.isPresent()) { return existing.get(); }
        try { http.post("/add_sync", configuration); }
        catch (BusinessException ignored) {
            return find(http, configuration).orElseThrow(() ->
                    OnboardingHttp.failure("AS 创建结果未确定，需读回核对真实任务；未重复提交"));
        }
        return find(http, configuration).orElseThrow(() ->
                OnboardingHttp.failure("AS 创建响应已返回但任务尚无法唯一读回，继续前请核对资源"));
    }

    public Optional<String> findCreatedUuid(AutoSymlinkProperties connection, ObjectNode protectedConfiguration) {
        return find(http(connection), configurationFields(protectedConfiguration));
    }

    private ObjectNode configurationFields(ObjectNode source) {
        ObjectNode result = json.createObjectNode();
        for (String field : CONFIG_STRINGS) {
            if (!source.path(field).isTextual()) { throw OnboardingHttp.failure("AS 完整创建表单缺少字符串字段"); }
            result.set(field, source.get(field).deepCopy());
        }
        for (String field : CONFIG_BOOLEANS) {
            if (!source.path(field).isBoolean()) { throw OnboardingHttp.failure("AS 完整创建表单缺少布尔字段"); }
            result.set(field, source.get(field).deepCopy());
        }
        return result;
    }

    public ObjectNode manualRequest(ObjectNode configuration) {
        ObjectNode result = json.createObjectNode();
        for (String field : RUN_FIELDS) {
            JsonNode value = configuration.get(field);
            if (value == null) { throw OnboardingHttp.failure("AS 配置缺少完整运行字段，当前版本不支持"); }
            result.set(field, value.deepCopy());
        }
        return result;
    }

    private Optional<String> find(OnboardingHttp http, ObjectNode expected) {
        List<String> matches = new ArrayList<>();
        for (String uuid : taskIds(http.readText("/"))) {
            ObjectNode actual = readConfiguration(http, uuid);
            if ((required(actual, "media_dir").equals(required(expected, "media_dir"))
                    || required(actual, "symlink_dir").equals(required(expected, "symlink_dir")))
                    && !required(actual, "task_name").equals(required(expected, "task_name"))) {
                throw OnboardingHttp.failure("AS 来源或输出已由其他任务使用，未创建重叠任务");
            }
            if (required(actual, "media_dir").equals(required(expected, "media_dir"))
                    && required(actual, "symlink_dir").equals(required(expected, "symlink_dir"))
                    && required(actual, "task_name").equals(required(expected, "task_name"))) {
                // 读回全部配置字段，而非仅按名字复用。
                var fields = expected.fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    if (!entry.getKey().equals("isNew") && !entry.getValue().equals(actual.get(entry.getKey()))) {
                        throw OnboardingHttp.failure("AS 同路径任务配置冲突，未覆盖已有任务");
                    }
                }
                matches.add(uuid);
            }
        }
        if (matches.size() > 1) { throw OnboardingHttp.failure("AS 存在多个匹配任务，无法安全复用"); }
        return matches.stream().findFirst();
    }

    private ObjectNode readConfiguration(OnboardingHttp http, String uuid) {
        ObjectNode form = parseForm(http.readText("/sync_list/" + uuid));
        required(form, "task_name"); required(form, "media_dir"); required(form, "symlink_dir");
        manualRequest(form);
        return form;
    }

    private Set<String> taskIds(String page) {
        Set<String> ids = new LinkedHashSet<>();
        var matcher = TASK_LINK.matcher(page);
        while (matcher.find()) { ids.add(matcher.group(1)); }
        // 登录页不能被解释成“连接成功、零任务”。
        if (ids.isEmpty() && !page.contains("/add_sync")) {
            throw OnboardingHttp.failure("AS 任务列表不可读取，认证失效或页面版本不兼容");
        }
        return ids;
    }

    ObjectNode parseForm(String page) {
        ObjectNode result = json.createObjectNode();
        try {
            new ParserDelegator().parse(new StringReader(page), new HTMLEditorKit.ParserCallback() {
                String select;
                String textarea;
                String optionValue;
                boolean optionSelected;
                boolean firstOption;
                StringBuilder text = new StringBuilder();
                @Override public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attrs, int position) {
                    if (tag == HTML.Tag.INPUT) {
                        String name = attribute(attrs, HTML.Attribute.NAME);
                        if (name == null) { return; }
                        if ("checkbox".equals(attribute(attrs, HTML.Attribute.TYPE))) {
                            result.put(name, attrs.isDefined(HTML.Attribute.CHECKED));
                        } else { result.put(name, attribute(attrs, HTML.Attribute.VALUE, "")); }
                    }
                }
                @Override public void handleStartTag(HTML.Tag tag, MutableAttributeSet attrs, int position) {
                    if (tag == HTML.Tag.SELECT) { select = attribute(attrs, HTML.Attribute.NAME); firstOption = true; }
                    if (tag == HTML.Tag.OPTION && select != null) {
                        optionValue = attribute(attrs, HTML.Attribute.VALUE);
                        optionSelected = attrs.isDefined(HTML.Attribute.SELECTED); text.setLength(0);
                    }
                    if (tag == HTML.Tag.TEXTAREA) { textarea = attribute(attrs, HTML.Attribute.NAME); text.setLength(0); }
                }
                @Override public void handleText(char[] chars, int position) { text.append(chars); }
                @Override public void handleEndTag(HTML.Tag tag, int position) {
                    if (tag == HTML.Tag.OPTION && select != null) {
                        if (firstOption || optionSelected) { result.put(select, optionValue == null ? text.toString().trim() : optionValue); }
                        firstOption = false;
                    }
                    if (tag == HTML.Tag.SELECT) { select = null; }
                    if (tag == HTML.Tag.TEXTAREA && textarea != null) { result.put(textarea, text.toString()); textarea = null; }
                }
            }, true);
        } catch (IOException exception) { throw OnboardingHttp.failure("AS 配置页面无法读取"); }
        return result;
    }

    private String required(ObjectNode form, String name) {
        if (!form.path(name).isTextual() || form.path(name).textValue().isBlank()) {
            throw OnboardingHttp.failure("AS 配置页面缺少必要路径或身份字段");
        }
        return form.path(name).textValue();
    }
    private void requireUuid(String uuid) {
        if (uuid == null || !uuid.matches("[0-9a-fA-F-]{36}")) { throw OnboardingHttp.failure("AS 任务引用格式无效"); }
    }
    private OnboardingHttp http(AutoSymlinkProperties p) {
        if (p.isDisabled()) { throw OnboardingHttp.unavailable("AS_DISABLED: AutoSymlink 已停用，请先启用并使配置生效"); }
        if (!org.springframework.util.StringUtils.hasText(p.getBaseUrl()) || !org.springframework.util.StringUtils.hasText(p.getCookie())) {
            throw OnboardingHttp.unavailable("AS_NOT_CONFIGURED: AS 有效地址或认证未配置");
        }
        return new OnboardingHttp(p.getBaseUrl(), Map.of("Cookie", p.getCookie()), p.getTimeout(), json);
    }
    private static String attribute(MutableAttributeSet attrs, HTML.Attribute key) { Object value = attrs.getAttribute(key); return value == null ? null : value.toString(); }
    private static String attribute(MutableAttributeSet attrs, HTML.Attribute key, String fallback) { String value = attribute(attrs, key); return value == null ? fallback : value; }
}
