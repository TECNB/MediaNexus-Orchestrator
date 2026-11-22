package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.JavMetadataProperties;
import com.medianexus.orchestrator.integration.emby.EmbyClient;
import com.medianexus.orchestrator.integration.emby.EmbyItem;
import com.medianexus.orchestrator.integration.emby.EmbyJavMetadataWriter;
import com.medianexus.orchestrator.integration.javdb.JavdbEdition;
import com.medianexus.orchestrator.integration.javdb.JavdbMetadataSnapshot;
import com.medianexus.orchestrator.integration.javdb.JavdbMetadataSource;
import com.medianexus.orchestrator.mapper.AdultMagnetIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.JavMetadataQueueMapper;
import com.medianexus.orchestrator.mapper.JavMetadataQueueMapper.SourceRow;
import com.medianexus.orchestrator.mapper.JavMetadataQueueMapper.TargetRow;
import com.medianexus.orchestrator.mapper.SystemSettingMapper;
import com.medianexus.orchestrator.model.AdultMagnetIngestTaskLog;
import com.medianexus.orchestrator.service.settings.targets.IngestTargetRegistry;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Single-instance durable completion worker. Download outcomes never depend on remote metadata availability. */
@Service
public class JavMetadataCompletion {
    private static final Logger log = LoggerFactory.getLogger(JavMetadataCompletion.class);
    private final JavMetadataProperties properties;
    private final JavMetadataQueueMapper queue;
    private final SystemSettingMapper settings;
    private final AdultMagnetIngestTaskLogMapper taskLogs;
    private final JavdbMetadataSource source;
    private final EmbyJavMetadataWriter writer;
    private final EmbyClient emby;
    private final IngestTargetRegistry targets;
    private final ObjectMapper json;
    private final ExecutorService preparation = Executors.newSingleThreadExecutor(r -> daemon(r, "jav-metadata-source"));
    private final ExecutorService completion = Executors.newSingleThreadExecutor(r -> daemon(r, "jav-metadata-completion"));
    private final AtomicBoolean preparing = new AtomicBoolean();
    private final AtomicBoolean completing = new AtomicBoolean();
    private volatile boolean tablesReady;

    public JavMetadataCompletion(JavMetadataProperties properties, JavMetadataQueueMapper queue,
            SystemSettingMapper settings, AdultMagnetIngestTaskLogMapper taskLogs, JavdbMetadataSource source,
            EmbyJavMetadataWriter writer, EmbyClient emby, IngestTargetRegistry targets, ObjectMapper json) {
        this.properties = properties;
        this.queue = queue;
        this.settings = settings;
        this.taskLogs = taskLogs;
        this.source = source;
        this.writer = writer;
        this.emby = emby;
        this.targets = targets;
        this.json = json;
    }

    public void registerDownload(String taskId, String hash, String magnet, String automationRunId) {
        if (!properties.isEnabled()) return;
        ensureTables();
        JavdbEdition.parse(magnet).ifPresentOrElse(edition -> {
            String detail = automationRunId == null ? null : queue.automationDetail(automationRunId, hash, edition.code());
            registerSource(taskId, hash, edition, detail);
        }, () -> event(taskId, "WARN", "磁力未包含唯一番号，下载后将按主文件名补充识别", null));
    }

    /** Only exact-identity main filenames are admitted; a parent directory cannot turn an advertisement into a movie. */
    public void publishedFile(String taskId, String hash, String magnet, String automationRunId, String sourcePath) {
        if (!properties.isEnabled()) return;
        ensureTables();
        var fileEdition = JavdbEdition.parse(Path.of(sourcePath).getFileName().toString());
        if (fileEdition.isEmpty()) {
            event(taskId, "WARN", "已保留视频没有明确主片番号，未自动套用元数据", Path.of(sourcePath).getFileName().toString());
            return;
        }
        var declared = JavdbEdition.parse(magnet);
        if (declared.isPresent() && !sameCode(declared.get().code(), fileEdition.get().code())) {
            event(taskId, "WARN", "实际视频番号与磁力不符，未写入元数据", fileEdition.get().code());
            return;
        }
        JavdbEdition edition = new JavdbEdition(fileEdition.get().code(),
                fileEdition.get().chineseSubtitles() || declared.map(JavdbEdition::chineseSubtitles).orElse(false),
                fileEdition.get().cracked() || declared.map(JavdbEdition::cracked).orElse(false));
        String detail = automationRunId == null ? null : queue.automationDetail(automationRunId, hash, edition.code());
        String sourceId = registerSource(taskId, hash, edition, detail);
        String strm = strmPath(sourcePath);
        if (queue.insertTarget(digest(taskId + "\n" + strm), sourceId, strm,
                LocalDateTime.now().plus(properties.getCompletionTimeout())) > 0) {
            event(taskId, "INFO", "主片已整理，开始确认 Emby 收录并补全资料", strm);
        }
    }

    private String registerSource(String taskId, String hash, JavdbEdition edition, String detail) {
        String id = digest(taskId + "\n" + hash + "\n" + edition.code()
                + "\n" + edition.chineseSubtitles() + "\n" + edition.cracked());
        queue.insertSource(id, taskId, hash, edition.code(), detail, edition.chineseSubtitles(), edition.cracked());
        return id;
    }

    String strmPath(String sourcePath) {
        var binding = targets.bindingForPath("ADULT", sourcePath);
        String root = binding.map(IngestTargetRegistry.CategoryBinding::rootPath).orElse(properties.getLegacyIngestRoot());
        String output = binding.map(IngestTargetRegistry.CategoryBinding::strmRootPath).orElse(properties.getLegacyStrmRoot());
        Path source = Path.of(sourcePath).normalize();
        Path input = Path.of(root).normalize();
        if (!source.isAbsolute() || !source.startsWith(input) || source.equals(input)) {
            throw new IllegalArgumentException("视频不在已配置的入库目录内");
        }
        Path relative = input.relativize(source);
        String filename = relative.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        if (dot < 1) throw new IllegalArgumentException("主文件扩展名缺失");
        Path parent = relative.getParent();
        Path result = Path.of(output).resolve(parent == null ? Path.of("") : parent)
                .resolve(filename.substring(0, dot) + ".strm").normalize();
        return result.toString();
    }

    @Scheduled(fixedDelayString = "#{@javMetadataProperties.pollInterval.toMillis()}")
    public void poll() {
        if (!properties.isEnabled()) return;
        ensureTables();
        if (preparing.compareAndSet(false, true)) preparation.execute(() -> {
            try { prepareDue(); } catch (RuntimeException e) { log.warn("JAV 资料队列读取失败: {}", e.getClass().getSimpleName()); }
            finally { preparing.set(false); }
        });
        if (completing.compareAndSet(false, true)) completion.execute(() -> {
            try { completeDue(); } catch (RuntimeException e) { log.warn("JAV 补全队列读取失败: {}", e.getClass().getSimpleName()); }
            finally { completing.set(false); }
        });
    }

    void prepareDue() {
        for (SourceRow row : queue.dueSources()) {
            try {
                String cookie = settings.selectSettingValue(row.code().startsWith("FC2-") ? "javdb_top_cookie" : "javdb_automation_cookie");
                JavdbMetadataSnapshot snapshot = row.snapshotJson() == null
                        ? source.fetch(row.code(), row.detailUrl(), cookie)
                        : json.readValue(row.snapshotJson(), JavdbMetadataSnapshot.class);
                String snapshotJson = json.writeValueAsString(snapshot);
                byte[] primary = row.primaryImage(), thumb = row.thumbImage(), backdrop = row.backdropImage();
                queue.saveProgress(row.id(), snapshotJson, primary, thumb, backdrop);
                if (snapshot.coverUrl() == null) throw new IllegalStateException("JavDB 封面地址缺失");
                if (primary == null) {
                    primary = writer.preparePrimary(snapshot, row.chineseSubtitles());
                    queue.saveProgress(row.id(), snapshotJson, primary, thumb, backdrop);
                }
                if (thumb == null) {
                    thumb = writer.prepareThumb(snapshot);
                    queue.saveProgress(row.id(), snapshotJson, primary, thumb, backdrop);
                }
                if (backdrop == null) backdrop = writer.prepareBackdrop(snapshot);
                queue.ready(row.id(), snapshotJson, primary, thumb, backdrop);
                event(row.taskId(), "INFO", "JavDB 身份已核对，元数据及三类图片已保存", row.code());
            } catch (Exception exception) {
                boolean exhausted = row.attempts() + 1 >= properties.getMaxAttempts();
                queue.sourceFailed(row.id(), exhausted ? "FAILED" : "NEW", exception.getClass().getSimpleName(),
                        LocalDateTime.now().plusSeconds(Math.min(300, 15L << Math.min(row.attempts(), 4))));
                String reason = exception instanceof com.medianexus.orchestrator.integration.javdb.JavdbClientException upstream
                        ? upstream.reason().name() : exception.getClass().getSimpleName();
                event(row.taskId(), "WARN", exhausted ? "JavDB 资料准备失败，已停止自动重试" : "JavDB 资料暂未取得，将有限重试",
                        row.code() + ": " + reason);
            }
        }
    }

    void completeDue() {
        for (TargetRow target : queue.dueTargets()) {
            SourceRow row = queue.source(target.sourceId());
            LocalDateTime now = LocalDateTime.now();
            if (now.isAfter(target.deadline()) || ("FAILED".equals(row.state()) && row.snapshotJson() == null)) {
                queue.targetState(target.id(), "FAILED", target.attempts(), target.embyItemId(),
                        "资料未就绪或等待收录超时", now, null);
                event(row.taskId(), "WARN", "资料补全未完成，需检查资料源或 Emby 收录", row.code());
                continue;
            }
            try {
                if (row.snapshotJson() == null) {
                    defer(target, null);
                    continue;
                }
                List<EmbyItem> items = emby.findVideoItemsByPath(target.strmPath());
                List<EmbyItem> exact = items.stream().filter(item -> target.strmPath().equals(item.path())).toList();
                if (exact.size() != 1) {
                    defer(target, exact.isEmpty() ? null : "同路径多个条目，未自动选择");
                    continue;
                }
                JavdbMetadataSnapshot snapshot = json.readValue(row.snapshotJson(), JavdbMetadataSnapshot.class);
                if (!"READY".equals(row.state())) {
                    if (!"METADATA_WRITTEN".equals(target.state())) {
                        writer.writeMetadataAndVerify(exact.get(0).id(), target.strmPath(), snapshot,
                                row.chineseSubtitles(), row.cracked());
                        queue.targetState(target.id(), "METADATA_WRITTEN", target.attempts(), exact.get(0).id(), null,
                                now.plus(properties.getPollInterval()), null);
                        event(row.taskId(), "INFO", "Emby 元数据已补全，图片仍待准备", row.code());
                    } else if ("FAILED".equals(row.state())) {
                        queue.targetState(target.id(), "FAILED", target.attempts(), exact.get(0).id(), "图片准备失败，文字资料已保留", now, null);
                    } else defer(target, null);
                    continue;
                }
                var artwork = new EmbyJavMetadataWriter.Artwork(row.primaryImage(), row.thumbImage(), row.backdropImage());
                boolean finalPass = "VERIFYING".equals(target.state());
                if (finalPass) {
                    writer.verify(exact.get(0).id(), target.strmPath(), snapshot,
                            row.chineseSubtitles(), row.cracked(), artwork);
                } else {
                    writer.writeAndVerify(exact.get(0).id(), target.strmPath(), snapshot,
                            row.chineseSubtitles(), row.cracked(), artwork);
                }
                queue.targetState(target.id(), finalPass ? "COMPLETE" : "VERIFYING", target.attempts(), exact.get(0).id(), null,
                        now.plusSeconds(30), finalPass ? now : null);
                event(row.taskId(), "INFO", finalPass ? "Emby 元数据及三类图片复核完成" : "Emby 已收录，元数据及图片已写入，等待复核",
                        row.code() + ", item=" + exact.get(0).id());
            } catch (Exception exception) {
                int attempts = target.attempts() + 1;
                queue.targetState(target.id(), attempts >= properties.getMaxAttempts() ? "FAILED" : target.state(),
                        attempts, target.embyItemId(), exception.getClass().getSimpleName(), now.plusSeconds(30), null);
                event(row.taskId(), "WARN", "Emby 元数据补全未通过，保留已下载文件",
                        row.code() + ": " + exception.getClass().getSimpleName());
            }
        }
    }

    private void defer(TargetRow row, String reason) {
        queue.targetState(row.id(), row.state(), row.attempts(), row.embyItemId(), reason,
                LocalDateTime.now().plus(properties.getPollInterval()), null);
    }

    private synchronized void ensureTables() {
        if (!tablesReady) { queue.createSources(); queue.createTargets(); tablesReady = true; }
    }

    private void event(String taskId, String level, String message, String detail) {
        AdultMagnetIngestTaskLog entry = new AdultMagnetIngestTaskLog();
        entry.setTaskId(taskId); entry.setLevel(level); entry.setStage("jav_metadata");
        entry.setMessage(message); entry.setDetail(detail); entry.setCreatedAt(LocalDateTime.now());
        taskLogs.insert(entry);
    }

    static boolean sameCode(String a, String b) {
        return a.replaceAll("(?i)([A-Z]+)-0+(\\d)", "$1-$2")
                .equalsIgnoreCase(b.replaceAll("(?i)([A-Z]+)-0+(\\d)", "$1-$2"));
    }

    private static String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static Thread daemon(Runnable task, String name) { Thread t = new Thread(task, name); t.setDaemon(true); return t; }
    @PreDestroy public void close() { preparation.shutdownNow(); completion.shutdownNow(); }
}
