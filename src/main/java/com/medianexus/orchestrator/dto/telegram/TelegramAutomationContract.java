package com.medianexus.orchestrator.dto.telegram;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

public final class TelegramAutomationContract {

    private TelegramAutomationContract() {
    }

    public record ChannelInput(
            String id,
            @JsonProperty("source_ref") @NotBlank(message = "频道地址或 ID 不能为空") String sourceRef,
            @NotNull(message = "频道启用状态不能为空") Boolean enabled,
            @NotNull(message = "分位数不能为空")
            @DecimalMin(value = "0.50", message = "分位数不能低于 P50")
            @DecimalMax(value = "0.99", message = "分位数不能高于 P99") Double percentile,
            @JsonProperty("resource_mode")
            @NotBlank(message = "资源模式不能为空")
            @Pattern(regexp = "group|hashtag_resource", message = "资源模式不正确") String resourceMode,
            @JsonProperty("min_video_duration")
            @NotNull(message = "最短视频时长不能为空")
            @Min(value = 0, message = "最短视频时长不能小于 0") Integer minVideoDuration,
            @JsonProperty("min_views")
            @NotNull(message = "最低浏览数不能为空")
            @Min(value = 0, message = "最低浏览数不能小于 0") Integer minViews,
            @JsonProperty("min_forwards")
            @NotNull(message = "最低转发数不能为空")
            @Min(value = 0, message = "最低转发数不能小于 0") Integer minForwards,
            @JsonProperty("min_age_hours")
            @NotNull(message = "最短观察时间不能为空")
            @Min(value = 0, message = "最短观察时间不能小于 0") Integer minAgeHours
    ) {
    }

    public record ConfigUpdateRequest(
            @NotNull(message = "自动化启用状态不能为空") Boolean enabled,
            @NotBlank(message = "目标 Bot 不能为空") String target,
            @Valid @NotEmpty(message = "至少需要配置一个频道")
            @Size(max = 50, message = "最多配置 50 个频道") List<ChannelInput> channels
    ) {
    }

    public record ResolveSourceRequest(
            @JsonProperty("source_ref") @NotBlank(message = "频道地址或 ID 不能为空") String sourceRef
    ) {
    }

    public record BackfillRequest(
            @JsonProperty("top_resources")
            @NotNull(message = "Top N 不能为空")
            @Min(value = 1, message = "Top N 至少为 1")
            @Max(value = 100, message = "Top N 最多为 100") Integer topResources,
            @JsonProperty("lookback_days")
            @NotNull(message = "回溯天数不能为空")
            @Min(value = 1, message = "回溯天数至少为 1")
            @Max(value = 3650, message = "回溯天数最多为 3650") Integer lookbackDays,
            @JsonProperty("max_messages")
            @NotNull(message = "扫描消息数不能为空")
            @Min(value = 200, message = "扫描消息数至少为 200")
            @Max(value = 10000, message = "扫描消息数最多为 10000") Integer maxMessages,
            @JsonProperty("start_mode")
            @NotBlank(message = "回溯起点不能为空")
            @Pattern(regexp = "continue|latest", message = "回溯起点不正确") String startMode,
            @JsonProperty("force_resend") Boolean forceResend
    ) {
    }

    public record ChannelConfig(
            String id,
            @JsonProperty("source_id") long sourceId,
            @JsonProperty("source_title") String sourceTitle,
            @JsonProperty("source_username") String sourceUsername,
            boolean enabled,
            double percentile,
            @JsonProperty("resource_mode") String resourceMode,
            @JsonProperty("min_video_duration") int minVideoDuration,
            @JsonProperty("min_views") int minViews,
            @JsonProperty("min_forwards") int minForwards,
            @JsonProperty("min_age_hours") int minAgeHours
    ) {
    }

    public record ConfigResponse(
            boolean enabled,
            String target,
            @JsonProperty("schedule_time") String scheduleTime,
            String timezone,
            List<ChannelConfig> channels
    ) {
    }

    public record ResolvedSourceResponse(
            @JsonProperty("source_id") long sourceId,
            String title,
            String username,
            @JsonProperty("forwards_restricted") boolean forwardsRestricted
    ) {
    }

    public record ChannelRunResponse(
            @JsonProperty("channel_id") String channelId,
            @JsonProperty("source_id") long sourceId,
            @JsonProperty("source_title") String sourceTitle,
            String status,
            @JsonProperty("attempt_count") int attemptCount,
            @JsonProperty("selected_resource_count") int selectedResourceCount,
            @JsonProperty("duplicate_resource_count") int duplicateResourceCount,
            @JsonProperty("forwarded_resource_count") int forwardedResourceCount,
            @JsonProperty("forwarded_message_count") int forwardedMessageCount,
            Double threshold,
            @JsonProperty("worker_response") JsonNode workerResponse,
            @JsonProperty("error_message") String errorMessage
    ) {
    }

    public record RunResponse(
            String id,
            @JsonProperty("trigger_type") String triggerType,
            @JsonProperty("triggered_by_user_id") Long triggeredByUserId,
            @JsonProperty("execution_mode") String executionMode,
            String status,
            String stage,
            @JsonProperty("channel_count") int channelCount,
            @JsonProperty("succeeded_channel_count") int succeededChannelCount,
            @JsonProperty("failed_channel_count") int failedChannelCount,
            @JsonProperty("selected_resource_count") int selectedResourceCount,
            @JsonProperty("duplicate_resource_count") int duplicateResourceCount,
            @JsonProperty("forwarded_resource_count") int forwardedResourceCount,
            @JsonProperty("forwarded_message_count") int forwardedMessageCount,
            @JsonProperty("error_message") String errorMessage,
            @JsonProperty("started_at") LocalDateTime startedAt,
            @JsonProperty("finished_at") LocalDateTime finishedAt,
            List<ChannelRunResponse> channels
    ) {
    }

    public record OverviewResponse(
            ConfigResponse config,
            @JsonProperty("worker_available") boolean workerAvailable,
            @JsonProperty("worker_authorized") boolean workerAuthorized,
            @JsonProperty("worker_version") String workerVersion,
            @JsonProperty("latest_run") RunResponse latestRun,
            @JsonProperty("current_run") RunResponse currentRun
    ) {
    }

    public record RunListResponse(
            List<RunResponse> items,
            int total,
            int page,
            @JsonProperty("page_size") int pageSize
    ) {
    }
}
