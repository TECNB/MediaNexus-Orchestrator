package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.AutoSymlinkProperties;
import com.medianexus.orchestrator.integration.autosymlink.AutoSymlinkClient;
import com.medianexus.orchestrator.integration.autosymlink.AutoSymlinkClientException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Submits configured AutoSymlink refresh tasks after media files are accepted into the library.
 * Refresh failures are reported to callers and must not roll back the completed ingest task.
 */
@Service
public class AutoSymlinkRefreshService {

    private static final Logger log = LoggerFactory.getLogger(AutoSymlinkRefreshService.class);

    private final AutoSymlinkProperties properties;
    private final AutoSymlinkClient autoSymlinkClient;
    private final ObjectMapper objectMapper;

    public AutoSymlinkRefreshService(
            AutoSymlinkProperties properties,
            AutoSymlinkClient autoSymlinkClient,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.autoSymlinkClient = autoSymlinkClient;
        this.objectMapper = objectMapper;
    }

    /** Submits the configured movie refresh without changing the completed ingest task. */
    public RefreshOutcome refreshMovie() {
        return refresh("movie", properties.getMovie());
    }

    /** Submits the configured series refresh without changing the completed ingest task. */
    public RefreshOutcome refreshSeries() {
        return refresh("tv", properties.getTv());
    }

    /** Submits the configured anime refresh without changing the completed ingest task. */
    public RefreshOutcome refreshAnime() {
        return refresh("anime", properties.getAnime());
    }

    /** Submits the configured Adult refresh without changing the completed ingest task. */
    public RefreshOutcome refreshAdult() {
        return refresh("adult", properties.getAdult());
    }

    private RefreshOutcome refresh(String taskKey, AutoSymlinkProperties.SyncTask task) {
        String configurationMessage = incompleteConfigurationMessage(taskKey, task);
        if (configurationMessage != null) {
            return RefreshOutcome.skipped(configurationMessage);
        }

        JsonNode requestBody;
        try {
            requestBody = objectMapper.readTree(task.getRequestBodyJson());
        } catch (JsonProcessingException exception) {
            log.warn("AutoSymlink request body json is invalid taskKey={}", taskKey, exception);
            return RefreshOutcome.skipped("AutoSymlink 刷新请求体配置解析失败，已跳过");
        }
        if (!requestBody.isObject()) {
            return RefreshOutcome.skipped("AutoSymlink 刷新请求体必须是 JSON 对象，已跳过");
        }

        int maxAttempts = properties.getMaxAttempts();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                autoSymlinkClient.submitSyncTask(task.getUuid(), requestBody);
                return RefreshOutcome.submitted("task=" + taskKey + ", attempt=" + attempt);
            } catch (AutoSymlinkClientException exception) {
                if (attempt >= maxAttempts) {
                    log.warn(
                            "AutoSymlink refresh failed taskKey={} attempt={} maxAttempts={}",
                            taskKey,
                            attempt,
                            maxAttempts,
                            exception
                    );
                    return RefreshOutcome.failed("AutoSymlink 刷新任务提交失败，已跳过");
                }
                log.warn("AutoSymlink refresh attempt failed taskKey={} attempt={}", taskKey, attempt, exception);
                if (!sleep(retryInterval())) {
                    return RefreshOutcome.failed("AutoSymlink 刷新重试被中断，已跳过");
                }
            }
        }
        return RefreshOutcome.failed("AutoSymlink 刷新任务提交失败，已跳过");
    }

    private String incompleteConfigurationMessage(String taskKey, AutoSymlinkProperties.SyncTask task) {
        if (!StringUtils.hasText(properties.getBaseUrl())
                || !StringUtils.hasText(properties.getCookie())) {
            return "AutoSymlink 刷新未配置，已跳过";
        }
        if (task == null
                || !StringUtils.hasText(task.getUuid())
                || !StringUtils.hasText(task.getRequestBodyJson())) {
            return "AutoSymlink " + taskKey + " 刷新任务未配置，已跳过";
        }
        return null;
    }

    private Duration retryInterval() {
        return properties.getRetryInterval();
    }

    private boolean sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public record RefreshOutcome(Status status, String message, String detail) {

        static RefreshOutcome submitted(String detail) {
            return new RefreshOutcome(Status.SUBMITTED, "AutoSymlink 刷新任务已提交", detail);
        }

        static RefreshOutcome skipped(String message) {
            return new RefreshOutcome(Status.SKIPPED, message, null);
        }

        static RefreshOutcome failed(String message) {
            return new RefreshOutcome(Status.FAILED, message, null);
        }
    }

    public enum Status {
        SUBMITTED,
        SKIPPED,
        FAILED
    }
}
