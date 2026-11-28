package com.medianexus.orchestrator.integration.smartstrm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medianexus.orchestrator.integration.onboarding.AutoSymlinkAccountClient.TaskView;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 旧表单仅作为迁移元数据；每次读回必须核验真实 storage/task/输出，不能用元数据掩盖外部修改。 */
@Component
public class SmartStrmAccountClient {
    private final SmartStrmClient client;
    private final SmartStrmOutputs outputs;
    private final ObjectMapper json;
    public SmartStrmAccountClient(SmartStrmClient client, SmartStrmOutputs outputs, ObjectMapper json) {
        this.client = client; this.outputs = outputs; this.json = json;
    }
    public boolean isEnabled() { return client.isEnabled(); }

    public List<TaskView> readTasks() {
        List<TaskView> result = new ArrayList<>();
        JsonNode tasks = taskMap();
        tasks.fieldNames().forEachRemaining(uuid -> {
            // 非迁移任务无需伪造成 AS 任务；带迁移元数据的任务必须完整通过核验。
            if (tasks.path(uuid).path("config").has("legacy_form")) {
                ObjectNode form = verified(uuid, tasks.path(uuid).path("config"));
                result.add(new TaskView(uuid, form.path("task_name").asText(), form.path("media_dir").asText(), form.path("symlink_dir").asText()));
            }
        });
        return List.copyOf(result);
    }

    /** 调度仅枚举迁移引用，配置核验在各任务内部执行，单项漂移不阻断其他任务。 */
    public List<String> migratedTaskIds() {
        List<String> ids = new ArrayList<>();
        taskMap().fields().forEachRemaining(entry -> {
            if (entry.getValue().path("config").has("legacy_form")) ids.add(entry.getKey());
        });
        return List.copyOf(ids);
    }

    public ObjectNode readConfiguration(String uuid) {
        SmartStrmOutputs.requireUuid(uuid);
        return verified(uuid, taskMap().path(uuid).path("config"));
    }

    public Optional<String> findCreatedUuid(ObjectNode expected) {
        List<String> matches = new ArrayList<>();
        for (TaskView view : readTasks()) {
            ObjectNode actual = readConfiguration(view.uuid());
            boolean overlap = actual.path("media_dir").equals(expected.path("media_dir"))
                    || actual.path("symlink_dir").equals(expected.path("symlink_dir"));
            if (!overlap) continue;
            if (!actual.equals(expected)) throw SmartStrmClient.failure("SmartStrm 同路径任务配置冲突，未覆盖已有任务");
            matches.add(view.uuid());
        }
        if (matches.size() > 1) throw SmartStrmClient.failure("SmartStrm 存在多个匹配任务");
        return matches.stream().findFirst();
    }

    public String createAndReadUuid(ObjectNode form) {
        Optional<String> existing = findCreatedUuid(form);
        if (existing.isPresent()) return existing.get();
        String uuid = UUID.randomUUID().toString();
        ObjectNode config = taskConfiguration(uuid, form);
        outputs.prepare(uuid, form.path("symlink_dir").asText());
        client.saveStorage(uuid, storageConfiguration(uuid, form));
        // 任一写入异常直接退出，后续 findCreatedUuid 只读核对，不重复创建。
        client.saveTask(uuid, config);
        if (!readConfiguration(uuid).equals(form)) throw SmartStrmClient.failure("SmartStrm 创建后配置无法一致读回");
        return uuid;
    }

    public void verifyReadableDirectory(String path) {
        requireAbsoluteDirectory(path);
        if (outputs.verifyDirectory(path)) return;
        for (TaskView task : readTasks()) {
            if (path.equals(task.symlinkDir()) || path.startsWith(task.symlinkDir() + "/")) {
                java.nio.file.Path output = outputs.verify(task.uuid(), task.symlinkDir()).resolve(path.substring(task.symlinkDir().length()).replaceFirst("^/", ""));
                if (!java.nio.file.Files.isDirectory(output)) throw SmartStrmClient.failure("SmartStrm 输出目录不可读取");
                return;
            }
        }
        JsonNode storages = client.storages();
        for (JsonNode storage : storageEntries(storages)) {
            String root = storage.path("root_path").asText();
            if (storage.path("driver").asText().equals("local") && !root.isBlank()
                    && (path.equals(root) || path.startsWith(root + "/"))) {
                JsonNode listing = client.browse(storage.path("name").asText(), path.equals(root) ? "/" : path.substring(root.length()));
                if (!listing.isArray() && !listing.path("files").isArray() && !listing.path("items").isArray())
                    throw SmartStrmClient.failure("SmartStrm 目录读取协议未核实");
                return;
            }
        }
        throw SmartStrmClient.failure("SmartStrm 未找到可读取此目录的本地存储");
    }

    public ObjectNode taskConfiguration(String uuid, ObjectNode form) {
        SmartStrmOutputs.requireUuid(uuid);
        if (!form.path("symlink_mode").asText().equals("strm") || !form.path("strm_mode").asText().equals("local"))
            throw SmartStrmClient.failure("SmartStrm 仅支持已核实的本地 STRM 表单；cloud 模式尚不支持");
        requireAbsoluteDirectory(form.path("media_dir").asText());
        requireAbsoluteDirectory(form.path("symlink_dir").asText());
        if (!form.path("exclude_folder").asText().isBlank()) throw SmartStrmClient.failure("SmartStrm 尚未核实排除目录映射");
        ObjectNode config = json.createObjectNode().put("name", uuid).put("storage", uuid).put("storage_path", "/")
                .put("crontab", "")
                .put("incremental", true).put("dir_time_check", false).put("keep_local_asset", true).put("watchdog", false);
        ObjectNode plugins = config.putObject("plugins");
        plugins.putObject("custom_strm_name").put("enabled", true).put("custom_name", "{name}.strm");
        plugins.set("scan_filter", scanFilter(form));
        config.set("legacy_form", form.deepCopy());
        return config;
    }

    public ObjectNode storageConfiguration(String uuid, ObjectNode form) {
        return json.createObjectNode().put("name", uuid).put("driver", "local")
                .put("root_path", form.path("media_dir").asText()).put("compatibility", true);
    }

    private ObjectNode verified(String uuid, JsonNode config) {
        if (!config.path("legacy_form").isObject()) throw SmartStrmClient.failure("SmartStrm 缺少可核验的 legacy_form 迁移元数据");
        ObjectNode form = (ObjectNode) config.path("legacy_form");
        ObjectNode expected = taskConfiguration(uuid, form);
        expected.fields().forEachRemaining(field -> {
            // HTTP JSON 往返会把小整数解析成 IntNode，不能把同值 LongNode 误判为配置漂移。
            if (!field.getValue().equals((left, right) -> left.isIntegralNumber() && right.isIntegralNumber()
                    ? left.bigIntegerValue().compareTo(right.bigIntegerValue()) : left.equals(right) ? 0 : 1,
                    config.get(field.getKey()))) throw SmartStrmClient.failure("SmartStrm 实际任务配置已偏离迁移表单");
        });
        JsonNode storage = storageEntries(client.storages()).stream().filter(item -> item.path("name").asText().equals(uuid))
                .findFirst().orElseThrow(() -> SmartStrmClient.failure("SmartStrm 本地存储不存在"));
        storageConfiguration(uuid, form).fields().forEachRemaining(field -> {
            if (!field.getValue().equals(storage.get(field.getKey()))) throw SmartStrmClient.failure("SmartStrm 实际本地存储已偏离迁移表单");
        });
        outputs.verify(uuid, form.path("symlink_dir").asText());
        return form.deepCopy();
    }

    private JsonNode taskMap() {
        JsonNode tasks = client.tasks();
        if (!tasks.isObject()) throw SmartStrmClient.failure("SmartStrm 任务列表协议无效");
        return tasks;
    }
    private List<JsonNode> storageEntries(JsonNode storages) {
        if (!storages.isArray()) throw SmartStrmClient.failure("SmartStrm 存储列表协议未核实");
        List<JsonNode> entries = new ArrayList<>(); storages.forEach(entries::add); return entries;
    }
    /** 空扩展名会回退全局配置；显式哨兵保证禁用操作不会扩大为全局复制／生成。 */
    ObjectNode scanFilter(ObjectNode form) {
        String size = form.path("symlink_size").asText("0");
        if (!size.matches("[0-9]+")) throw SmartStrmClient.failure("SmartStrm 媒体最小大小配置无效");
        long minimum;
        try { minimum = Long.parseLong(size); }
        catch (NumberFormatException invalid) { throw SmartStrmClient.failure("SmartStrm 媒体最小大小配置无效"); }
        return json.createObjectNode().put("enabled", true).put("media_size_min", minimum)
                .put("media_size_max", "").put("copy_size_min", "").put("copy_size_max", "")
                .put("media_ext", form.path("symlink_creator").asBoolean() ? suffixes(form.path("symlink_ext").asText(), "medianexus-no-media") : "medianexus-no-media")
                .put("copy_ext", form.path("metadata_copyer").asBoolean() ? suffixes(form.path("metadata_ext").asText(), "medianexus-no-copy") : "medianexus-no-copy");
    }

    private String suffixes(String extensions, String empty) {
        String result = java.util.Arrays.stream(extensions.split("[,;\\s]+"))
                .map(value -> value.replaceFirst("^\\.", "")).filter(value -> !value.isBlank()).distinct()
                .collect(java.util.stream.Collectors.joining(","));
        return result.isEmpty() ? empty : result;
    }

    private static void requireAbsoluteDirectory(String value) {
        try {
            java.nio.file.Path path = java.nio.file.Path.of(value);
            if (!path.isAbsolute() || !path.normalize().equals(path) || !path.toString().equals(value))
                throw SmartStrmClient.failure("SmartStrm 目录必须是规范绝对路径");
        } catch (java.nio.file.InvalidPathException invalid) { throw SmartStrmClient.failure("SmartStrm 目录格式无效"); }
    }
}
