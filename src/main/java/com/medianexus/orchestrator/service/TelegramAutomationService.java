package com.medianexus.orchestrator.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.dto.telegram.TelegramAutomationContract.BackfillRequest;
import com.medianexus.orchestrator.dto.telegram.TelegramAutomationContract.ChannelConfig;
import com.medianexus.orchestrator.dto.telegram.TelegramAutomationContract.ChannelInput;
import com.medianexus.orchestrator.dto.telegram.TelegramAutomationContract.ChannelRunResponse;
import com.medianexus.orchestrator.dto.telegram.TelegramAutomationContract.ConfigResponse;
import com.medianexus.orchestrator.dto.telegram.TelegramAutomationContract.ConfigUpdateRequest;
import com.medianexus.orchestrator.dto.telegram.TelegramAutomationContract.OverviewResponse;
import com.medianexus.orchestrator.dto.telegram.TelegramAutomationContract.ResolvedSourceResponse;
import com.medianexus.orchestrator.dto.telegram.TelegramAutomationContract.RunListResponse;
import com.medianexus.orchestrator.dto.telegram.TelegramAutomationContract.RunResponse;
import com.medianexus.orchestrator.integration.telegram.TelegramWorkerClient;
import com.medianexus.orchestrator.integration.telegram.TelegramWorkerClientException;
import com.medianexus.orchestrator.mapper.SystemSettingMapper;
import com.medianexus.orchestrator.mapper.TelegramAutomationRunMapper;
import com.medianexus.orchestrator.model.TelegramAutomationRun;
import com.medianexus.orchestrator.model.User;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class TelegramAutomationService {

    private static final Logger log = LoggerFactory.getLogger(TelegramAutomationService.class);
    private static final String CONFIG_KEY = "telegram_automation_config";
    private static final String TIMEZONE = "Asia/Shanghai";
    private static final String SCHEDULE_TIME = "00:00";
    private static final Duration RETRY_INTERVAL = Duration.ofMinutes(15);
    private static final int MAX_ATTEMPTS = 3;

    private final AuthService authService;
    private final SystemSettingMapper settingMapper;
    private final TelegramAutomationRunMapper runMapper;
    private final TelegramWorkerClient workerClient;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "telegram-automation-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final ReentrantLock runCreationLock = new ReentrantLock();
    private volatile boolean tablesReady;

    public TelegramAutomationService(
            AuthService authService,
            SystemSettingMapper settingMapper,
            TelegramAutomationRunMapper runMapper,
            TelegramWorkerClient workerClient,
            ObjectMapper objectMapper
    ) {
        this.authService = authService;
        this.settingMapper = settingMapper;
        this.runMapper = runMapper;
        this.workerClient = workerClient;
        this.objectMapper = objectMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void markUnfinishedRunsInterrupted() {
        ensureTablesReady();
        runMapper.update(null, new LambdaUpdateWrapper<TelegramAutomationRun>()
                .eq(TelegramAutomationRun::getStatus, "RUNNING")
                .set(TelegramAutomationRun::getStatus, "INTERRUPTED")
                .set(TelegramAutomationRun::getStage, "INTERRUPTED")
                .set(TelegramAutomationRun::getErrorMessage, "服务重启，Telegram 自动化运行已中断")
                .set(TelegramAutomationRun::getFinishedAt, LocalDateTime.now()));
    }

    @Scheduled(cron = "0 0 0 * * *", zone = TIMEZONE)
    public void runDaily() {
        ConfigResponse config = loadConfig();
        if (!config.enabled()) {
            return;
        }
        try {
            startScheduledFollowRun(config);
        } catch (BusinessException exception) {
            log.info("Telegram scheduled run skipped: {}", exception.getMessage());
        }
    }

    public OverviewResponse overview() {
        authService.requireAdminUser();
        ensureTablesReady();
        TelegramAutomationRun latest = runMapper.selectOne(new LambdaQueryWrapper<TelegramAutomationRun>()
                .orderByDesc(TelegramAutomationRun::getStartedAt)
                .last("LIMIT 1"));
        TelegramAutomationRun current = currentRun();
        boolean available = false;
        boolean authorized = false;
        String version = null;
        try {
            JsonNode health = workerClient.health();
            available = "ok".equals(health.path("status").asText());
            authorized = health.path("authorized").asBoolean(false);
            version = health.path("workerVersion").asText(null);
        } catch (TelegramWorkerClientException exception) {
            log.debug("Telegram Worker health check failed: {}", exception.getMessage());
        }
        return new OverviewResponse(
                loadConfig(), available, authorized, version,
                latest == null ? null : toResponse(latest, false),
                current == null ? null : toResponse(current, false)
        );
    }

    public ResolvedSourceResponse resolveSource(String sourceRef) {
        authService.requireAdminUser();
        return resolveSourceWithoutAuthorization(sourceRef);
    }

    public ConfigResponse updateConfig(ConfigUpdateRequest request) {
        authService.requireAdminUser();
        ensureTablesReady();
        Map<String, ChannelConfig> existingById = new HashMap<>();
        for (ChannelConfig channel : loadConfig().channels()) {
            existingById.put(channel.id(), channel);
        }
        List<ChannelConfig> channels = new ArrayList<>();
        Set<Long> sourceIds = new HashSet<>();
        for (ChannelInput input : request.channels()) {
            ChannelConfig existing = existingById.get(input.id());
            ResolvedSourceResponse resolved = existing != null
                    && String.valueOf(existing.sourceId()).equals(input.sourceRef().trim())
                    ? new ResolvedSourceResponse(
                            existing.sourceId(), existing.sourceTitle(), existing.sourceUsername(), false
                    )
                    : resolveSourceWithoutAuthorization(input.sourceRef());
            if (resolved.forwardsRestricted()) {
                throw badRequest("频道禁止转发：" + displaySource(resolved));
            }
            if (!sourceIds.add(resolved.sourceId())) {
                throw badRequest("频道重复：" + displaySource(resolved));
            }
            channels.add(new ChannelConfig(
                    StringUtils.hasText(input.id()) ? input.id().trim() : UUID.randomUUID().toString(),
                    resolved.sourceId(), resolved.title(), resolved.username(), input.enabled(),
                    input.percentile(), input.resourceMode(), input.minVideoDuration(),
                    input.minViews(), input.minForwards(), input.minAgeHours()
            ));
        }
        if (request.enabled() && channels.stream().noneMatch(ChannelConfig::enabled)) {
            throw badRequest("启用每日追更前至少需要启用一个频道");
        }
        ConfigResponse config = new ConfigResponse(
                request.enabled(), request.target().trim(), SCHEDULE_TIME, TIMEZONE, List.copyOf(channels)
        );
        settingMapper.upsertSetting(CONFIG_KEY, writeJson(config));
        return config;
    }

    public RunResponse requestFollowDryRun() {
        User admin = authService.requireAdminUser();
        ConfigResponse config = loadConfig();
        List<ChannelConfig> channels = enabledChannels(config);
        TelegramAutomationRun run = createRun("MANUAL", admin.getId(), "FOLLOW_DRY_RUN", channels.size());
        executor.submit(() -> executeFollowDryRun(run, config, channels));
        return toResponse(run, true);
    }

    public RunResponse requestFollowExecution() {
        User admin = authService.requireAdminUser();
        ConfigResponse config = loadConfig();
        List<ChannelConfig> channels = enabledChannels(config);
        TelegramAutomationRun run = createRun("MANUAL", admin.getId(), "FOLLOW", channels.size());
        executor.submit(() -> executeFollow(run, config, channels));
        return toResponse(run, true);
    }

    public RunResponse requestBackfillDryRun(String channelId, BackfillRequest request) {
        User admin = authService.requireAdminUser();
        ConfigResponse config = loadConfig();
        ChannelConfig channel = channel(config, channelId);
        TelegramAutomationRun run = createRun("MANUAL", admin.getId(), "BACKFILL_DRY_RUN", 1);
        executor.submit(() -> executeBackfillDryRun(run, config, channel, request));
        return toResponse(run, true);
    }

    public RunResponse requestBackfillExecution(String channelId, BackfillRequest request) {
        User admin = authService.requireAdminUser();
        ConfigResponse config = loadConfig();
        ChannelConfig channel = channel(config, channelId);
        TelegramAutomationRun run = createRun("MANUAL", admin.getId(), "BACKFILL", 1);
        executor.submit(() -> executeBackfill(run, config, channel, request));
        return toResponse(run, true);
    }

    public RunListResponse listRuns(Integer page, Integer pageSize) {
        authService.requireAdminUser();
        ensureTablesReady();
        int normalizedPage = page == null ? 1 : Math.max(1, page);
        int normalizedPageSize = pageSize == null ? 20 : Math.min(50, Math.max(1, pageSize));
        int total = Math.toIntExact(runMapper.selectCount(null));
        int offset = (normalizedPage - 1) * normalizedPageSize;
        List<RunResponse> items = runMapper.selectList(new LambdaQueryWrapper<TelegramAutomationRun>()
                        .orderByDesc(TelegramAutomationRun::getStartedAt)
                        .last("LIMIT " + normalizedPageSize + " OFFSET " + offset))
                .stream().map(run -> toResponse(run, false)).toList();
        return new RunListResponse(items, total, normalizedPage, normalizedPageSize);
    }

    public RunResponse getRun(String runId) {
        authService.requireAdminUser();
        ensureTablesReady();
        TelegramAutomationRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Telegram 自动化运行记录不存在", HttpStatus.NOT_FOUND);
        }
        return toResponse(run, true);
    }

    private void startScheduledFollowRun(ConfigResponse config) {
        List<ChannelConfig> channels = enabledChannels(config);
        TelegramAutomationRun run = createRun("SCHEDULED", null, "FOLLOW", channels.size());
        executor.submit(() -> executeFollow(run, config, channels));
    }

    private TelegramAutomationRun createRun(
            String triggerType,
            Long userId,
            String executionMode,
            int channelCount
    ) {
        ensureTablesReady();
        runCreationLock.lock();
        try {
            if (currentRun() != null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "已有 Telegram 自动化任务正在运行");
            }
            TelegramAutomationRun run = new TelegramAutomationRun();
            run.setId(UUID.randomUUID().toString());
            run.setTriggerType(triggerType);
            run.setTriggeredByUserId(userId);
            run.setExecutionMode(executionMode);
            run.setStatus("RUNNING");
            run.setStage("QUEUED");
            run.setChannelCount(channelCount);
            run.setSucceededChannelCount(0);
            run.setFailedChannelCount(0);
            run.setSelectedResourceCount(0);
            run.setDuplicateResourceCount(0);
            run.setForwardedResourceCount(0);
            run.setForwardedMessageCount(0);
            run.setResultJson("[]");
            run.setStartedAt(LocalDateTime.now());
            run.setCreatedAt(LocalDateTime.now());
            runMapper.insert(run);
            return run;
        } finally {
            runCreationLock.unlock();
        }
    }

    private void executeFollowDryRun(
            TelegramAutomationRun run,
            ConfigResponse config,
            List<ChannelConfig> channels
    ) {
        List<ChannelRunResponse> results = new ArrayList<>();
        run.setStage("FORWARDING_CHANNELS");
        persist(run, results);
        for (ChannelConfig channel : channels) {
            String requestId = run.getId() + ":" + channel.id() + ":FOLLOW";
            ObjectNode body = followBody(config.target(), channel, requestId);
            body.put("dryRun", true);
            CallOutcome outcome = callWithRetries(() -> workerClient.forwardUnread(body));
            results.add(channelResult(channel, outcome));
            persist(run, results);
        }
        finish(run, results);
    }

    private void executeFollow(
            TelegramAutomationRun run,
            ConfigResponse config,
            List<ChannelConfig> channels
    ) {
        List<ChannelRunResponse> results = new ArrayList<>();
        run.setStage("FORWARDING_CHANNELS");
        persist(run, results);
        for (ChannelConfig channel : channels) {
            String requestId = run.getId() + ":" + channel.id() + ":FOLLOW";
            ObjectNode body = followBody(config.target(), channel, requestId);
            body.put("dryRun", false);
            CallOutcome outcome = callWithRetries(() -> workerClient.forwardUnread(body));
            results.add(channelResult(channel, outcome));
            persist(run, results);
        }
        finish(run, results);
    }

    private void executeBackfillDryRun(
            TelegramAutomationRun run,
            ConfigResponse config,
            ChannelConfig channel,
            BackfillRequest request
    ) {
        List<ChannelRunResponse> results = new ArrayList<>();
        run.setStage("BACKFILLING_CHANNEL");
        persist(run, results);
        String requestId = run.getId() + ":" + channel.id() + ":BACKFILL";
        ObjectNode body = backfillBody(config.target(), channel, request, requestId);
        body.put("dryRun", true);
        CallOutcome outcome = callWithRetries(() -> workerClient.backfill(body));
        results.add(channelResult(channel, outcome));
        finish(run, results);
    }

    private void executeBackfill(
            TelegramAutomationRun run,
            ConfigResponse config,
            ChannelConfig channel,
            BackfillRequest request
    ) {
        List<ChannelRunResponse> results = new ArrayList<>();
        run.setStage("BACKFILLING_CHANNEL");
        persist(run, results);
        String requestId = run.getId() + ":" + channel.id() + ":BACKFILL";
        ObjectNode body = backfillBody(config.target(), channel, request, requestId);
        body.put("dryRun", false);
        CallOutcome outcome = callWithRetries(() -> workerClient.backfill(body));
        results.add(channelResult(channel, outcome));
        finish(run, results);
    }

    private CallOutcome callWithRetries(Supplier<JsonNode> call) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return new CallOutcome(call.get(), null, attempt);
            } catch (TelegramWorkerClientException exception) {
                if (!exception.isRetryable() || attempt == MAX_ATTEMPTS) {
                    return new CallOutcome(null, exception.getMessage(), attempt);
                }
                long delaySeconds = Math.max(
                        RETRY_INTERVAL.toSeconds(),
                        exception.getRetryAfterSeconds() == null ? 0 : exception.getRetryAfterSeconds()
                );
                log.warn(
                        "Telegram Worker call failed, retrying attempt={}/{} delay={}s reason={}",
                        attempt, MAX_ATTEMPTS, delaySeconds, exception.getMessage()
                );
                try {
                    TimeUnit.SECONDS.sleep(delaySeconds);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return new CallOutcome(null, "Telegram 自动化重试等待被中断", attempt);
                }
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private ChannelRunResponse channelResult(ChannelConfig channel, CallOutcome outcome) {
        JsonNode response = outcome.response();
        return new ChannelRunResponse(
                channel.id(), channel.sourceId(), channel.sourceTitle(),
                response == null ? "FAILED" : "SUCCEEDED", outcome.attemptCount(),
                intValue(response, "selectedResourceCount"),
                intValue(response, "duplicateResourceCount"),
                intValue(response, "forwardedResourceCount"),
                intValue(response, "forwardedMessageCount"),
                response != null && response.path("threshold").isNumber()
                        ? response.path("threshold").asDouble()
                        : null,
                response, outcome.errorMessage()
        );
    }

    private ObjectNode followBody(String target, ChannelConfig channel, String requestId) {
        ObjectNode body = baseBody(target, channel, requestId);
        body.put("percentile", channel.percentile());
        body.put("baselineSize", 100);
        body.put("minAgeHours", channel.minAgeHours());
        body.put("markRead", false);
        return body;
    }

    private ObjectNode backfillBody(
            String target,
            ChannelConfig channel,
            BackfillRequest request,
            String requestId
    ) {
        ObjectNode body = baseBody(target, channel, requestId);
        body.put("topResources", request.topResources());
        body.put("lookbackDays", request.lookbackDays());
        body.put("maxMessages", request.maxMessages());
        body.put("startMode", request.startMode());
        return body;
    }

    private ObjectNode baseBody(String target, ChannelConfig channel, String requestId) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("source", channel.sourceId());
        body.put("target", target);
        body.put("minVideoDuration", channel.minVideoDuration());
        body.put("minViews", channel.minViews());
        body.put("minForwards", channel.minForwards());
        body.put("resourceMode", channel.resourceMode());
        body.put("requestId", requestId);
        return body;
    }

    private List<ChannelConfig> enabledChannels(ConfigResponse config) {
        List<ChannelConfig> channels = config.channels().stream().filter(ChannelConfig::enabled).toList();
        if (channels.isEmpty()) {
            throw badRequest("没有已启用的 Telegram 频道");
        }
        return channels;
    }

    private ChannelConfig channel(ConfigResponse config, String channelId) {
        return config.channels().stream()
                .filter(candidate -> candidate.id().equals(channelId))
                .findFirst()
                .orElseThrow(() -> badRequest("Telegram 频道配置不存在"));
    }

    private void finish(TelegramAutomationRun run, List<ChannelRunResponse> results) {
        long succeeded = results.stream().filter(result -> "SUCCEEDED".equals(result.status())).count();
        String status = succeeded == results.size() ? "SUCCEEDED" : succeeded == 0 ? "FAILED" : "PARTIAL_SUCCESS";
        run.setStatus(status);
        run.setStage(status);
        run.setFinishedAt(LocalDateTime.now());
        if (!"SUCCEEDED".equals(status)) {
            run.setErrorMessage(results.stream()
                    .filter(result -> result.errorMessage() != null)
                    .map(result -> result.sourceTitle() + "：" + result.errorMessage())
                    .findFirst().orElse("部分 Telegram 频道处理失败"));
        }
        persist(run, results);
    }

    private void persist(TelegramAutomationRun run, List<ChannelRunResponse> results) {
        run.setSucceededChannelCount((int) results.stream()
                .filter(result -> "SUCCEEDED".equals(result.status())).count());
        run.setFailedChannelCount((int) results.stream()
                .filter(result -> "FAILED".equals(result.status())).count());
        run.setSelectedResourceCount(results.stream().mapToInt(ChannelRunResponse::selectedResourceCount).sum());
        run.setDuplicateResourceCount(results.stream().mapToInt(ChannelRunResponse::duplicateResourceCount).sum());
        run.setForwardedResourceCount(results.stream().mapToInt(ChannelRunResponse::forwardedResourceCount).sum());
        run.setForwardedMessageCount(results.stream().mapToInt(ChannelRunResponse::forwardedMessageCount).sum());
        run.setResultJson(writeJson(results));
        runMapper.updateById(run);
    }

    private ResolvedSourceResponse resolveSourceWithoutAuthorization(String sourceRef) {
        try {
            JsonNode source = workerClient.resolveSource(sourceRef);
            return new ResolvedSourceResponse(
                    source.path("sourceId").asLong(), source.path("title").asText(null),
                    source.path("username").asText(null), source.path("forwardsRestricted").asBoolean(false)
            );
        } catch (TelegramWorkerClientException exception) {
            if (exception.isRetryable()) {
                throw new BusinessException(
                        ErrorCode.SERVICE_UNAVAILABLE,
                        exception.getMessage(),
                        HttpStatus.SERVICE_UNAVAILABLE
                );
            }
            throw badRequest(exception.getMessage());
        }
    }

    private ConfigResponse loadConfig() {
        ensureTablesReady();
        String raw = settingMapper.selectSettingValue(CONFIG_KEY);
        if (!StringUtils.hasText(raw)) {
            return new ConfigResponse(false, "@PikPak_Bot", SCHEDULE_TIME, TIMEZONE, List.of());
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            List<ChannelConfig> channels = objectMapper.convertValue(
                    root.path("channels"), new TypeReference<List<ChannelConfig>>() { }
            );
            return new ConfigResponse(
                    root.path("enabled").asBoolean(false),
                    root.path("target").asText("@PikPak_Bot"),
                    SCHEDULE_TIME, TIMEZONE, channels == null ? List.of() : channels
            );
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            log.warn("Invalid Telegram automation config, using defaults", exception);
            return new ConfigResponse(false, "@PikPak_Bot", SCHEDULE_TIME, TIMEZONE, List.of());
        }
    }

    private RunResponse toResponse(TelegramAutomationRun run, boolean includeDetails) {
        List<ChannelRunResponse> channels = includeDetails
                ? readChannelResults(run.getResultJson())
                : List.of();
        return new RunResponse(
                run.getId(), run.getTriggerType(), run.getTriggeredByUserId(), run.getExecutionMode(),
                run.getStatus(), run.getStage(), count(run.getChannelCount()),
                count(run.getSucceededChannelCount()), count(run.getFailedChannelCount()),
                count(run.getSelectedResourceCount()), count(run.getDuplicateResourceCount()),
                count(run.getForwardedResourceCount()), count(run.getForwardedMessageCount()),
                run.getErrorMessage(), run.getStartedAt(), run.getFinishedAt(), channels
        );
    }

    private List<ChannelRunResponse> readChannelResults(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<List<ChannelRunResponse>>() { });
        } catch (JsonProcessingException exception) {
            log.warn("Invalid Telegram automation run result runJsonLength={}", value.length());
            return List.of();
        }
    }

    private TelegramAutomationRun currentRun() {
        return runMapper.selectOne(new LambdaQueryWrapper<TelegramAutomationRun>()
                .eq(TelegramAutomationRun::getStatus, "RUNNING")
                .orderByDesc(TelegramAutomationRun::getStartedAt)
                .last("LIMIT 1"));
    }

    private void ensureTablesReady() {
        if (tablesReady) {
            return;
        }
        synchronized (this) {
            if (!tablesReady) {
                settingMapper.createTableIfNotExists();
                runMapper.createTableIfNotExists();
                tablesReady = true;
            }
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Telegram 自动化数据序列化失败", exception);
        }
    }

    private int intValue(JsonNode node, String field) {
        return node == null ? 0 : node.path(field).asInt(0);
    }

    private int count(Integer value) {
        return value == null ? 0 : value;
    }

    private String displaySource(ResolvedSourceResponse source) {
        return StringUtils.hasText(source.title()) ? source.title() : String.valueOf(source.sourceId());
    }

    private BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }

    private record CallOutcome(JsonNode response, String errorMessage, int attemptCount) {
    }
}
