package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.dto.javdb.request.JavdbAutomationConfigUpdateRequest;
import com.medianexus.orchestrator.dto.javdb.request.JavdbCookieUpdateRequest;
import com.medianexus.orchestrator.dto.javdb.response.JavdbAutomationConfigResponse;
import com.medianexus.orchestrator.dto.javdb.response.JavdbAutomationOverviewResponse;
import com.medianexus.orchestrator.dto.javdb.response.JavdbAutomationRunItemResponse;
import com.medianexus.orchestrator.dto.javdb.response.JavdbAutomationRunListResponse;
import com.medianexus.orchestrator.dto.javdb.response.JavdbAutomationRunLogResponse;
import com.medianexus.orchestrator.dto.javdb.response.JavdbAutomationRunResponse;
import com.medianexus.orchestrator.dto.javdb.response.JavdbCredentialStatusResponse;
import com.medianexus.orchestrator.dto.javdb.response.JavdbMagnetCandidateResponse;
import com.medianexus.orchestrator.dto.javdb.response.JavdbRankingAppearanceResponse;
import com.medianexus.orchestrator.integration.emby.EmbyClient;
import com.medianexus.orchestrator.integration.emby.EmbyClientException;
import com.medianexus.orchestrator.integration.emby.EmbyItem;
import com.medianexus.orchestrator.integration.emby.EmbyLibrary;
import com.medianexus.orchestrator.integration.javdb.JavdbClient;
import com.medianexus.orchestrator.integration.javdb.JavdbClientException;
import com.medianexus.orchestrator.integration.javdb.JavdbMagnet;
import com.medianexus.orchestrator.integration.javdb.JavdbMovieDetail;
import com.medianexus.orchestrator.integration.javdb.JavdbRankingMovie;
import com.medianexus.orchestrator.mapper.AdultMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.JavdbAutomationLedgerMapper;
import com.medianexus.orchestrator.mapper.JavdbAutomationRunItemMapper;
import com.medianexus.orchestrator.mapper.JavdbAutomationRunLogMapper;
import com.medianexus.orchestrator.mapper.JavdbAutomationRunMapper;
import com.medianexus.orchestrator.mapper.SystemSettingMapper;
import com.medianexus.orchestrator.model.AdultMagnetIngestTask;
import com.medianexus.orchestrator.model.JavdbAutomationLedger;
import com.medianexus.orchestrator.model.JavdbAutomationRun;
import com.medianexus.orchestrator.model.JavdbAutomationRunItem;
import com.medianexus.orchestrator.model.JavdbAutomationRunLog;
import com.medianexus.orchestrator.model.User;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.http.HttpStatus;

/**
 * Owns the JAVDB ranking workflow, its durable audit trail, and the small
 * amount of scheduling state needed by the first implementation.
 */
@Service
public class JavdbAutomationService {

    private static final Logger log = LoggerFactory.getLogger(JavdbAutomationService.class);
    private static final String CONFIG_KEY = "javdb_automation_config";
    private static final String COOKIE_KEY = "javdb_automation_cookie";
    private static final String VALIDATION_KEY = "javdb_automation_cookie_validation";
    private static final String TIMEZONE = "Asia/Shanghai";
    private static final ZoneId ZONE_ID = ZoneId.of(TIMEZONE);
    private static final String DEFAULT_SCHEDULE_TIME = "03:00";
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;
    private static final int BATCH_SIZE = 50;
    private static final long DETAIL_REQUEST_DELAY_MILLIS = 1000L;
    private static final String ADULT_JAV_SOURCE = "JAVDB_AUTOMATION";
    private static final String ADULT_JAV_LIBRARY_NAME = "Adult-JAV";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final Pattern CODE_PATTERN = Pattern.compile(
            "(?<![A-Z0-9])([A-Z]{2,12})[-_ ]?(\\d{2,7})(?![A-Z0-9])",
            Pattern.CASE_INSENSITIVE
    );
    private static final Set<String> RUNNING_STATUSES = Set.of("RUNNING");
    private static final Set<String> ACTIVE_ADULT_STATUSES = Set.of(
            "PENDING", "SUBMITTED", "DOWNLOADING", "ORGANIZING"
    );

    private final AuthService authService;
    private final SystemSettingMapper systemSettingMapper;
    private final JavdbClient javdbClient;
    private final EmbyClient embyClient;
    private final AdultMagnetIngestService adultMagnetIngestService;
    private final AdultMagnetIngestTaskMapper adultTaskMapper;
    private final JavdbAutomationRunMapper runMapper;
    private final JavdbAutomationRunItemMapper itemMapper;
    private final JavdbAutomationLedgerMapper ledgerMapper;
    private final JavdbAutomationRunLogMapper logMapper;
    private final ObjectMapper objectMapper;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "javdb-automation-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final ReentrantLock runCreationLock = new ReentrantLock();
    private volatile boolean tablesReady;
    private volatile LocalDate lastScheduledDate;

    public JavdbAutomationService(
            AuthService authService,
            SystemSettingMapper systemSettingMapper,
            JavdbClient javdbClient,
            EmbyClient embyClient,
            AdultMagnetIngestService adultMagnetIngestService,
            AdultMagnetIngestTaskMapper adultTaskMapper,
            JavdbAutomationRunMapper runMapper,
            JavdbAutomationRunItemMapper itemMapper,
            JavdbAutomationLedgerMapper ledgerMapper,
            JavdbAutomationRunLogMapper logMapper,
            ObjectMapper objectMapper
    ) {
        this.authService = authService;
        this.systemSettingMapper = systemSettingMapper;
        this.javdbClient = javdbClient;
        this.embyClient = embyClient;
        this.adultMagnetIngestService = adultMagnetIngestService;
        this.adultTaskMapper = adultTaskMapper;
        this.runMapper = runMapper;
        this.itemMapper = itemMapper;
        this.ledgerMapper = ledgerMapper;
        this.logMapper = logMapper;
        this.objectMapper = objectMapper;
    }

    private void ensureTablesReady() {
        if (tablesReady) {
            return;
        }
        synchronized (this) {
            if (tablesReady) {
                return;
            }
            runMapper.createTableIfNotExists();
            itemMapper.createTableIfNotExists();
            ledgerMapper.createTableIfNotExists();
            logMapper.createTableIfNotExists();
            adultMagnetIngestService.ensureTablesReadyForInternalUse();
            tablesReady = true;
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void markUnfinishedRunsInterrupted() {
        ensureTablesReady();
        int interrupted = runMapper.update(
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<JavdbAutomationRun>()
                        .eq(JavdbAutomationRun::getStatus, "RUNNING")
                        .set(JavdbAutomationRun::getStatus, "INTERRUPTED")
                        .set(JavdbAutomationRun::getStage, "INTERRUPTED")
                        .set(JavdbAutomationRun::getErrorMessage, "服务重启，JAVDB 自动化运行已中断")
                        .set(JavdbAutomationRun::getFinishedAt, LocalDateTime.now())
        );
        if (interrupted > 0) {
            log.info("Marked unfinished JAVDB automation runs interrupted count={}", interrupted);
        }
    }

    /**
     * Checks once a minute so a changed database setting takes effect without
     * rebuilding a Spring scheduled task. A missed minute is intentionally not
     * replayed after downtime.
     */
    @Scheduled(cron = "0 * * * * *", zone = TIMEZONE)
    public void runScheduledIfDue() {
        Config config = loadConfig();
        LocalDateTime now = LocalDateTime.now(ZONE_ID).withSecond(0).withNano(0);
        if (!config.enabled() || !config.scheduleTime().equals(now.toLocalTime().format(TIME_FORMATTER))) {
            return;
        }
        if (now.toLocalDate().equals(lastScheduledDate)) {
            return;
        }
        lastScheduledDate = now.toLocalDate();
        requestScheduledRun();
    }

    public JavdbAutomationOverviewResponse overview() {
        authService.requireAdminUser();
        ensureTablesReady();
        JavdbAutomationRun latest = runMapper.selectOne(new LambdaQueryWrapper<JavdbAutomationRun>()
                .orderByDesc(JavdbAutomationRun::getStartedAt)
                .last("LIMIT 1"));
        JavdbAutomationRun current = currentRun();
        return new JavdbAutomationOverviewResponse(
                toConfigResponse(),
                latest == null ? null : toResponse(latest, false),
                current == null ? null : toResponse(current, false)
        );
    }

    public JavdbAutomationConfigResponse updateConfig(JavdbAutomationConfigUpdateRequest request) {
        authService.requireAdminUser();
        if (request == null) {
            throw badRequest("JAVDB 自动化配置不能为空");
        }
        Config config = new Config(
                Boolean.TRUE.equals(request.enabled()),
                Boolean.TRUE.equals(request.dailyEnabled()),
                Boolean.TRUE.equals(request.weeklyEnabled()),
                Boolean.TRUE.equals(request.monthlyEnabled()),
                Boolean.TRUE.equals(request.crackedOnly()),
                Boolean.TRUE.equals(request.subtitleOnly()),
                request.limitPerRanking() == null ? DEFAULT_LIMIT : request.limitPerRanking(),
                StringUtils.hasText(request.scheduleTime()) ? request.scheduleTime() : DEFAULT_SCHEDULE_TIME,
                TIMEZONE
        );
        validateConfig(config);
        if (config.enabled()) {
            ensureCanEnable();
        }
        saveConfig(config);
        return toConfigResponse();
    }

    public JavdbCredentialStatusResponse updateCookie(JavdbCookieUpdateRequest request) {
        authService.requireAdminUser();
        if (request == null || !StringUtils.hasText(request.cookie())) {
            throw badRequest("JAVDB Cookie 不能为空");
        }
        String cookie = request.cookie().trim();
        systemSettingMapper.upsertSetting(COOKIE_KEY, cookie);
        ValidationState validationState;
        try {
            javdbClient.validate(cookie);
            validationState = new ValidationState(true, LocalDateTime.now(), "JAVDB Cookie 验证成功");
        } catch (JavdbClientException exception) {
            validationState = new ValidationState(false, LocalDateTime.now(), safeCredentialMessage(exception));
            if (exception.reason() == JavdbClientException.Reason.AUTHENTICATION) {
                disableAfterInvalidCredential();
            }
        }
        saveValidation(validationState);
        return toCredentialStatus(validationState);
    }

    public JavdbAutomationRunResponse requestDryRun(JavdbAutomationConfigUpdateRequest request) {
        User admin = authService.requireAdminUser();
        Config config = request == null ? loadConfig() : configFromRequest(request);
        validateConfig(config);
        return startRun("MANUAL", admin.getId(), "DRY_RUN", config);
    }

    public JavdbAutomationRunResponse requestExecution(JavdbAutomationConfigUpdateRequest request) {
        User admin = authService.requireAdminUser();
        Config config = request == null ? loadConfig() : configFromRequest(request);
        validateConfig(config);
        return startRun("MANUAL", admin.getId(), "EXECUTE", config);
    }

    private Config configFromRequest(JavdbAutomationConfigUpdateRequest request) {
        return new Config(
                Boolean.TRUE.equals(request.enabled()),
                Boolean.TRUE.equals(request.dailyEnabled()),
                Boolean.TRUE.equals(request.weeklyEnabled()),
                Boolean.TRUE.equals(request.monthlyEnabled()),
                Boolean.TRUE.equals(request.crackedOnly()),
                Boolean.TRUE.equals(request.subtitleOnly()),
                request.limitPerRanking(), request.scheduleTime(), TIMEZONE
        );
    }

    public JavdbAutomationRunListResponse listRuns(Integer page, Integer pageSize) {
        authService.requireAdminUser();
        ensureTablesReady();
        int normalizedPage = page == null ? 1 : Math.max(1, page);
        int normalizedPageSize = pageSize == null ? 20 : Math.min(50, Math.max(1, pageSize));
        int total = Math.toIntExact(runMapper.selectCount(null));
        int offset = (normalizedPage - 1) * normalizedPageSize;
        List<JavdbAutomationRun> runs = runMapper.selectList(new LambdaQueryWrapper<JavdbAutomationRun>()
                .orderByDesc(JavdbAutomationRun::getStartedAt)
                .last("LIMIT " + normalizedPageSize + " OFFSET " + offset));
        return new JavdbAutomationRunListResponse(
                runs.stream().map(run -> toResponse(run, false)).toList(),
                total,
                normalizedPage,
                normalizedPageSize
        );
    }

    public JavdbAutomationRunResponse getRun(String runId) {
        authService.requireAdminUser();
        ensureTablesReady();
        JavdbAutomationRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "自动化运行记录不存在", HttpStatus.NOT_FOUND);
        }
        return toResponse(run, true);
    }

    private void requestScheduledRun() {
        Config config = loadConfig();
        startRun("SCHEDULED", null, "EXECUTE", config);
    }

    private JavdbAutomationRunResponse startRun(
            String triggerType,
            Long triggeredByUserId,
            String executionMode,
            Config config
    ) {
        ensureTablesReady();
        runCreationLock.lock();
        try {
            JavdbAutomationRun active = currentRun();
            if (active != null) {
                if ("SCHEDULED".equals(triggerType)) {
                    JavdbAutomationRun skipped = newRun(triggerType, null, executionMode, config);
                    skipped.setStatus("SKIPPED");
                    skipped.setStage("SKIPPED");
                    skipped.setErrorMessage("已有 JAVDB 自动化运行中，本次计划跳过");
                    skipped.setFinishedAt(LocalDateTime.now());
                    runMapper.insert(skipped);
                    writeLog(skipped.getId(), "INFO", "SKIPPED", "已有运行中任务，本次计划跳过", null);
                    return toResponse(skipped, false);
                }
                return toResponse(active, false);
            }
            JavdbAutomationRun run = newRun(triggerType, triggeredByUserId, executionMode, config);
            runMapper.insert(run);
            writeLog(run.getId(), "INFO", "CREATED", "已创建 JAVDB 自动化运行", "mode=" + executionMode);
            try {
                executorService.submit(() -> executeRun(run.getId()));
            } catch (RuntimeException exception) {
                markFailed(run.getId(), "自动化任务调度失败，请稍后重试");
            }
            return toResponse(run, false);
        } finally {
            runCreationLock.unlock();
        }
    }

    private JavdbAutomationRun newRun(
            String triggerType,
            Long triggeredByUserId,
            String executionMode,
            Config config
    ) {
        JavdbAutomationRun run = new JavdbAutomationRun();
        run.setId(UUID.randomUUID().toString());
        run.setTriggerType(triggerType);
        run.setTriggeredByUserId(triggeredByUserId);
        run.setExecutionMode(executionMode);
        run.setStatus("RUNNING");
        run.setStage("CREATED");
        run.setConfigSnapshot(writeJson(config));
        run.setRankingEntries(0);
        run.setUniqueMovies(0);
        run.setDuplicateEntriesRemoved(0);
        run.setAlreadyInEmby(0);
        run.setHistoryDuplicates(0);
        run.setActiveDuplicates(0);
        run.setRemainingMovies(0);
        run.setSubmittedCount(0);
        run.setAdultTaskCount(0);
        run.setStartedAt(LocalDateTime.now());
        run.setCreatedAt(LocalDateTime.now());
        return run;
    }

    private void executeRun(String runId) {
        JavdbAutomationRun run = runMapper.selectById(runId);
        if (run == null) {
            return;
        }
        Config config = readConfigSnapshot(run.getConfigSnapshot());
        try {
            if ("EXECUTE".equals(run.getExecutionMode()) && executeFromLatestDryRun(run, config)) {
                return;
            }
            executePipeline(run, config);
            if ("DRY_RUN".equals(run.getExecutionMode()) && "SUCCEEDED".equals(run.getStatus())) {
                saveValidation(new ValidationState(true, LocalDateTime.now(), "JAVDB Cookie 验证成功"));
            }
        } catch (JavdbClientException exception) {
            if (exception.reason() == JavdbClientException.Reason.AUTHENTICATION) {
                saveValidation(new ValidationState(
                        false,
                        LocalDateTime.now(),
                        "JAVDB Cookie 已失效，请更新登录凭证"
                ));
                disableAfterInvalidCredential();
            }
            markFailed(runId, safeRunMessage(exception));
        } catch (EmbyClientException exception) {
            markFailed(runId, "Emby Adult-JAV 查重不可用，请修复后重试");
        } catch (RuntimeException exception) {
            log.warn("JAVDB automation run failed id={}", runId, exception);
            markFailed(runId, safeRunMessage(exception));
        }
    }

    private void executePipeline(JavdbAutomationRun run, Config config) {
        String cookie = loadCookie();
        if (!StringUtils.hasText(cookie)) {
            throw new JavdbClientException(JavdbClientException.Reason.AUTHENTICATION, "JAVDB Cookie 未配置");
        }
        updateStage(run, "FETCHING_RANKINGS");
        Map<String, List<JavdbRankingMovie>> rankings = fetchRankings(config, cookie);
        int rankingEntries = rankings.values().stream().mapToInt(List::size).sum();
        LinkedHashMap<String, MergedMovie> mergedMovies = mergeRankings(rankings);
        run.setRankingEntries(rankingEntries);
        run.setUniqueMovies(mergedMovies.size());
        run.setDuplicateEntriesRemoved(rankingEntries - mergedMovies.size());
        persistRun(run);
        writeLog(run.getId(), "INFO", "FETCHING_RANKINGS", "榜单抓取完成",
                "rankingEntries=" + rankingEntries + ", uniqueMovies=" + mergedMovies.size());

        updateStage(run, "CHECKING_EMBY");
        Set<String> embyCodes = loadAdultJavCodes();
        Map<String, JavdbAutomationLedger> ledgerByCode = ledgerByCode();
        Map<String, AdultMagnetIngestTask> activeTasksByCode = activeTasksByCode(ledgerByCode);
        Map<String, AdultMagnetIngestTask> submittedAutomationTasksByCode = submittedAutomationTasksByCode();
        List<MergedMovie> detailCandidates = new ArrayList<>();
        for (MergedMovie movie : mergedMovies.values()) {
            String crossRankReason = movie.appearances().size() > 1 ? "CROSS_RANK_DUPLICATE" : null;
            if (embyCodes.contains(movie.code())) {
                saveItem(run, movie, "ALREADY_IN_EMBY", crossRankReason, null, null, null, null);
                run.setAlreadyInEmby(safeCount(run.getAlreadyInEmby()) + 1);
                continue;
            }
            JavdbAutomationLedger ledger = ledgerByCode.get(movie.code());
            if (ledger != null) {
                boolean active = activeTasksByCode.containsKey(movie.code());
                saveItem(run, movie, active ? "ADULT_IN_PROGRESS" : "HISTORY_SUBMITTED", crossRankReason,
                        null, null, null, ledger.getAdultTaskId());
                if (active) {
                    run.setActiveDuplicates(safeCount(run.getActiveDuplicates()) + 1);
                } else {
                    run.setHistoryDuplicates(safeCount(run.getHistoryDuplicates()) + 1);
                }
                continue;
            }
            AdultMagnetIngestTask activeTask = activeTasksByCode.get(movie.code());
            if (activeTask != null) {
                saveItem(run, movie, "ADULT_IN_PROGRESS", crossRankReason,
                        null, null, null, activeTask.getId());
                run.setActiveDuplicates(safeCount(run.getActiveDuplicates()) + 1);
                continue;
            }
            AdultMagnetIngestTask submittedTask = submittedAutomationTasksByCode.get(movie.code());
            if (submittedTask != null) {
                saveItem(run, movie, "HISTORY_SUBMITTED", crossRankReason,
                        null, null, null, submittedTask.getId());
                run.setHistoryDuplicates(safeCount(run.getHistoryDuplicates()) + 1);
                continue;
            }
            detailCandidates.add(movie);
        }
        run.setRemainingMovies(detailCandidates.size());
        persistRun(run);

        updateStage(run, "FETCHING_DETAILS");
        List<PendingSubmission> pendingSubmissions = new ArrayList<>();
        boolean hasItemFailure = false;
        for (int detailIndex = 0; detailIndex < detailCandidates.size(); detailIndex++) {
            MergedMovie movie = detailCandidates.get(detailIndex);
            try {
                if (detailIndex > 0) {
                    LockSupport.parkNanos(DETAIL_REQUEST_DELAY_MILLIS * 1_000_000L);
                }
                JavdbMovieDetail detail = javdbClient.detail(movie.detailUrl(), movie.code(), cookie);
                JavdbMagnet selected = selectMagnet(detail.magnets(), config);
                if (selected == null) {
                    saveItem(run, movie, "NO_MAGNET", reasonFor(movie), detail.magnets(), null, null, null);
                    hasItemFailure = true;
                    continue;
                }
                String selectionReason = selectionReason(selected);
                saveItem(run, movie, "READY_TO_SUBMIT", reasonFor(movie), detail.magnets(), selected,
                        selectionReason, null);
                pendingSubmissions.add(new PendingSubmission(movie, detail.magnets(), selected, selectionReason));
            } catch (JavdbClientException exception) {
                if (exception.reason() == JavdbClientException.Reason.AUTHENTICATION) {
                    throw exception;
                }
                saveItem(run, movie, "DETAIL_FAILED", reasonFor(movie), null, null, null, null,
                        safeRunMessage(exception));
                hasItemFailure = true;
                writeLog(run.getId(), "WARN", "FETCHING_DETAILS", "单部详情获取失败",
                        "code=" + movie.code() + ", reason=" + safeRunMessage(exception));
            }
        }
        persistRun(run);

        if ("DRY_RUN".equals(run.getExecutionMode())) {
            finishRun(run, hasItemFailure ? "PARTIAL_SUCCESS" : "SUCCEEDED",
                    hasItemFailure ? "试运行完成，部分影片详情处理失败" : "试运行完成，未创建 Adult 任务");
            return;
        }
        if (pendingSubmissions.isEmpty()) {
            finishRun(run, hasItemFailure ? "PARTIAL_SUCCESS" : "SUCCEEDED",
                    hasItemFailure ? "同步完成，部分影片详情处理失败" : "同步成功，无新增内容");
            return;
        }

        updateStage(run, "SUBMITTING_ADULT_TASKS");
        submitPendingSubmissions(run, pendingSubmissions, hasItemFailure);
    }

    private void submitPendingSubmissions(
            JavdbAutomationRun run,
            List<PendingSubmission> pendingSubmissions,
            boolean hasItemFailure
    ) {
        boolean hasSubmissionFailure = false;
        int successfulTaskCount = 0;
        for (int start = 0; start < pendingSubmissions.size(); start += BATCH_SIZE) {
            List<PendingSubmission> batch = pendingSubmissions.subList(
                    start,
                    Math.min(start + BATCH_SIZE, pendingSubmissions.size())
            );
            List<String> magnets = batch.stream().map(item -> item.selectedMagnet().magnet()).toList();
            String adultTaskId;
            try {
                adultTaskId = adultMagnetIngestService.createAutomationTask(magnets, run.getId()).id();
                successfulTaskCount++;
                for (PendingSubmission item : batch) {
                    updateItemAfterSubmission(run.getId(), item.movie().code(), "SUBMITTED", adultTaskId, null);
                    insertLedger(run, item, adultTaskId);
                }
                run.setSubmittedCount(safeCount(run.getSubmittedCount()) + batch.size());
                run.setAdultTaskCount(successfulTaskCount);
                writeLog(run.getId(), "INFO", "SUBMITTING_ADULT_TASKS", "Adult 批量任务已创建",
                        "taskId=" + adultTaskId + ", count=" + batch.size());
            } catch (RuntimeException exception) {
                hasSubmissionFailure = true;
                for (PendingSubmission item : batch) {
                    updateItemAfterSubmission(run.getId(), item.movie().code(), "SUBMIT_FAILED", null,
                            safeRunMessage(exception));
                }
                writeLog(run.getId(), "ERROR", "SUBMITTING_ADULT_TASKS", "Adult 批量任务创建失败",
                        "count=" + batch.size() + ", reason=" + safeRunMessage(exception));
            }
            persistRun(run);
        }
        String status = hasItemFailure || hasSubmissionFailure ? "PARTIAL_SUCCESS" : "SUCCEEDED";
        String message = hasSubmissionFailure
                ? "同步部分成功，失败批次下次可重试"
                : hasItemFailure ? "同步部分成功，部分影片处理失败" : "同步成功";
        finishRun(run, status, message);
    }

    private boolean executeFromLatestDryRun(JavdbAutomationRun run, Config config) {
        JavdbAutomationRun dryRun = runMapper.selectOne(new LambdaQueryWrapper<JavdbAutomationRun>()
                .eq(JavdbAutomationRun::getExecutionMode, "DRY_RUN")
                .eq(JavdbAutomationRun::getStatus, "SUCCEEDED")
                .orderByDesc(JavdbAutomationRun::getFinishedAt)
                .last("LIMIT 1"));
        if (dryRun == null || !Objects.equals(readConfigSnapshot(dryRun.getConfigSnapshot()), config)) {
            return false;
        }

        List<JavdbAutomationRunItem> sourceItems = itemMapper.selectList(new LambdaQueryWrapper<JavdbAutomationRunItem>()
                .eq(JavdbAutomationRunItem::getRunId, dryRun.getId())
                .orderByAsc(JavdbAutomationRunItem::getCreatedAt));
        List<PendingSubmission> pending = new ArrayList<>();
        for (JavdbAutomationRunItem source : sourceItems) {
            JavdbAutomationRunItem copy = copyRunItem(source, run.getId());
            itemMapper.insert(copy);
            if ("READY_TO_SUBMIT".equals(source.getStatus()) && StringUtils.hasText(source.getSelectedMagnet())) {
                List<JavdbMagnetCandidateResponse> candidates = readJsonList(
                        source.getCandidatesJson(), new TypeReference<List<JavdbMagnetCandidateResponse>>() { }
                );
                JavdbMagnet selected = candidates.stream()
                        .filter(candidate -> Objects.equals(candidate.magnet(), source.getSelectedMagnet()))
                        .findFirst()
                        .map(this::toMagnet)
                        .orElse(new JavdbMagnet(source.getSelectedMagnet(), null, source.getSelectedInfohash(), false, false, List.of(), null));
                pending.add(new PendingSubmission(
                        new MergedMovie(source.getCode(), source.getTitle(), source.getDetailUrl(), new ArrayList<>()),
                        candidates.stream().map(this::toMagnet).toList(), selected, source.getSelectedReason()
                ));
            }
        }
        run.setRankingEntries(dryRun.getRankingEntries());
        run.setUniqueMovies(dryRun.getUniqueMovies());
        run.setDuplicateEntriesRemoved(dryRun.getDuplicateEntriesRemoved());
        run.setAlreadyInEmby(dryRun.getAlreadyInEmby());
        run.setHistoryDuplicates(dryRun.getHistoryDuplicates());
        run.setActiveDuplicates(dryRun.getActiveDuplicates());
        run.setRemainingMovies(pending.size());
        persistRun(run);
        writeLog(run.getId(), "INFO", "FETCHING_DETAILS", "复用最近一次成功试运行结果",
                "sourceRunId=" + dryRun.getId() + ", pending=" + pending.size());
        if (pending.isEmpty()) {
            finishRun(run, "SUCCEEDED", "同步成功，试运行结果无新增入库内容");
        } else {
            updateStage(run, "SUBMITTING_ADULT_TASKS");
            submitPendingSubmissions(run, pending, false);
        }
        return true;
    }

    private JavdbAutomationRunItem copyRunItem(JavdbAutomationRunItem source, String runId) {
        JavdbAutomationRunItem copy = new JavdbAutomationRunItem();
        copy.setId(UUID.randomUUID().toString());
        copy.setRunId(runId);
        copy.setCode(source.getCode());
        copy.setTitle(source.getTitle());
        copy.setDetailUrl(source.getDetailUrl());
        copy.setAppearancesJson(source.getAppearancesJson());
        copy.setStatus(source.getStatus());
        copy.setReason(source.getReason());
        copy.setCandidatesJson(source.getCandidatesJson());
        copy.setSelectedInfohash(source.getSelectedInfohash());
        copy.setSelectedMagnet(source.getSelectedMagnet());
        copy.setSelectedReason(source.getSelectedReason());
        copy.setCreatedAt(LocalDateTime.now());
        return copy;
    }

    private JavdbMagnet toMagnet(JavdbMagnetCandidateResponse candidate) {
        return new JavdbMagnet(candidate.magnet(), candidate.originalName(), candidate.infohash(),
                candidate.hasSubtitle(), candidate.cracked(), candidate.labels(), candidate.detectionSource());
    }

    private Map<String, List<JavdbRankingMovie>> fetchRankings(Config config, String cookie) {
        Map<String, List<JavdbRankingMovie>> rankings = new LinkedHashMap<>();
        if (config.dailyEnabled()) {
            rankings.put("daily", limitedRanking("daily", config.limitPerRanking(), cookie));
        }
        if (config.weeklyEnabled()) {
            rankings.put("weekly", limitedRanking("weekly", config.limitPerRanking(), cookie));
        }
        if (config.monthlyEnabled()) {
            rankings.put("monthly", limitedRanking("monthly", config.limitPerRanking(), cookie));
        }
        return rankings;
    }

    private List<JavdbRankingMovie> limitedRanking(String period, int limit, String cookie) {
        return javdbClient.ranking(period, cookie).stream().limit(limit).toList();
    }

    private LinkedHashMap<String, MergedMovie> mergeRankings(Map<String, List<JavdbRankingMovie>> rankings) {
        LinkedHashMap<String, MergedMovie> merged = new LinkedHashMap<>();
        for (List<JavdbRankingMovie> ranking : rankings.values()) {
            for (JavdbRankingMovie movie : ranking) {
                String code = normalizeCode(movie.code());
                if (!StringUtils.hasText(code)) {
                    continue;
                }
                MergedMovie existing = merged.get(code);
                if (existing == null) {
                    merged.put(code, new MergedMovie(
                            code, movie.title(), movie.detailUrl(), new ArrayList<>(List.of(movie))
                    ));
                } else {
                    existing.appearances().add(movie);
                }
            }
        }
        return merged;
    }

    private Set<String> loadAdultJavCodes() {
        List<EmbyLibrary> libraries = embyClient.listLibraries();
        List<EmbyLibrary> matchingLibraries = libraries.stream()
                .filter(candidate -> normalizedLibraryName(candidate.name()).equals(normalizedLibraryName(ADULT_JAV_LIBRARY_NAME)))
                .toList();
        if (matchingLibraries.size() != 1 || !StringUtils.hasText(matchingLibraries.get(0).id())) {
            throw new EmbyClientException("Emby Adult-JAV 媒体库必须唯一匹配");
        }
        EmbyLibrary library = matchingLibraries.get(0);
        Set<String> codes = new HashSet<>();
        for (EmbyItem item : embyClient.listLibraryVideoItems(library.id())) {
            addCodes(codes, item.name());
            String path = item.path();
            if (StringUtils.hasText(path)) {
                String normalizedPath = path.replace('\\', '/');
                String[] segments = normalizedPath.split("/");
                int from = Math.max(0, segments.length - 2);
                for (int index = from; index < segments.length; index++) {
                    addCodes(codes, segments[index]);
                }
            }
        }
        return codes;
    }

    private Map<String, JavdbAutomationLedger> ledgerByCode() {
        return ledgerMapper.selectList(new LambdaQueryWrapper<JavdbAutomationLedger>()).stream()
                .filter(ledger -> StringUtils.hasText(ledger.getCode()))
                .collect(java.util.stream.Collectors.toMap(
                        JavdbAutomationLedger::getCode,
                        ledger -> ledger,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private Map<String, AdultMagnetIngestTask> activeTasksByCode(
            Map<String, JavdbAutomationLedger> ledgerByCode
    ) {
        Map<String, AdultMagnetIngestTask> result = new HashMap<>();
        List<AdultMagnetIngestTask> activeTasks = adultTaskMapper.selectList(
                new LambdaQueryWrapper<AdultMagnetIngestTask>()
                        .eq(AdultMagnetIngestTask::getCategory, "JAV")
                        .in(AdultMagnetIngestTask::getStatus, ACTIVE_ADULT_STATUSES)
        );
        Map<String, AdultMagnetIngestTask> tasksById = activeTasks.stream()
                .collect(java.util.stream.Collectors.toMap(
                        AdultMagnetIngestTask::getId,
                        task -> task,
                        (left, right) -> left
                ));
        indexTaskCodes(result, activeTasks);
        ledgerByCode.forEach((code, ledger) -> {
            AdultMagnetIngestTask task = tasksById.get(ledger.getAdultTaskId());
            if (task != null) {
                result.put(code, task);
            }
        });
        return result;
    }

    private Map<String, AdultMagnetIngestTask> submittedAutomationTasksByCode() {
        List<AdultMagnetIngestTask> submittedTasks = adultTaskMapper.selectList(
                new LambdaQueryWrapper<AdultMagnetIngestTask>()
                        .eq(AdultMagnetIngestTask::getCategory, "JAV")
                        .eq(AdultMagnetIngestTask::getSourceType, ADULT_JAV_SOURCE)
        );
        Map<String, AdultMagnetIngestTask> result = new HashMap<>();
        indexTaskCodes(result, submittedTasks);
        return result;
    }

    private void indexTaskCodes(
            Map<String, AdultMagnetIngestTask> result,
            List<AdultMagnetIngestTask> tasks
    ) {
        for (AdultMagnetIngestTask task : tasks) {
            List<String> downloadLinks = readJsonList(
                    task.getDownloadLinksJson(), new TypeReference<List<String>>() { }
            );
            for (String downloadLink : downloadLinks) {
                Set<String> codes = new HashSet<>();
                addCodes(codes, downloadLink);
                codes.forEach(code -> result.putIfAbsent(code, task));
            }
        }
    }

    private JavdbMagnet selectMagnet(List<JavdbMagnet> magnets, Config config) {
        if (magnets == null || magnets.isEmpty()) {
            return null;
        }
        return magnets.stream()
                .filter(magnet -> !config.crackedOnly() || magnet.isCracked())
                .filter(magnet -> !config.subtitleOnly() || magnet.hasSubtitle())
                .sorted(Comparator.comparingInt(this::magnetPriority).reversed()
                        .thenComparingInt(magnets::indexOf))
                .findFirst()
                .orElse(null);
    }

    private int magnetPriority(JavdbMagnet magnet) {
        if (magnet.isCracked() && magnet.hasSubtitle()) {
            return 4;
        }
        if (magnet.hasSubtitle()) {
            return 3;
        }
        if (magnet.isCracked()) {
            return 2;
        }
        return 1;
    }

    private String selectionReason(JavdbMagnet magnet) {
        String label = magnet.isCracked() && magnet.hasSubtitle()
                ? "破解+中文字幕"
                : magnet.hasSubtitle() ? "中文字幕" : magnet.isCracked() ? "破解" : "普通";
        return "按优先级选择「" + label + "」；同级取 JAVDB 页面第一条；识别来源 filename_rule";
    }

    private String reasonFor(MergedMovie movie) {
        return movie.appearances().size() > 1 ? "CROSS_RANK_DUPLICATE" : "NEW";
    }

    private void saveItem(
            JavdbAutomationRun run,
            MergedMovie movie,
            String status,
            String reason,
            List<JavdbMagnet> magnets,
            JavdbMagnet selected,
            String selectedReason,
            String adultTaskId
    ) {
        saveItem(run, movie, status, reason, magnets, selected, selectedReason, adultTaskId, null);
    }

    private void saveItem(
            JavdbAutomationRun run,
            MergedMovie movie,
            String status,
            String reason,
            List<JavdbMagnet> magnets,
            JavdbMagnet selected,
            String selectedReason,
            String adultTaskId,
            String errorMessage
    ) {
        JavdbAutomationRunItem item = new JavdbAutomationRunItem();
        item.setId(UUID.randomUUID().toString());
        item.setRunId(run.getId());
        item.setCode(movie.code());
        item.setTitle(movie.title());
        item.setDetailUrl(movie.detailUrl());
        item.setAppearancesJson(writeJson(movie.appearances().stream()
                .map(appearance -> new JavdbRankingAppearanceResponse(
                        appearance.period(), appearance.rank(), appearance.hasMagnetBadge()))
                .toList()));
        item.setStatus(status);
        item.setReason(reason);
        item.setCandidatesJson(magnets == null ? null : writeJson(magnets.stream()
                .map(this::toCandidateResponse)
                .toList()));
        item.setSelectedInfohash(selected == null ? null : selected.infohash());
        item.setSelectedMagnet(selected == null ? null : selected.magnet());
        item.setSelectedReason(selectedReason);
        item.setAdultTaskId(adultTaskId);
        item.setErrorMessage(errorMessage);
        item.setCreatedAt(LocalDateTime.now());
        itemMapper.insert(item);
    }

    private JavdbMagnetCandidateResponse toCandidateResponse(JavdbMagnet magnet) {
        return new JavdbMagnetCandidateResponse(
                magnet.magnet(), magnet.originalName(), magnet.infohash(), magnet.hasSubtitle(),
                magnet.isCracked(), magnet.labels(), magnet.detectionSource()
        );
    }

    private void updateItemAfterSubmission(
            String runId,
            String code,
            String status,
            String adultTaskId,
            String errorMessage
    ) {
        JavdbAutomationRunItem item = itemMapper.selectOne(new LambdaQueryWrapper<JavdbAutomationRunItem>()
                .eq(JavdbAutomationRunItem::getRunId, runId)
                .eq(JavdbAutomationRunItem::getCode, code)
                .orderByDesc(JavdbAutomationRunItem::getCreatedAt)
                .last("LIMIT 1"));
        if (item == null) {
            return;
        }
        item.setStatus(status);
        item.setAdultTaskId(adultTaskId);
        item.setErrorMessage(errorMessage);
        itemMapper.updateById(item);
    }

    private void insertLedger(JavdbAutomationRun run, PendingSubmission pending, String adultTaskId) {
        JavdbAutomationLedger ledger = new JavdbAutomationLedger();
        ledger.setId(UUID.randomUUID().toString());
        ledger.setCode(pending.movie().code());
        ledger.setSelectedInfohash(pending.selectedMagnet().infohash());
        ledger.setSelectedMagnet(pending.selectedMagnet().magnet());
        ledger.setAdultTaskId(adultTaskId);
        ledger.setRunId(run.getId());
        ledger.setSubmittedAt(LocalDateTime.now());
        ledgerMapper.insert(ledger);
    }

    private void updateStage(JavdbAutomationRun run, String stage) {
        run.setStage(stage);
        persistRun(run);
    }

    private void finishRun(JavdbAutomationRun run, String status, String message) {
        run.setStatus(status);
        run.setStage(status);
        run.setErrorMessage("FAILED".equals(status) ? message : null);
        run.setFinishedAt(LocalDateTime.now());
        persistRun(run);
        writeLog(run.getId(), "SUCCEEDED".equals(status) ? "INFO" : "WARN", status, message, null);
    }

    private void markFailed(String runId, String message) {
        JavdbAutomationRun run = runMapper.selectById(runId);
        if (run == null || !RUNNING_STATUSES.contains(run.getStatus())) {
            return;
        }
        run.setStatus("FAILED");
        run.setStage("FAILED");
        run.setErrorMessage(truncate(message, 1024));
        run.setFinishedAt(LocalDateTime.now());
        persistRun(run);
        writeLog(runId, "ERROR", "FAILED", run.getErrorMessage(), null);
    }

    private void persistRun(JavdbAutomationRun run) {
        runMapper.updateById(run);
    }

    private JavdbAutomationRun currentRun() {
        return runMapper.selectOne(new LambdaQueryWrapper<JavdbAutomationRun>()
                .eq(JavdbAutomationRun::getStatus, "RUNNING")
                .orderByDesc(JavdbAutomationRun::getStartedAt)
                .last("LIMIT 1"));
    }

    private void writeLog(String runId, String level, String stage, String message, String detail) {
        JavdbAutomationRunLog entry = new JavdbAutomationRunLog();
        entry.setRunId(runId);
        entry.setLevel(level);
        entry.setStage(stage);
        entry.setMessage(truncate(message, 1024));
        entry.setDetail(truncate(detail, 4096));
        entry.setCreatedAt(LocalDateTime.now());
        logMapper.insert(entry);
    }

    private JavdbAutomationRunResponse toResponse(JavdbAutomationRun run, boolean includeDetails) {
        List<JavdbAutomationRunItemResponse> items = List.of();
        List<JavdbAutomationRunLogResponse> logs = List.of();
        if (includeDetails) {
            items = itemMapper.selectList(new LambdaQueryWrapper<JavdbAutomationRunItem>()
                            .eq(JavdbAutomationRunItem::getRunId, run.getId())
                            .orderByAsc(JavdbAutomationRunItem::getCreatedAt))
                    .stream().map(this::toItemResponse).toList();
            logs = logMapper.selectList(new LambdaQueryWrapper<JavdbAutomationRunLog>()
                            .eq(JavdbAutomationRunLog::getRunId, run.getId())
                            .orderByAsc(JavdbAutomationRunLog::getId))
                    .stream().map(this::toLogResponse).toList();
        }
        return new JavdbAutomationRunResponse(
                run.getId(), run.getTriggerType(), run.getTriggeredByUserId(), run.getExecutionMode(),
                run.getStatus(), run.getStage(), safeCount(run.getRankingEntries()), safeCount(run.getUniqueMovies()),
                safeCount(run.getDuplicateEntriesRemoved()), safeCount(run.getAlreadyInEmby()),
                safeCount(run.getHistoryDuplicates()), safeCount(run.getActiveDuplicates()),
                safeCount(run.getRemainingMovies()), safeCount(run.getSubmittedCount()), safeCount(run.getAdultTaskCount()),
                run.getErrorMessage(), run.getStartedAt(), run.getFinishedAt(), items, logs
        );
    }

    private JavdbAutomationRunItemResponse toItemResponse(JavdbAutomationRunItem item) {
        List<JavdbRankingAppearanceResponse> appearances = readJsonList(
                item.getAppearancesJson(), new TypeReference<List<JavdbRankingAppearanceResponse>>() { }
        );
        List<JavdbMagnetCandidateResponse> candidates = readJsonList(
                item.getCandidatesJson(), new TypeReference<List<JavdbMagnetCandidateResponse>>() { }
        );
        return new JavdbAutomationRunItemResponse(
                item.getCode(), item.getTitle(), item.getDetailUrl(), appearances, item.getStatus(), item.getReason(),
                candidates, item.getSelectedInfohash(), item.getSelectedMagnet(), item.getSelectedReason(),
                item.getAdultTaskId(), item.getErrorMessage()
        );
    }

    private JavdbAutomationRunLogResponse toLogResponse(JavdbAutomationRunLog entry) {
        return new JavdbAutomationRunLogResponse(
                entry.getId() == null ? 0 : entry.getId(), entry.getRunId(), entry.getLevel(), entry.getStage(),
                entry.getMessage(), entry.getDetail(), entry.getCreatedAt()
        );
    }

    private JavdbAutomationConfigResponse toConfigResponse() {
        Config config = loadConfig();
        ValidationState validation = loadValidation();
        boolean configured = StringUtils.hasText(loadCookie());
        return new JavdbAutomationConfigResponse(
                config.enabled(), config.dailyEnabled(), config.weeklyEnabled(), config.monthlyEnabled(),
                config.crackedOnly(), config.subtitleOnly(), config.limitPerRanking(), config.scheduleTime(),
                TIMEZONE, configured, validation.valid(),
                validation.validatedAt() == null ? null : validation.validatedAt().toString()
        );
    }

    private JavdbCredentialStatusResponse toCredentialStatus(ValidationState validation) {
        return new JavdbCredentialStatusResponse(
                StringUtils.hasText(loadCookie()), validation.valid(), validation.validatedAt()
        );
    }

    private Config loadConfig() {
        String raw = systemSettingMapper.selectSettingValue(CONFIG_KEY);
        if (!StringUtils.hasText(raw)) {
            return new Config(false, true, true, true, false, false, DEFAULT_LIMIT, DEFAULT_SCHEDULE_TIME, TIMEZONE);
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            return new Config(
                    node.path("enabled").asBoolean(false),
                    node.path("dailyEnabled").asBoolean(node.path("daily_enabled").asBoolean(true)),
                    node.path("weeklyEnabled").asBoolean(node.path("weekly_enabled").asBoolean(true)),
                    node.path("monthlyEnabled").asBoolean(node.path("monthly_enabled").asBoolean(true)),
                    node.path("crackedOnly").asBoolean(node.path("cracked_only").asBoolean(false)),
                    node.path("subtitleOnly").asBoolean(node.path("subtitle_only").asBoolean(false)),
                    node.path("limitPerRanking").asInt(node.path("limit_per_ranking").asInt(DEFAULT_LIMIT)),
                    node.path("scheduleTime").asText(node.path("schedule_time").asText(DEFAULT_SCHEDULE_TIME)),
                    TIMEZONE
            );
        } catch (JsonProcessingException exception) {
            log.warn("Invalid JAVDB automation config, using defaults");
            return new Config(false, true, true, true, false, false, DEFAULT_LIMIT, DEFAULT_SCHEDULE_TIME, TIMEZONE);
        }
    }

    private Config readConfigSnapshot(String raw) {
        if (!StringUtils.hasText(raw)) {
            return loadConfig();
        }
        try {
            return objectMapper.readValue(raw, Config.class);
        } catch (JsonProcessingException exception) {
            return loadConfig();
        }
    }

    private void saveConfig(Config config) {
        systemSettingMapper.upsertSetting(CONFIG_KEY, writeJson(config));
    }

    private String loadCookie() {
        return systemSettingMapper.selectSettingValue(COOKIE_KEY);
    }

    private ValidationState loadValidation() {
        String raw = systemSettingMapper.selectSettingValue(VALIDATION_KEY);
        if (!StringUtils.hasText(raw)) {
            return new ValidationState(false, null, "尚未验证 JAVDB Cookie");
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            String timestamp = node.path("validatedAt").asText(null);
            LocalDateTime validatedAt = timestamp == null ? null : LocalDateTime.parse(timestamp);
            return new ValidationState(node.path("valid").asBoolean(false), validatedAt,
                    node.path("message").asText("尚未验证 JAVDB Cookie"));
        } catch (Exception exception) {
            return new ValidationState(false, null, "尚未验证 JAVDB Cookie");
        }
    }

    private void saveValidation(ValidationState state) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("valid", state.valid());
        value.put("validatedAt", state.validatedAt() == null ? null : state.validatedAt().toString());
        value.put("message", truncate(state.message(), 256));
        systemSettingMapper.upsertSetting(VALIDATION_KEY, writeJson(value));
    }

    private void disableAfterInvalidCredential() {
        Config config = loadConfig();
        if (!config.enabled()) {
            return;
        }
        saveConfig(new Config(false, config.dailyEnabled(), config.weeklyEnabled(), config.monthlyEnabled(),
                config.crackedOnly(), config.subtitleOnly(),
                config.limitPerRanking(), config.scheduleTime(), TIMEZONE));
    }

    private void ensureCanEnable() {
        ValidationState validation = loadValidation();
        if (!StringUtils.hasText(loadCookie()) || !validation.valid()) {
            throw badRequest("请先配置并验证 JAVDB Cookie");
        }
        try {
            loadAdultJavCodes();
        } catch (EmbyClientException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "Emby Adult-JAV 不可用，请先修复连接", HttpStatus.SERVICE_UNAVAILABLE);
        }
        boolean hasSuccessfulDryRun = runMapper.selectCount(new LambdaQueryWrapper<JavdbAutomationRun>()
                .eq(JavdbAutomationRun::getExecutionMode, "DRY_RUN")
                .eq(JavdbAutomationRun::getStatus, "SUCCEEDED")) > 0;
        if (!hasSuccessfulDryRun) {
            throw badRequest("请先完成一次成功的试运行");
        }
    }

    private void validateConfig(Config config) {
        if (config.limitPerRanking() < 1 || config.limitPerRanking() > MAX_LIMIT) {
            throw badRequest("每个榜单数量必须为 1-50");
        }
        if (!config.dailyEnabled() && !config.weeklyEnabled() && !config.monthlyEnabled()) {
            throw badRequest("至少选择一个 JAVDB 榜单");
        }
        try {
            LocalTime.parse(config.scheduleTime(), TIME_FORMATTER);
        } catch (DateTimeParseException exception) {
            throw badRequest("执行时间格式必须为 HH:mm");
        }
    }

    private String normalizedLibraryName(String name) {
        return StringUtils.hasText(name)
                ? name.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT)
                : "";
    }

    private void addCodes(Set<String> codes, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        Matcher matcher = CODE_PATTERN.matcher(Normalizer.normalize(value, Normalizer.Form.NFKC).toUpperCase(Locale.ROOT));
        while (matcher.find()) {
            codes.add(matcher.group(1) + "-" + matcher.group(2));
        }
    }

    private String normalizeCode(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        Matcher matcher = CODE_PATTERN.matcher(
                Normalizer.normalize(value, Normalizer.Form.NFKC).toUpperCase(Locale.ROOT)
        );
        return matcher.find() ? matcher.group(1) + "-" + matcher.group(2) : null;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("JAVDB automation JSON could not be serialized", exception);
        }
    }

    private <T> List<T> readJsonList(String raw, TypeReference<List<T>> type) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        try {
            List<T> value = objectMapper.readValue(raw, type);
            return value == null ? List.of() : value;
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private int safeCount(Integer value) {
        return value == null ? 0 : value;
    }

    private String safeCredentialMessage(JavdbClientException exception) {
        return exception.reason() == JavdbClientException.Reason.AUTHENTICATION
                ? "JAVDB Cookie 验证失败，请更新登录凭证"
                : "JAVDB Cookie 验证请求失败，请稍后重试";
    }

    private String safeRunMessage(RuntimeException exception) {
        if (exception instanceof JavdbClientException javdbException) {
            return switch (javdbException.reason()) {
                case AUTHENTICATION -> "JAVDB 登录凭证失效或需要验证";
                case NOT_FOUND -> "JAVDB 页面不存在";
                case UPSTREAM -> "JAVDB 请求失败，请稍后重试";
                case PARSE -> "JAVDB 页面解析失败";
            };
        }
        if (exception instanceof EmbyClientException) {
            return "Emby Adult-JAV 查重不可用，请修复后重试";
        }
        return StringUtils.hasText(exception.getMessage()) ? truncate(exception.getMessage(), 1024) : "自动化运行失败";
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, HttpStatus.BAD_REQUEST);
    }

    private record Config(
            boolean enabled,
            boolean dailyEnabled,
            boolean weeklyEnabled,
            boolean monthlyEnabled,
            boolean crackedOnly,
            boolean subtitleOnly,
            int limitPerRanking,
            String scheduleTime,
            String timezone
    ) {
    }

    private record ValidationState(boolean valid, LocalDateTime validatedAt, String message) {
    }

    private record MergedMovie(
            String code,
            String title,
            String detailUrl,
            List<JavdbRankingMovie> appearances
    ) {
    }

    private record PendingSubmission(
            MergedMovie movie,
            List<JavdbMagnet> magnets,
            JavdbMagnet selectedMagnet,
            String selectionReason
    ) {
    }
}
