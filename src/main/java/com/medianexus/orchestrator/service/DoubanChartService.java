package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.common.exception.*;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.*;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistOperationContract.Entry;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistOperationContract.Search;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistOperationContract.Task;
import com.medianexus.orchestrator.dto.douban.DoubanChartContract.*;
import com.medianexus.orchestrator.dto.resources.response.ProwlarrReleaseItemResponse;
import com.medianexus.orchestrator.integration.douban.DoubanChartClient;
import com.medianexus.orchestrator.mapper.UserMapper;
import com.medianexus.orchestrator.model.User;
import com.medianexus.orchestrator.service.DoubanChartStore.Run;
import com.medianexus.orchestrator.service.DoubanChartEligibility.Assessment;
import com.medianexus.orchestrator.service.BgmWishlistTaskGateway.ActiveTask;
import com.medianexus.orchestrator.service.BgmWishlistTaskGateway.Target;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 公共榜单完整应用操作；单个有界后台执行器，不复用个人想看的恢复存储。 */
@Service
public class DoubanChartService {
    private static final Logger log = LoggerFactory.getLogger(DoubanChartService.class);
    private static final LocalTime RUN_TIME = LocalTime.of(9, 0);
    private final DoubanChartStore store;
    private final DoubanChartClient client;
    private final DoubanChartEligibility eligibility;
    private final BgmWishlistTaskGateway tasks;
    private final BgmWishlistSessionLocks locks;
    private final AuthService auth;
    private final UserMapper users;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), runnable -> {
                Thread thread = new Thread(runnable, "douban-chart-ingest"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final Set<String> enqueued = ConcurrentHashMap.newKeySet();

    public DoubanChartService(DoubanChartStore store, DoubanChartClient client, DoubanChartEligibility eligibility,
                              BgmWishlistTaskGateway tasks, BgmWishlistSessionLocks locks, AuthService auth, UserMapper users) {
        this.store = store; this.client = client; this.eligibility = eligibility;
        this.tasks = tasks; this.locks = locks; this.auth = auth; this.users = users;
    }

    public Overview overview() {
        auth.requireCurrentUser();
        return new Overview(Arrays.stream(ChartId.values()).map(this::readBoard).toList(), readSettings());
    }
    public Board board(ChartId id) { auth.requireCurrentUser(); return readBoard(id); }
    private Board readBoard(ChartId id) {
        Board board = store.board(id);
        List<Candidate> candidates = board.candidates().stream().map(candidate -> {
            Identity corrected = store.identity(candidate.subject().id());
            Candidate visible = candidate(candidate, candidate.subject(), candidate.current(), candidate.excluded(),
                    corrected == null ? candidate.identity() : corrected, candidate.reason());
            return new Candidate(visible.subject(), visible.current(), visible.firstSeenAt(), visible.lastSeenAt(),
                    visible.lastRank(), visible.excluded(), visible.observations(), visible.identity(), visible.reason(),
                    DoubanChartProgress.visible(store.progress(id, candidate.subject().id()), candidate.subject(), corrected, Instant.now()));
        }).sorted(Comparator.comparing(Candidate::current).reversed()
                .thenComparing(candidate -> candidate.current() ? candidate.subject().rank() : candidate.lastRank(),
                        Comparator.nullsLast(Comparator.naturalOrder()))).toList();
        return board(board, candidates, nextRun());
    }
    public Board automation(ChartId id, AutoInput input) {
        User admin = auth.requireAdminUser(); requireAdminIdentity(admin.getId());
        store.updateBoard(id, previous -> new Board(id, id.label(), input.enabled(), admin.getId(), previous.refreshStatus(),
                previous.refreshError(), previous.lastAttemptAt(), previous.lastSuccessAt(), nextRun(), previous.candidates()));
        return readBoard(id);
    }
    public Settings settings(LimitsInput input) { auth.requireAdminUser(); store.limits(input); return readSettings(); }
    private Settings readSettings() {
        var limits = store.limits(); var counts = store.counts();
        return new Settings(limits.checks(), limits.creates(), counts.checked(), counts.created(), "09:00", "Asia/Shanghai", nextRun());
    }
    public Board exclusion(ChartId id, long subjectId, ExclusionInput input) {
        auth.requireAdminUser(); requireCandidate(id, subjectId);
        store.updateBoard(id, previous -> board(previous, previous.candidates().stream().map(candidate ->
                candidate.subject().id() == subjectId ? candidate(candidate, candidate.subject(), candidate.current(), input.excluded(),
                        candidate.identity(), input.excluded() ? reason("EXCLUDED", "管理员已排除") : inclusion(candidate)) : candidate).toList(), nextRun()));
        return readBoard(id);
    }
    public Board identity(ChartId id, long subjectId, IdentityInput input) {
        auth.requireAdminUser(); Candidate candidate = requireCandidate(id, subjectId);
        Identity corrected = eligibility.correct(candidate.subject(), input);
        store.identity(subjectId, corrected);
        Assessment assessment = eligibility.assess(candidate.subject(), corrected);
        updateCandidate(id, subjectId, previous -> candidate(previous, previous.subject(), previous.current(), previous.excluded(),
                corrected, previous.excluded() ? reason("EXCLUDED", "管理员已排除") : assessment.reason()));
        return readBoard(id);
    }
    public List<CatalogCandidate> search(String query, String mediaType) {
        auth.requireCurrentUser();
        if (query == null || query.isBlank() || query.length() > 200 || !Set.of("MOVIE", "SERIES").contains(mediaType)) throw bad("搜索词或目录类型无效");
        return eligibility.search(query, mediaType);
    }
    public List<Season> seasons(int tmdbId) {
        auth.requireCurrentUser(); if (tmdbId <= 0) throw bad("目录 ID 无效"); return eligibility.seasons(tmdbId);
    }
    public Board refresh(ChartId id) {
        auth.requireAdminUser();
        Board previous = store.board(id);
        store.updateBoard(id, old -> new Board(old.id(), old.label(), old.autoEnabled(), old.ownerUserId(), "REFRESHING", null,
                Instant.now(), old.lastSuccessAt(), nextRun(), old.candidates()));
        try { executor.execute(() -> refreshBoard(id)); }
        catch (RejectedExecutionException exception) {
            store.updateBoard(id, old -> new Board(old.id(), old.label(), old.autoEnabled(), old.ownerUserId(), previous.refreshStatus(),
                    "刷新队列繁忙，请稍后再试", old.lastAttemptAt(), old.lastSuccessAt(), nextRun(), old.candidates()));
        }
        return readBoard(id);
    }


    private Board refreshBoard(ChartId id) {
        return locks.withLock("douban-refresh:" + id.name(), Busy::new, () -> {
            Instant attempted = Instant.now();
            try {
                List<Subject> fetched = client.fetch(id);
                Instant success = Instant.now();
                store.updateBoard(id, previous -> {
                    Map<Long, Subject> current = new LinkedHashMap<>();
                    for (Subject subject : fetched) current.put(subject.id(), subject);
                    List<Candidate> merged = new ArrayList<>();
                    for (Candidate old : previous.candidates()) {
                        Subject subject = current.remove(old.subject().id());
                        if (subject == null) merged.add(candidate(old, old.subject(), false, old.excluded(), old.identity(), old.reason()));
                        else merged.add(observed(old, subject, true, success));
                    }
                    for (Subject subject : current.values()) merged.add(new Candidate(subject, true, success, success, subject.rank(),
                            false, List.of(new Observation(success, subject.progress())), null, reason("CURRENT_CANDIDATE", "当前在榜，执行时核验资格")));
                    return new Board(id, id.label(), previous.autoEnabled(), previous.ownerUserId(), "SUCCESS", null,
                            attempted, success, nextRun(), List.copyOf(merged));
                });
            } catch (RuntimeException exception) {
                log.warn("豆瓣榜单刷新失败 chart={}", id, exception);
                String failure = exception instanceof DoubanChartClient.ChartReadException ? exception.getMessage() : "榜单读取失败，请查看服务日志";
                store.updateBoard(id, previous -> new Board(id, id.label(), previous.autoEnabled(), previous.ownerUserId(), "FAILED",
                        failure + "；保留最后成功清单，本轮旧在榜不受理", attempted, previous.lastSuccessAt(), nextRun(), previous.candidates()));
            }
            // 掉榜进度独立核验，不以整榜故障或自动开关停止历史治理。
            for (Candidate old : store.board(id).candidates()) {
                if (old.current()) continue;
                try {
                    Subject fresh = client.refreshSubject(old.subject());
                    updateCandidate(id, old.subject().id(), candidate -> observed(candidate, fresh, false, Instant.now()));
                } catch (RuntimeException exception) {
                    updateCandidate(id, old.subject().id(), candidate -> candidate(candidate, candidate.subject(), false,
                            candidate.excluded(), candidate.identity(), reason("SOURCE_UNAVAILABLE", "历史作品进度核验失败，保留旧信息")));
                }
            }
            return store.board(id);
        });
    }

    public Operation execute(ChartId id) {
        User admin = auth.requireAdminUser(); requireAdminIdentity(admin.getId());
        Run run = store.submit(id, admin.getId(), "MANUAL"); enqueue(run.operation().id()); return run.operation();
    }
    public Operation get(String id) { auth.requireCurrentUser(); return live(store.run(id).operation()); }
    public Operations operations(ChartId id, int page, int size) {
        auth.requireCurrentUser(); if (page < 1 || size < 1 || size > 100) throw bad("分页参数无效");
        Operations records = store.operations(id, page, size);
        return new Operations(records.items().stream().map(this::live).toList(), records.total(), page, size);
    }
    public Operation stop(String id) {
        auth.requireAdminUser();
        return store.updateRun(id, previous -> {
            if (!DoubanChartStore.active(previous.operation().status())) return previous;
            return new Run(operation(previous.operation(), "STOPPING", previous.operation().entries(), reason("STOP_REQUESTED", "停止后不再受理新目标，已有任务保留")),
                    previous.scope(), previous.scopeCaptured(), true);
        }).operation();
    }

    private void enqueue(String id) {
        if (!enqueued.add(id)) return;
        try { executor.execute(() -> {
            try { locks.withLock("douban-run:" + id, Busy::new, () -> { run(id); return null; }); }
            catch (Busy ignored) { /* 其他实例持有本轮执行权。 */ }
            catch (RuntimeException exception) { log.error("榜单后台操作失败 operation={}", id, exception); }
            finally { enqueued.remove(id); }
        }); }
        catch (RejectedExecutionException exception) {
            enqueued.remove(id);
            store.updateRun(id, previous -> new Run(operation(previous.operation(), "INTERRUPTED", previous.operation().entries(),
                    reason("EXECUTOR_BUSY", "后台队列已满或正在关闭，尚未继续受理")), previous.scope(), previous.scopeCaptured(), previous.stopRequested()));
        }
    }

    private void run(String id) {
        Run initial = store.run(id);
        if (!DoubanChartStore.active(initial.operation().status())) return;
        // 一个实例崩溃后只能在获得同一 DB 会话锁时恢复；绝不重放 CREATING 或未处理目标。
        if (initial.stopRequested() && initial.operation().entries().isEmpty()) { finish(id); return; }
        if (!"QUEUED".equals(initial.operation().status())) { reconcile(id); return; }
        try {
            store.updateRun(id, previous -> new Run(operation(previous.operation(), previous.stopRequested() ? "STOPPING" : "RUNNING",
                    previous.operation().entries(), null), previous.scope(), previous.scopeCaptured(), previous.stopRequested()));
            requireAdminIdentity(initial.operation().actorUserId());
            Board fresh = refreshBoard(initial.operation().chartId());
            List<Candidate> scope = fresh.candidates().stream().map(candidate -> candidate(candidate, candidate.subject(), candidate.current(),
                    candidate.excluded(), candidate.identity(), candidate.excluded() ? reason("EXCLUDED", "管理员已排除") : inclusion(candidate))).toList();
            store.updateRun(id, previous -> new Run(operation(previous.operation(), previous.operation().status(), scope.stream()
                    .map(candidate -> new Entry(item(candidate.subject(), candidate.identity(), candidate.reason()), "QUEUED", null, List.of(), List.of(), null, null,
                            candidate.reason(), null)).toList(), "SUCCESS".equals(fresh.refreshStatus()) ? null
                            : reason("CHART_REFRESH_FAILED", "本轮榜单抓取失败，当前在榜作品未搜索资源、未创建任务。" + fresh.refreshError()
                            + "；来源恢复后可手动发起新一轮，本记录不会自动重跑。")), scope, true, previous.stopRequested()));
            Set<String> targets = new HashSet<>();
            for (Candidate candidate : scope) {
                if (store.run(id).stopRequested()) break;
                process(id, candidate, fresh, targets);
            }
            finish(id);
        } catch (BusinessException exception) {
            reconcile(id);
            store.updateRun(id, previous -> new Run(operation(previous.operation(), "INTERRUPTED", previous.operation().entries(),
                    reason("EXECUTION_PAUSED", exception.getMessage())), previous.scope(), previous.scopeCaptured(), previous.stopRequested()));
        } catch (RuntimeException exception) {
            log.warn("榜单操作中断 operation={}", id, exception);
            reconcile(id);
        }
    }

    private void process(String id, Candidate snapshot, Board fresh, Set<String> targets) {
        long subjectId = snapshot.subject().id(); ChartId chart = fresh.id();
        try {
            if (snapshot.excluded()) { outcome(id, subjectId, "SKIPPED", snapshot.reason()); return; }
            if (snapshot.current() && !"SUCCESS".equals(fresh.refreshStatus())) {
                outcome(id, subjectId, "SKIPPED", reason("SOURCE_UNAVAILABLE", "本轮榜单抓取失败，旧在榜只展示不受理")); return;
            }
            Subject original = snapshot.subject();
            Subject subject = !snapshot.current() || "SERIES".equals(original.mediaType())
                    && (original.progress() == null || original.progress().isBlank()) ? client.refreshSubject(original) : original;
            if (!snapshot.current()) updateCandidate(chart, subjectId, candidate -> observed(candidate, subject, false, Instant.now()));
            Assessment assessment = eligibility.assess(subject, store.identity(subjectId));
            updateCandidate(chart, subjectId, candidate -> candidate(candidate, candidate.subject(), candidate.current(), candidate.excluded(),
                    assessment.identity(), candidate.excluded() ? reason("EXCLUDED", "管理员已排除") : assessment.reason() == null ? inclusion(candidate) : assessment.reason()));
            updateEntry(id, subjectId, entry -> new Entry(item(subject, assessment.identity(), snapshot.reason()), "CHECKING", Instant.now(),
                    entry.searches(), entry.candidates(), entry.selected(), entry.task(), entry.reason(), null));
            if (assessment.reason() != null || assessment.target() == null) {
                outcome(id, subjectId, "SKIPPED", assessment.reason() == null ? reason("IDENTITY_UNKNOWN", "目录目标待确认") : assessment.reason()); return;
            }
            Target target = assessment.target();
            if (!targets.add(targetKey(target))) { outcome(id, subjectId, "SKIPPED", reason("DUPLICATE_TARGET", "本轮已检查同一目录目标")); return; }
            ActiveTask existing = store.recordedTask(targetKey(target));
            if (existing == null) existing = tasks.previousTask(target);
            if (existing == null) existing = tasks.activeTask(target);
            if (existing != null) { associate(id, subjectId, existing, "ASSOCIATED", BgmWishlistTaskGateway.previousTaskReason(existing.status())); return; }
            if (!store.reserveCheck()) { outcome(id, subjectId, "SKIPPED", reason("DAILY_CHECK_LIMIT", "榜单共享每日检查数已达上限")); return; }
            updateEntry(id, subjectId, entry -> entry(entry, "SEARCHING", entry.reason()));
            List<ProwlarrReleaseItemResponse> releases;
            try {
                releases = tasks.candidates(target, "1080p", progress -> updateEntry(id, subjectId, entry -> {
                    List<Search> searches = new ArrayList<>(entry.searches());
                    searches.removeIf(search -> Objects.equals(search.query(), progress.query()) && Objects.equals(search.source(), progress.source()));
                    searches.add(new Search(progress.query(), progress.source(), progress.status(), progress.elapsedMs(), progress.resultCount()));
                    return new Entry(entry.item(), entry.status(), entry.startedAt(), List.copyOf(searches), entry.candidates(), entry.selected(), entry.task(), entry.reason(), null);
                }));
            } catch (ProwlarrReleaseIngestService.NoMatchingReleaseException exception) {
                List<com.medianexus.orchestrator.dto.bgm.BgmWishlistOperationContract.Candidate> candidates = releaseCandidates(exception.candidates());
                updateEntry(id, subjectId, entry -> new Entry(entry.item(), "NO_RELEASE".equals(exception.reasonCode()) ? "WAITING_RESOURCE" : "WAITING_CONFIRMATION",
                        entry.startedAt(), entry.searches(), candidates, null, null, reason(exception.reasonCode(), exception.getMessage()), null)); return;
            }
            List<com.medianexus.orchestrator.dto.bgm.BgmWishlistOperationContract.Candidate> candidates = releaseCandidates(releases);
            var selected = candidates.stream().filter(candidate -> safe(candidate.release()) && eligibility.completeRelease(assessment, candidate.release())).findFirst().orElse(null);
            updateEntry(id, subjectId, entry -> new Entry(entry.item(), selected == null ? candidates.isEmpty() ? "WAITING_RESOURCE" : "WAITING_CONFIRMATION" : "SELECTED",
                    entry.startedAt(), entry.searches(), candidates, selected, null, selected == null ? reason(candidates.isEmpty() ? "NO_RELEASE" : "REVIEW_REQUIRED",
                            candidates.isEmpty() ? "未找到资源，留待下一轮重搜" : "发布安全或整季覆盖证据不足，未创建任务") : entry.reason(), null));
            if (selected == null) return;
            if (store.run(id).stopRequested()) return;
            Candidate latest = requireCandidate(chart, subjectId);
            if (latest.excluded()) { outcome(id, subjectId, "SKIPPED", reason("EXCLUDED", "管理员已排除")); return; }
            Subject verifiedSubject = client.refreshSubject(subject);
            Assessment verified = eligibility.assess(verifiedSubject, store.identity(subjectId));
            if (verified.reason() != null || verified.target() == null || !targetKey(target).equals(targetKey(verified.target()))
                    || !eligibility.completeRelease(verified, selected.release())) {
                outcome(id, subjectId, "SKIPPED", verified.reason() == null ? reason("TARGET_CHANGED", "最终资格或目录目标已变化") : verified.reason()); return;
            }
            if (requireCandidate(chart, subjectId).excluded()) {
                outcome(id, subjectId, "SKIPPED", reason("EXCLUDED", "最终核验期间管理员已排除")); return;
            }
            User owner = requireAdminIdentity(store.run(id).operation().actorUserId());
            String reservation = store.reserveCreate();
            if (reservation == null) { outcome(id, subjectId, "SKIPPED", reason("DAILY_CREATE_LIMIT", "榜单共享每日创建数已达上限")); return; }
            Run admitted = store.updateRun(id, previous -> {
                if (previous.stopRequested()) return previous;
                return replaceEntry(previous, subjectId, entry -> entry(entry, "CREATING", entry.reason()));
            });
            if (admitted.stopRequested()) { store.releaseCreate(reservation); return; }
            if (!store.claimTarget(targetKey(target), id)) {
                store.releaseCreate(reservation);
                outcome(id, subjectId, "INTERRUPTED", reason("CREATE_UNCERTAIN", "同一目标曾有未核实的创建尝试，请在任务中心核实，禁止自动重放")); return;
            }
            try {
                ActiveTask created = "SERIES".equals(target.mediaType()) ? tasks.createChartSeries(target, selected.release(), owner)
                        : tasks.createChartMovie(target, selected.release(), owner);
                store.recordTask(targetKey(target), created);
                store.resolveTarget(targetKey(target));
                associate(id, subjectId, created, "CREATED", reason("TASK_CREATED", "已创建入库任务，不表示已入库"));
            } catch (BgmWishlistTaskGateway.CreatedTaskRecordException exception) {
                store.recordTask(targetKey(target), exception.task());
                associate(id, subjectId, exception.task(), "CREATED", reason("TASK_LINK_PENDING", "任务已创建，目标账本写入异常；保留已知任务，禁止重放"));
            } catch (BgmWishlistTaskGateway.EligibilityRefusal refusal) {
                store.releaseCreate(reservation); store.resolveTarget(targetKey(target));
                if (refusal.task() == null) outcome(id, subjectId, "SKIPPED", refusal.reason());
                else associate(id, subjectId, refusal.task(), "ASSOCIATED", refusal.reason());
            } catch (RuntimeException exception) {
                outcome(id, subjectId, "INTERRUPTED", reason("CREATE_UNCERTAIN", "创建结果待核实，禁止自动重放；请查看任务中心"));
            }
        } catch (RuntimeException exception) {
            log.warn("榜单候选核验失败 chart={} subject={}", chart, subjectId, exception);
            Entry current = find(store.run(id).operation(), subjectId);
            outcome(id, subjectId, "CREATING".equals(current.status()) ? "INTERRUPTED" : "FAILED",
                    reason("CREATING".equals(current.status()) ? "CREATE_UNCERTAIN" : "CHECK_UNAVAILABLE", "检查或外部服务不可用，未继续受理；不是无资源"));
        }
    }

    private void reconcile(String id) {
        Run run = store.run(id);
        for (Entry entry : run.operation().entries()) {
            if ("CREATING".equals(entry.status())) {
                ActiveTask known = null;
                if (entry.item().identity() != null) {
                    Target target = target(entry.item().identity());
                    try {
                        known = store.recordedTask(targetKey(target));
                        if (known == null) known = tasks.previousTask(target);
                        if (known == null) known = tasks.activeTask(target);
                        if (known != null) store.recordTask(targetKey(target), known);
                        else store.claimTarget(targetKey(target), id);
                    }
                    catch (RuntimeException exception) { log.warn("榜单创建恢复核验失败 operation={}", id, exception); }
                }
                if (known != null) associate(id, entry.item().subjectId(), known, "ASSOCIATED", reason("RECOVERED_TASK", "已恢复明确任务关联，不重新创建"));
                else outcome(id, entry.item().subjectId(), "INTERRUPTED", reason("CREATE_UNCERTAIN", "创建副作用未知，禁止自动重放，请核实任务中心"));
            } else if (Set.of("QUEUED", "CHECKING", "SEARCHING", "SELECTED").contains(entry.status())) {
                outcome(id, entry.item().subjectId(), "INTERRUPTED", reason("RUN_INTERRUPTED", "运行中断，未继续受理"));
            }
        }
        store.updateRun(id, previous -> new Run(operation(previous.operation(), "INTERRUPTED", previous.operation().entries(),
                reason("RUN_INTERRUPTED", "运行已中断；明确任务已保留，未知副作用不重放")), previous.scope(), previous.scopeCaptured(), previous.stopRequested()));
    }

    private void finish(String id) {
        store.updateRun(id, previous -> {
            List<Entry> entries = previous.operation().entries().stream().map(entry -> "QUEUED".equals(entry.status()) || "SELECTED".equals(entry.status())
                    ? entry(entry, "SKIPPED", reason("STOPPED", "本轮已停止，未创建任务")) : entry).toList();
            String status = previous.stopRequested() ? "STOPPED" : entries.stream().anyMatch(entry -> "INTERRUPTED".equals(entry.status())) ? "INTERRUPTED"
                    : entries.stream().anyMatch(entry -> "FAILED".equals(entry.status())) ? "PARTIAL_SUCCESS" : "SUCCEEDED";
            return new Run(chartReadOutcome(operation(previous.operation(), status, entries, previous.stopRequested() ? reason("STOPPED", "已停止新受理，已有任务保留") : previous.operation().reason())),
                    previous.scope(), previous.scopeCaptured(), previous.stopRequested());
        });
    }

    /** 每日认领是数据库唯一记录，重复实例/重启不重复当天同榜定时动作。 */
    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Shanghai")
    public void daily() {
        try { executor.execute(() -> {
            for (ChartId id : ChartId.values()) {
                try {
                    var work = store.claimDailyWork(id, LocalDate.now(DoubanChartStore.ZONE));
                    if (work == null) continue;
                    if (work.run() == null) { refreshBoard(id); continue; }
                    Run run = work.run();
                    // 已在同一有界执行器中，不再次入队。
                    locks.withLock("douban-run:" + run.operation().id(), Busy::new, () -> {
                        if (!"QUEUED".equals(store.run(run.operation().id()).operation().status())) return null;
                        run(run.operation().id()); return null;
                    });
                } catch (RuntimeException exception) { log.warn("豆瓣榜单每日操作暂停 chart={}", id, exception); }
            }
        }); } catch (RejectedExecutionException exception) { log.warn("豆瓣每日刷新未入队：执行器繁忙"); }
    }
    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        try {
            for (Run run : store.open()) {
                if ("QUEUED".equals(run.operation().status())) enqueue(run.operation().id());
                else {
                    try { locks.withLock("douban-run:" + run.operation().id(), Busy::new, () -> {
                        if (DoubanChartStore.active(store.run(run.operation().id()).operation().status())) reconcile(run.operation().id());
                        return null;
                    }); }
                    catch (Busy ignored) { /* 另一实例仍执行，不干扰其状态。 */ }
                }
            }
            executor.execute(() -> {
                for (ChartId chart : ChartId.values()) {
                    try {
                        if ("NEVER".equals(store.board(chart).refreshStatus())) refreshBoard(chart);
                    } catch (RuntimeException exception) {
                        log.warn("豆瓣榜单首次采集未完成 chart={}", chart, exception);
                    }
                }
            });
        } catch (RuntimeException exception) { log.warn("豆瓣榜单持久状态恢复不可用，未重放任务", exception); }
    }
    @PreDestroy public void close() { executor.shutdown(); }

    private Operation live(Operation operation) {
        List<Entry> entries = operation.entries().stream().map(entry -> {
            if (entry.task() == null) return entry;
            try {
                Task current = tasks.currentTask(entry.task().taskType(), entry.task().taskId());
                if (current != null) return new Entry(entry.item(), entry.status(), entry.startedAt(), entry.searches(), entry.candidates(), entry.selected(), current, entry.reason(), entry.notification());
            } catch (RuntimeException exception) { log.debug("榜单任务摘要暂不可读 task={}", entry.task().taskId()); }
            return entry;
        }).toList();
        return chartReadOutcome(new Operation(operation.id(), operation.chartId(), operation.chartLabel(), operation.triggerType(), operation.actorUserId(),
                operation.startedAt(), operation.updatedAt(), operation.status(), entries, operation.reason()));
    }
    /** 兼容旧记录的错误成功汇总；不改写持久条目、执行时间或触发重放。 */
    private static Operation chartReadOutcome(Operation operation) {
        if (!Set.of("SUCCEEDED", "PARTIAL_SUCCESS").contains(operation.status())) return operation;
        boolean failed = operation.reason() != null && "CHART_REFRESH_FAILED".equals(operation.reason().code())
                || operation.entries().stream().anyMatch(entry -> entry.reason() != null && "SOURCE_UNAVAILABLE".equals(entry.reason().code())
                && entry.item().reasons().stream().anyMatch(inclusion -> "CURRENT_CANDIDATE".equals(inclusion.code())));
        if (!failed) return operation;
        boolean processed = operation.entries().stream().anyMatch(entry -> !"SKIPPED".equals(entry.status()));
        Reason failure = operation.reason() != null && "CHART_REFRESH_FAILED".equals(operation.reason().code()) ? operation.reason()
                : reason("CHART_REFRESH_FAILED", "本轮榜单抓取失败，当前在榜作品未搜索资源、未创建任务。来源恢复后可手动发起新一轮；本记录不会自动重跑。");
        return new Operation(operation.id(), operation.chartId(), operation.chartLabel(), operation.triggerType(), operation.actorUserId(),
                operation.startedAt(), operation.updatedAt(), processed ? "PARTIAL_SUCCESS" : "FAILED", operation.entries(), failure);
    }
    private void associate(String id, long subjectId, ActiveTask task, String status, Reason reason) {
        updateEntry(id, subjectId, entry -> new Entry(entry.item(), status, entry.startedAt(), entry.searches(), entry.candidates(), entry.selected(),
                new Task(task.taskType(), task.taskId(), task.status(), null, null, null), reason, null));
    }
    private void outcome(String id, long subjectId, String status, Reason reason) { updateEntry(id, subjectId, entry -> entry(entry, status, reason)); }
    private void updateEntry(String id, long subjectId, UnaryOperator<Entry> change) { store.updateRun(id, previous -> replaceEntry(previous, subjectId, change)); }
    private Run replaceEntry(Run run, long subjectId, UnaryOperator<Entry> change) {
        List<Entry> entries = run.operation().entries().stream().map(entry -> entry.item().subjectId() == subjectId ? change.apply(entry) : entry).toList();
        return new Run(operation(run.operation(), run.operation().status(), entries, run.operation().reason()), run.scope(), run.scopeCaptured(), run.stopRequested());
    }
    private Entry find(Operation operation, long subjectId) { return operation.entries().stream().filter(entry -> entry.item().subjectId() == subjectId).findFirst().orElseThrow(); }
    private Candidate requireCandidate(ChartId id, long subjectId) {
        return store.board(id).candidates().stream().filter(candidate -> candidate.subject().id() == subjectId).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "本榜候选不存在", HttpStatus.NOT_FOUND));
    }
    private void updateCandidate(ChartId id, long subjectId, UnaryOperator<Candidate> change) {
        store.updateBoard(id, previous -> board(previous, previous.candidates().stream().map(candidate -> candidate.subject().id() == subjectId ? change.apply(candidate) : candidate).toList(), nextRun()));
    }
    private User requireAdminIdentity(Long id) {
        User owner = id == null ? null : users.selectById(id);
        if (owner == null || !"ADMIN".equals(owner.getRole())) throw new BusinessException(ErrorCode.FORBIDDEN, "持久管理员执行身份已失效，暂停受理", HttpStatus.FORBIDDEN);
        return owner;
    }
    private static Candidate observed(Candidate old, Subject subject, boolean current, Instant now) {
        List<Observation> observations = new ArrayList<>(old.observations()); observations.add(new Observation(now, subject.progress()));
        return new Candidate(subject, current, old.firstSeenAt(), current ? now : old.lastSeenAt(), current ? subject.rank() : old.lastRank(), old.excluded(),
                List.copyOf(observations), old.identity(), old.excluded() ? reason("EXCLUDED", "管理员已排除") : current
                        ? reason("CURRENT_CANDIDATE", "当前在榜，执行时核验资格") : historyReason(subject, observations));
    }
    private static Candidate candidate(Candidate old, Subject subject, boolean current, boolean excluded, Identity identity, Reason reason) {
        return new Candidate(subject, current, old.firstSeenAt(), old.lastSeenAt(), old.lastRank(), excluded, old.observations(), identity, reason);
    }
    private static Board board(Board old, List<Candidate> candidates, Instant next) {
        return new Board(old.id(), old.label(), old.autoEnabled(), old.ownerUserId(), old.refreshStatus(), old.refreshError(), old.lastAttemptAt(), old.lastSuccessAt(), next, candidates);
    }
    private static Operation operation(Operation old, String status, List<Entry> entries, Reason reason) {
        return new Operation(old.id(), old.chartId(), old.chartLabel(), old.triggerType(), old.actorUserId(), old.startedAt(), Instant.now(), status, entries, reason);
    }
    private static Entry entry(Entry old, String status, Reason reason) { return new Entry(old.item(), status, old.startedAt(), old.searches(), old.candidates(), old.selected(), old.task(), reason, old.notification()); }
    private static Item item(Subject subject, Identity identity, Reason inclusion) {
        return new Item(subject.id(), subject.title(), subject.originalTitle(), subject.coverUrl(), subject.year(),
                subject.observedAt() == null ? null : subject.observedAt().toString(), identity, "UNKNOWN", "UNKNOWN", false,
                inclusion == null ? List.of() : List.of(inclusion), null, true, "NOT_CHECKED", null, null, false);
    }
    private static Reason inclusion(Candidate candidate) {
        return candidate.current() ? reason("CURRENT_CANDIDATE", "本轮当前在榜候选")
                : historyReason(candidate.subject(), candidate.observations());
    }
    private static Reason historyReason(Subject subject, List<Observation> observations) {
        boolean complete = subject.progress() != null && subject.progress().matches("\\s*[1-9][0-9]{0,3}\\s*集全\\s*");
        boolean wasAiring = observations.stream().anyMatch(observation -> observation.progress() != null
                && observation.progress().matches("\\s*更新至\\s*[1-9][0-9]{0,3}\\s*集\\s*"));
        String message = "MOVIE".equals(subject.mediaType()) || complete
                ? complete && wasAiring ? "历史连载现已确认完结" : "历史完结作品重搜资源"
                : "历史连载或进度未知，继续每日检查";
        return reason("HISTORICAL_CANDIDATE", message);
    }
    private static boolean safe(ProwlarrReleaseItemResponse release) {
        return release.evidence() != null && "RECOMMENDABLE".equals(release.evidence().status()) && !release.evidence().camera();
    }
    private static List<com.medianexus.orchestrator.dto.bgm.BgmWishlistOperationContract.Candidate> releaseCandidates(List<ProwlarrReleaseItemResponse> releases) {
        return releases.stream().map(release -> new com.medianexus.orchestrator.dto.bgm.BgmWishlistOperationContract.Candidate(UUID.randomUUID().toString(), release, false)).toList();
    }
    private static Target target(Identity identity) { return new Target(identity.mediaType(), identity.tmdbId(), identity.seasonNumber(), identity.title(), identity.originalTitle(), identity.year()); }
    private static String targetKey(Target target) { return target.mediaType() + ":" + target.tmdbId() + ":" + target.seasonNumber(); }
    private static Instant nextRun() {
        ZonedDateTime now = ZonedDateTime.now(DoubanChartStore.ZONE); ZonedDateTime next = now.toLocalDate().atTime(RUN_TIME).atZone(DoubanChartStore.ZONE);
        return (next.isAfter(now) ? next : next.plusDays(1)).toInstant();
    }
    private static Reason reason(String code, String message) { return new Reason(code, message); }
    private static BusinessException bad(String message) { return new BusinessException(ErrorCode.BAD_REQUEST, message); }
    private static class Busy extends BusinessException { Busy() { super(ErrorCode.CONFLICT, "榜单操作正在另一实例处理中，请稍后读取", HttpStatus.CONFLICT); } }
}
