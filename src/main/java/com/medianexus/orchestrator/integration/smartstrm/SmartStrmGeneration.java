package com.medianexus.orchestrator.integration.smartstrm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medianexus.orchestrator.config.SmartStrmProperties;
import com.medianexus.orchestrator.integration.emby.EmbyClient;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 提交与完成分离：任务结束后核验指定输出子树，通知精确路径；写入与通知均不重放。 */
@Component
public class SmartStrmGeneration {
    private static final Logger log = LoggerFactory.getLogger(SmartStrmGeneration.class);
    private final SmartStrmClient client;
    private final SmartStrmAccountClient accounts;
    private final SmartStrmOutputs outputs;
    private final SmartStrmProperties properties;
    private final EmbyClient emby;
    private final ScheduledExecutorService observer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "smartstrm-completion"); thread.setDaemon(true); return thread;
    });
    private final Map<String, Observation> pending = new ConcurrentHashMap<>();

    public SmartStrmGeneration(SmartStrmClient client, SmartStrmAccountClient accounts, SmartStrmOutputs outputs,
            SmartStrmProperties properties, EmbyClient emby) {
        this.client = client; this.accounts = accounts; this.outputs = outputs; this.properties = properties; this.emby = emby;
    }
    public boolean isEnabled() { return client.isEnabled(); }

    public void submit(String uuid, JsonNode request) {
        if (request == null || !request.isObject()) throw SmartStrmClient.failure("SmartStrm 运行请求必须是 JSON 对象");
        ObjectNode form = accounts.readConfiguration(uuid);
        String root = form.path("media_dir").asText().replaceAll("/+$", "");
        String media = request.path("media_dir").asText();
        if (!media.equals(root) && !media.startsWith(root + "/")) throw SmartStrmClient.failure("SmartStrm 扫描路径超出原任务根");
        String relative = media.substring(root.length());
        if (java.util.Arrays.stream(relative.split("/")).anyMatch(part -> part.equals("..") || part.equals(".")))
            throw SmartStrmClient.failure("SmartStrm 扫描路径不能包含相对跳转");
        if (!Path.of(media).isAbsolute() || !Path.of(media).toString().equals(media))
            throw SmartStrmClient.failure("SmartStrm 扫描路径格式无效");
        ObjectNode runForm = form.deepCopy();
        // 字幕上传可临时启用元数据复制，不修改已核验的任务基础配置。
        for (String field : List.of("symlink_creator", "metadata_copyer")) {
            if (request.has(field)) {
                if (!request.path(field).isBoolean()) throw SmartStrmClient.failure("SmartStrm 运行开关必须为布尔值");
                runForm.set(field, request.get(field));
            }
        }
        for (String field : List.of("symlink_ext", "metadata_ext", "symlink_size")) {
            if (request.has(field)) {
                if (!request.path(field).isTextual()) throw SmartStrmClient.failure("SmartStrm 运行过滤配置必须为字符串");
                runForm.set(field, request.get(field));
            }
        }
        ObjectNode task = runForm.objectNode().put("name", uuid).put("storage_path", relative.isEmpty() ? "/" : relative)
                .put("incremental", true).put("keep_local_asset", true).put("dir_time_check", false);
        task.set("plugins", accounts.taskConfiguration(uuid, runForm).path("plugins"));
        start(uuid, form, task);
    }

    /** Java 是迁移任务唯一调度者；不接管无 legacy_form 的 Quark 等既有任务。 */
    @Scheduled(cron = "${medianexus.smartstrm.sync-cron}", zone = "${medianexus.smartstrm.sync-zone}")
    public void synchronizeAll() {
        if (!isEnabled()) return;
        for (String uuid : accounts.migratedTaskIds()) {
            try {
                ObjectNode form = accounts.readConfiguration(uuid);
                if (!form.path("sync_enabled").asBoolean() || !form.path("sync_scheduled").asBoolean()) continue;
                ObjectNode task = form.objectNode().put("name", uuid).put("storage_path", "/")
                        .put("incremental", false).put("keep_local_asset", true).put("dir_time_check", false);
                task.set("plugins", accounts.taskConfiguration(uuid, form).path("plugins"));
                start(uuid, form, task);
            } catch (RuntimeException unavailable) {
                log.warn("SmartStrm scheduled synchronization unavailable; no write replayed");
            }
        }
    }

    private void start(String uuid, ObjectNode form, ObjectNode task) {
        if (properties.getCompletionTimeout() == null || properties.getCompletionTimeout().isNegative() || properties.getCompletionTimeout().isZero()
                || properties.getPollInterval() == null || properties.getPollInterval().toMillis() < 1)
            throw SmartStrmClient.failure("SmartStrm 完成观察时间配置无效");
        Path output = outputs.verify(uuid, form.path("symlink_dir").asText())
                .resolve(task.path("storage_path").asText().replaceFirst("^/", ""));
        synchronized (pending) {
            // 同名任务共享一个观察者；多个路径运行排队时合并核验范围，不丢弃后续字幕／入库请求。
            Map<String, String> before = snapshot(output);
            String baselineLog = client.taskLog(uuid);
            Observation observation = pending.get(uuid);
            if (observation == null) {
                if (pending.size() >= 128) throw SmartStrmClient.failure("SmartStrm 完成观察容量已满，未提交");
                observation = new Observation(System.nanoTime() + properties.getCompletionTimeout().toNanos(), baselineLog);
                pending.put(uuid, observation);
            }
            if (!observation.before.containsKey(output) && observation.before.size() >= 128)
                throw SmartStrmClient.failure("SmartStrm 完成观察路径容量已满，未提交");
            try {
                observation.before.putIfAbsent(output, before);
                observation.idlePolls = 0;
                observation.lastIdle = null;
                observation.submissions++;
                client.run(task);
            } finally {
                if (!observation.scheduled) { observation.scheduled = true; schedule(uuid); }
            }
        }
    }

    private void schedule(String uuid) {
        observer.schedule(() -> poll(uuid), properties.getPollInterval().toMillis(), TimeUnit.MILLISECONDS);
    }

    private void poll(String uuid) {
        synchronized (pending) { pollTask(uuid); }
    }

    private void pollTask(String uuid) {
        Observation observation = pending.get(uuid);
        if (observation == null) return;
        try {
            JsonNode running = client.tasks().path(uuid).path("running");
            if (!running.isBoolean()) throw SmartStrmClient.failure("SmartStrm 运行状态无法核验");
            if (running.asBoolean()) {
                observation.idlePolls = 0;
                observation.lastIdle = null;
            } else {
                Map<String, String> current = new HashMap<>();
                observation.before.keySet().forEach(output -> current.putAll(snapshot(output)));
                observation.idlePolls = current.equals(observation.lastIdle) ? observation.idlePolls + 1 : 1;
                observation.lastIdle = current;
                String taskLog = client.taskLog(uuid);
                boolean ended = completionCount(taskLog) >= observation.baselineCompletions + observation.submissions;
                // 日志滚动会丢失计数；单次运行可用新的末条结束记录确认生命周期，不能仅凭 idle。
                if (!ended && observation.submissions == 1 && !lastCompletion(taskLog).equals(observation.baselineLastCompletion)
                        && !lastCompletion(taskLog).isEmpty()) ended = true;
                if (ended && observation.idlePolls >= 2) {
                    pending.remove(uuid, observation);
                    List<String> created = current.keySet().stream().sorted().toList();
                    List<String> deleted = observation.before.values().stream().flatMap(before -> before.keySet().stream())
                            .distinct().filter(path -> !current.containsKey(path)).sorted().toList();
                    notifyChunks(created, emby::notifyMediaCreated);
                    notifyChunks(deleted, emby::notifyMediaDeleted);
                    return;
                }
            }
        } catch (RuntimeException unavailable) {
            observation.idlePolls = 0;
            observation.lastIdle = null;
            log.warn("SmartStrm completion observation unavailable; no generation or notification writes replayed");
        }
        if (System.nanoTime() >= observation.deadline) {
            pending.remove(uuid, observation);
            log.warn("SmartStrm completion observation expired; completion remains unverified");
        } else schedule(uuid);
    }

    private static int completionCount(String log) {
        return (int) java.util.regex.Pattern.compile("任务完成").matcher(log).results().count();
    }

    private static String lastCompletion(String log) {
        return java.util.regex.Pattern.compile("(?:[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2} )?任务完成")
                .matcher(log).results().map(java.util.regex.MatchResult::group).reduce((a, b) -> b).orElse("");
    }

    private void notifyChunks(List<String> paths, Consumer<List<String>> notify) {
        for (int offset = 0; offset < paths.size(); offset += 200) {
            try { notify.accept(paths.subList(offset, Math.min(offset + 200, paths.size()))); }
            catch (RuntimeException unknown) { log.warn("SmartStrm Emby notification outcome unknown; chunk not replayed"); }
        }
    }

    private Map<String, String> snapshot(Path output) {
        Map<String, String> files = new HashMap<>();
        try {
            Path permitted = Path.of(properties.getPermittedOutputRoot()).toRealPath();
            Path existing = output;
            while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
            if (!existing.toRealPath().startsWith(permitted) || !existing.toRealPath().equals(existing))
                throw SmartStrmClient.failure("SmartStrm 完成观察路径越界或包含符号链接");
            if (!Files.exists(output, LinkOption.NOFOLLOW_LINKS)) return files;
            try (var walk = Files.walk(output, 32)) {
                var entries = walk.limit(10001).toList();
                if (entries.size() > 10000) throw SmartStrmClient.failure("SmartStrm 输出子树超出有界观察容量");
                for (Path path : entries) {
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && output.relativize(path).getNameCount() >= 32)
                        throw SmartStrmClient.failure("SmartStrm 输出子树超出有界观察深度");
                    if (!path.getFileName().toString().endsWith(".strm") || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                    BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (attributes.size() > 0) files.put(path.toString(), attributes.lastModifiedTime() + ":" + attributes.size());
                }
            }
            return files;
        } catch (IOException exception) { throw SmartStrmClient.failure("SmartStrm 输出子树暂时无法核验"); }
    }
    @PreDestroy public void close() { observer.shutdownNow(); pending.clear(); }
    private static class Observation {
        final Map<Path, Map<String, String>> before = new HashMap<>();
        final long deadline;
        final int baselineCompletions;
        final String baselineLastCompletion;
        int submissions;
        boolean scheduled;
        Map<String, String> lastIdle;
        int idlePolls;
        Observation(long deadline, String baselineLog) {
            this.deadline = deadline;
            this.baselineCompletions = completionCount(baselineLog);
            this.baselineLastCompletion = lastCompletion(baselineLog);
        }
    }
}
