package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.medianexus.orchestrator.config.TmdbProperties;
import com.medianexus.orchestrator.config.WeComProperties;
import com.medianexus.orchestrator.integration.tmdb.TmdbClient;
import com.medianexus.orchestrator.integration.wecom.WeComClient;
import com.medianexus.orchestrator.integration.wecom.WeComClient.Recipient;
import com.medianexus.orchestrator.model.AnimeMagnetIngestTask;
import com.medianexus.orchestrator.model.MovieMagnetIngestTask;
import com.medianexus.orchestrator.model.SeriesMagnetIngestTask;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 任务创建和成功保存后的尽力通知；缺图、配置或发送失败只记录日志，不修改入库状态。 */
@Service
public class IngestTaskNotifier {

    private static final Logger log = LoggerFactory.getLogger(IngestTaskNotifier.class);
    private final WeComProperties properties;
    private final TmdbProperties tmdbProperties;
    private final TmdbClient tmdbClient;
    private final WeComClient weComClient;
    private final ExecutorService executor;
    private final IngestExceptionNotifier exceptions;
    private final WeComBindings bindings;

    @Autowired
    public IngestTaskNotifier(
            WeComProperties properties, TmdbProperties tmdbProperties, TmdbClient tmdbClient, WeComClient weComClient, IngestExceptionNotifier exceptions, WeComBindings bindings
    ) {
        this(properties, tmdbProperties, tmdbClient, weComClient, Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "wecom-ingest-notifications");
            thread.setDaemon(true);
            return thread;
        }), exceptions, bindings);
    }

    IngestTaskNotifier(
            WeComProperties properties, TmdbProperties tmdbProperties, TmdbClient tmdbClient,
            WeComClient weComClient, ExecutorService executor, IngestExceptionNotifier exceptions, WeComBindings bindings
    ) {
        this.properties = properties;
        this.tmdbProperties = tmdbProperties;
        this.tmdbClient = tmdbClient;
        this.weComClient = weComClient;
        this.executor = executor;
        this.exceptions = exceptions;
        this.bindings = bindings;
    }

    public void movieStarted(MovieMagnetIngestTask task) {
        submit(task.getId(), task.getTmdbId(), task.getCreatedByUserId(), recipient -> {
            String title = task.getTitle() + (task.getYear() == null ? "" : "（" + task.getYear() + "）");
            send(recipient, new IngestNotice("movie", task.getId(), title, "电影"), "任务已发起", "入库任务已创建并排入后台队列，进度请查看任务详情。", snapshot -> tmdbClient.getMovieImages(task.getTmdbId(), snapshot));
        });
    }

    public void seriesStarted(SeriesMagnetIngestTask task) {
        submit(task.getId(), task.getTmdbId(), task.getCreatedByUserId(), recipient -> send(recipient,
                new IngestNotice("series", task.getId(), seasonTitle(task.getTitle(), task.getSeasonNumber()),
                        "ANIME".equals(task.getTaskProductType()) ? "动漫" : "剧集"),
                "任务已发起", "入库任务已创建并排入后台队列，进度请查看任务详情。", snapshot -> tmdbClient.getTvImages(task.getTmdbId(), snapshot)
        ));
    }

    public void animeStarted(AnimeMagnetIngestTask task) {
        submit(task.getId(), task.getTmdbId(), task.getCreatedByUserId(), recipient -> send(recipient,
                new IngestNotice("anime", task.getId(), seasonTitle(task.getTitle(), task.getSeasonNumber()), "动漫"),
                "任务已发起", "入库任务已创建并排入后台队列，进度请查看任务详情。", snapshot -> tmdbClient.getTvImages(task.getTmdbId(), snapshot)
        ));
    }

    public void movieCompleted(MovieMagnetIngestTask task) {
        submit(task.getId(), task.getTmdbId(), task.getCreatedByUserId(), recipient -> {
            String title = task.getTitle() + (task.getYear() == null ? "" : "（" + task.getYear() + "）");
            send(recipient, new IngestNotice("movie", task.getId(), title, "电影"), "任务处理完成", "入库任务已处理完成。\n播放可用性请以媒体库为准。", snapshot -> tmdbClient.getMovieImages(task.getTmdbId(), snapshot));
        });
    }

    public void seriesCompleted(SeriesMagnetIngestTask task) {
        submit(task.getId(), task.getTmdbId(), task.getCreatedByUserId(), recipient -> send(recipient,
                new IngestNotice("series", task.getId(), seasonTitle(task.getTitle(), task.getSeasonNumber()),
                        "ANIME".equals(task.getTaskProductType()) ? "动漫" : "剧集"),
                "任务处理完成", "入库任务已处理完成。\n播放可用性请以媒体库为准。", snapshot -> tmdbClient.getTvImages(task.getTmdbId(), snapshot)
        ));
    }

    public void animeCompleted(AnimeMagnetIngestTask task) {
        submit(task.getId(), task.getTmdbId(), task.getCreatedByUserId(), recipient -> send(recipient,
                new IngestNotice("anime", task.getId(), seasonTitle(task.getTitle(), task.getSeasonNumber()), "动漫"),
                "任务处理完成", "入库任务已处理完成。\n播放可用性请以媒体库为准。", snapshot -> tmdbClient.getTvImages(task.getTmdbId(), snapshot)
        ));
    }

    /** 最终失败后发送无图消息；持久去重以任务尝试为单位，通知故障不影响任务结果。 */
    public void failed(String type, String id, Long ownerId, String title, String failedStage, String error) {
        dispatch(id, ownerId, recipient -> {
            boolean downloading = !"organizing".equals(failedStage) && ("downloading".equals(failedStage) || "submitted".equals(failedStage)
                    || error != null && (error.contains("离线") || error.contains("下载")));
            String heading = downloading ? error != null && error.contains("超时") ? "下载超时" : "下载失败" : "入库处理失败";
            String body = downloading ? "离线下载未完成，可以前往任务中心查看详情，并更换种子重试。"
                    : "任务处理未完成，请前往任务中心查看具体失败阶段和日志。";
            exceptions.send(recipient, "task:" + type + ":" + id, heading + "｜" + title, body,
                    "/tasks/" + type + "/" + java.net.URLEncoder.encode(id, StandardCharsets.UTF_8));
        });
    }

    private void submit(String taskId, Integer tmdbId, Long ownerId, Consumer<Recipient> notification) {
        if (!properties.isEnabled()) {
            return;
        }
        if (tmdbId == null || tmdbId <= 0) {
            log.warn("企业微信任务通知未发送 taskId={} 原因=任务缺少 TMDB 身份，无法取得封面", taskId);
            return;
        }
        dispatch(taskId, ownerId, notification);
    }

    private void dispatch(String taskId, Long ownerId, Consumer<Recipient> notification) {
        if (!properties.isEnabled()) return;
        try {
            executor.execute(() -> {
                try {
                    Recipient recipient = bindings.recipient(ownerId);
                    if (recipient == null) {
                        log.debug("企业微信任务通知已跳过 taskId={} 原因=任务没有已绑定的所属用户", taskId);
                        return;
                    }
                    notification.accept(recipient);
                } catch (RuntimeException exception) {
                    log.warn("企业微信任务通知未发送 taskId={} 原因={}", taskId, exception.getMessage());
                }
            });
        } catch (RuntimeException exception) {
            log.warn("企业微信任务通知未排入发送队列 taskId={}", taskId);
        }
    }

    private void send(Recipient recipient, IngestNotice notice, String heading, String summary, java.util.function.Function<TmdbProperties, JsonNode> loadImages) {
        TmdbProperties snapshot = tmdbProperties.snapshot();
        String imageUrl = imageUrl(loadImages.apply(snapshot), snapshot);
        if (imageUrl == null) {
            log.warn("企业微信任务通知未发送 taskId={} 原因=该条目没有可用横图或海报", notice.taskId());
            return;
        }
        String suffix = " · " + heading;
        String title = truncateUtf8(notice.title(), 128 - suffix.getBytes(StandardCharsets.UTF_8).length) + suffix;
        String description = notice.mediaLabel() + summary;
        weComClient.sendNews(recipient, title, description, imageUrl, detailUrl(notice));
        log.info("企业微信 API 已受理任务通知 taskId={} 类型={}，企业微信实际收件仍需核实", notice.taskId(), heading);
    }

    private String imageUrl(JsonNode images, TmdbProperties snapshot) {
        for (String kind : new String[]{"backdrops", "posters"}) {
            for (JsonNode image : images.path(kind)) {
                String path = image.path("file_path").asText();
                if (StringUtils.hasText(path) && path.startsWith("/") && !path.startsWith("//")) {
                    return snapshot.getImageBaseUrl().replaceAll("/+$", "") + path;
                }
            }
        }
        return null;
    }

    private String detailUrl(IngestNotice notice) {
        if (!StringUtils.hasText(properties.getFrontendBaseUrl())) {
            throw new IllegalStateException("企业微信通知缺少前端站点地址");
        }
        URI base;
        try {
            base = URI.create(properties.getFrontendBaseUrl().trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("企业微信通知前端站点地址格式无效");
        }
        if (!("https".equals(base.getScheme()) || "http".equals(base.getScheme())) || base.getHost() == null
                || base.getRawUserInfo() != null || base.getRawQuery() != null || base.getRawFragment() != null) {
            throw new IllegalStateException("企业微信通知前端站点地址须为不含凭据、查询参数或片段的 HTTP(S) 地址");
        }
        return base.toString().replaceAll("/+$", "") + "/tasks/" + notice.taskType() + "/" + notice.taskId();
    }

    private String seasonTitle(String title, Integer seasonNumber) {
        return title + (seasonNumber == null ? "" : " · 第" + seasonNumber + "季");
    }

    static String truncateUtf8(String text, int maxBytes) {
        if (text.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return text;
        }
        int budget = maxBytes - "…".getBytes(StandardCharsets.UTF_8).length;
        int bytes = 0;
        int end = 0;
        while (end < text.length()) {
            int codePoint = text.codePointAt(end);
            int length = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + length > budget) {
                break;
            }
            bytes += length;
            end += Character.charCount(codePoint);
        }
        return text.substring(0, end) + "…";
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private record IngestNotice(String taskType, String taskId, String title, String mediaLabel) { }
}
