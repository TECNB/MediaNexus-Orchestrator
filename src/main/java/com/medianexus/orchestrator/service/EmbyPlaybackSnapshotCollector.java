package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.dto.emby.response.EmbyPlaybackSessionsResponse;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Component;

/** 固定延迟、单进程防重入；DB 写者互斥与整轮事务由 accounting 拥有。 */
@Component
public class EmbyPlaybackSnapshotCollector {
    private static final Logger log = LoggerFactory.getLogger(EmbyPlaybackSnapshotCollector.class);
    private final EmbyWatchAccounting accounting;
    private String lastFailureReason;
    private volatile boolean applicationReady;
    private final AtomicBoolean collecting = new AtomicBoolean();
    private volatile EmbyPlaybackSessionsResponse snapshot = new EmbyPlaybackSessionsResponse(
            null, List.of(), false, null, "unavailable", "实时观看尚无成功采集，请稍后重试");

    public EmbyPlaybackSnapshotCollector(EmbyWatchAccounting accounting) {
        this.accounting = accounting;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void applicationReady() { applicationReady = true; }

    /** ApplicationRunner 完成结构初始化前不运行调度；只有这一处 scheduler。 */
    @Scheduled(fixedDelayString = "${medianexus.emby.playback-collection-delay}")
    public void scheduledCollection() {
        if (applicationReady) collectSnapshot();
    }

    /** 一次完整采集；并发触发直接跳过，读取接口不会调用此操作。 */
    public void collectSnapshot() {
        if (!collecting.compareAndSet(false, true)) {
            return;
        }
        EmbyPlaybackSessionsResponse previous = snapshot;
        Instant attemptedAt = Instant.now();
        snapshot = new EmbyPlaybackSessionsResponse(previous.collectedAt(), previous.sessions(), previous.stale(),
                attemptedAt, previous.collectionStatus(), previous.collectionMessage());
        try {
            var observation = accounting.collectSnapshot();
            if (observation == null) { snapshot = previous; return; }
            lastFailureReason = null;
            snapshot = new EmbyPlaybackSessionsResponse(observation.observedAt(), observation.sessions(), false, attemptedAt, "fresh", null);
        } catch (RuntimeException exception) {
            boolean hasSnapshot = previous.collectedAt() != null;
            snapshot = new EmbyPlaybackSessionsResponse(previous.collectedAt(), previous.sessions(), hasSnapshot,
                    attemptedAt, hasSnapshot ? "stale" : "unavailable",
                    hasSnapshot ? "实时观看采集失败，正在显示上次成功快照" : "实时观看尚无成功采集，请稍后重试");
            String reason = failureReason(exception);
            if (!reason.equals(lastFailureReason)) {
                // 仅记录已知阶段与封闭原因；正文、类型名和堆栈均可能携带敏感内容。
                log.warn("Emby 实时观看采集失败，等待后台重试，阶段/原因={}", reason);
                lastFailureReason = reason;
            }
        } finally {
            collecting.set(false);
        }
    }

    private String failureReason(RuntimeException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql) {
                String state = sql.getSQLState();
                return "persistence/sql-" + sql.getErrorCode() + "/"
                        + (state != null && state.matches("[A-Z0-9]{5}") ? state : "unknown");
            }
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.springframework.jdbc.CannotGetJdbcConnectionException) return "persistence/connection";
            if (cause instanceof org.springframework.transaction.TransactionException) return "persistence/transaction";
            if (cause instanceof org.springframework.dao.DataAccessException
                    || cause instanceof org.apache.ibatis.exceptions.PersistenceException) return "persistence/failure";
        }
        if (failure instanceof com.medianexus.orchestrator.integration.emby.EmbyClientException) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof java.net.http.HttpTimeoutException) return "upstream/timeout";
                if (cause instanceof com.fasterxml.jackson.core.JsonProcessingException) return "upstream/invalid-response";
                if (cause instanceof java.io.IOException) return "upstream/transport";
            }
            String message = failure.getMessage();
            if (message != null && message.matches("Emby returned non-success status [1-5][0-9]{2}")) {
                String status = message.substring(message.length() - 3);
                return "401".equals(status) || "403".equals(status) ? "upstream/auth-" + status : "upstream/status-" + status;
            }
            if (java.util.Set.of("Emby sessions response is incomplete", "Emby session identity missing or invalid",
                    "Emby now playing item is incomplete", "Emby playback session identity missing",
                    "Emby response is not valid JSON").contains(message == null ? "" : message)) {
                return "upstream/invalid-response";
            }
            if ("Duplicate playback connection".equals(message)) return "upstream/duplicate-connection";
            if ("Emby configuration is incomplete".equals(message)) return "upstream/configuration";
            return "upstream/failure";
        }
        if ("旧观看累计与闭合分段不一致，保留原活动记录".equals(failure.getMessage())) return "accounting/legacy-evidence";
        if ("活动播放连接重复".equals(failure.getMessage())) return "accounting/duplicate-connection";
        return "accounting/failure";
    }

    public EmbyPlaybackSessionsResponse currentSnapshot() {
        return snapshot;
    }
}
