package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.medianexus.orchestrator.config.TmdbProperties;
import com.medianexus.orchestrator.config.WeComProperties;
import com.medianexus.orchestrator.integration.tmdb.TmdbClient;
import com.medianexus.orchestrator.integration.wecom.WeComClient;
import com.medianexus.orchestrator.model.AnimeMagnetIngestTask;
import com.medianexus.orchestrator.model.MovieMagnetIngestTask;
import com.medianexus.orchestrator.model.SeriesMagnetIngestTask;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 成功保存后的尽力通知；缺图、配置或发送失败只记录日志，不修改入库状态。 */
@Service
public class IngestCompletionNotifier {

    private static final Logger log = LoggerFactory.getLogger(IngestCompletionNotifier.class);
    private static final String TITLE_SUFFIX = " · 任务处理完成";
    private final WeComProperties properties;
    private final TmdbProperties tmdbProperties;
    private final TmdbClient tmdbClient;
    private final WeComClient weComClient;
    private final ExecutorService executor;

    @Autowired
    public IngestCompletionNotifier(
            WeComProperties properties, TmdbProperties tmdbProperties, TmdbClient tmdbClient, WeComClient weComClient
    ) {
        this(properties, tmdbProperties, tmdbClient, weComClient, Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "wecom-ingest-notifications");
            thread.setDaemon(true);
            return thread;
        }));
    }

    IngestCompletionNotifier(
            WeComProperties properties, TmdbProperties tmdbProperties, TmdbClient tmdbClient,
            WeComClient weComClient, ExecutorService executor
    ) {
        this.properties = properties;
        this.tmdbProperties = tmdbProperties;
        this.tmdbClient = tmdbClient;
        this.weComClient = weComClient;
        this.executor = executor;
    }

    public void movieCompleted(MovieMagnetIngestTask task) {
        submit(task.getId(), task.getTmdbId(), () -> {
            String title = task.getTitle() + (task.getYear() == null ? "" : "（" + task.getYear() + "）");
            send(new CompletionNotice("movie", task.getId(), title, "电影"), tmdbClient.getMovieImages(task.getTmdbId()));
        });
    }

    public void seriesCompleted(SeriesMagnetIngestTask task) {
        submit(task.getId(), task.getTmdbId(), () -> send(
                new CompletionNotice("series", task.getId(), seasonTitle(task.getTitle(), task.getSeasonNumber()),
                        "ANIME".equals(task.getTaskProductType()) ? "动漫" : "剧集"),
                tmdbClient.getTvImages(task.getTmdbId())
        ));
    }

    public void animeCompleted(AnimeMagnetIngestTask task) {
        submit(task.getId(), task.getTmdbId(), () -> send(
                new CompletionNotice("anime", task.getId(), seasonTitle(task.getTitle(), task.getSeasonNumber()), "动漫"),
                tmdbClient.getTvImages(task.getTmdbId())
        ));
    }

    private void submit(String taskId, Integer tmdbId, Runnable notification) {
        if (!properties.isEnabled()) {
            return;
        }
        if (tmdbId == null || tmdbId <= 0) {
            log.warn("企业微信完成通知未发送 taskId={} 原因=任务缺少 TMDB 身份，无法取得封面", taskId);
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    notification.run();
                } catch (RuntimeException exception) {
                    log.warn("企业微信完成通知未发送 taskId={} 原因={}", taskId, exception.getMessage());
                }
            });
        } catch (RuntimeException exception) {
            log.warn("企业微信完成通知未排入发送队列 taskId={}", taskId);
        }
    }

    private void send(CompletionNotice notice, JsonNode images) {
        String imageUrl = imageUrl(images);
        if (imageUrl == null) {
            log.warn("企业微信完成通知未发送 taskId={} 原因=该条目没有可用横图或海报", notice.taskId());
            return;
        }
        String title = truncateUtf8(notice.title(), 128 - TITLE_SUFFIX.getBytes(StandardCharsets.UTF_8).length) + TITLE_SUFFIX;
        String description = notice.mediaLabel() + "入库任务已处理完成。\n播放可用性请以媒体库为准。";
        weComClient.sendNews(title, description, imageUrl, detailUrl(notice));
        log.info("企业微信 API 已受理完成通知 taskId={}，普通微信实际收件仍需核实", notice.taskId());
    }

    private String imageUrl(JsonNode images) {
        for (String kind : new String[]{"backdrops", "posters"}) {
            for (JsonNode image : images.path(kind)) {
                String path = image.path("file_path").asText();
                if (StringUtils.hasText(path) && path.startsWith("/") && !path.startsWith("//")) {
                    return tmdbProperties.getImageBaseUrl().replaceAll("/+$", "") + path;
                }
            }
        }
        return null;
    }

    private String detailUrl(CompletionNotice notice) {
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

    private record CompletionNotice(String taskType, String taskId, String title, String mediaLabel) { }
}
