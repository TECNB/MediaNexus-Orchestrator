package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.common.exception.*;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.*;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistOperationContract.*;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistOperationContract.Operation;
import com.medianexus.orchestrator.dto.resources.response.ProwlarrReleaseItemResponse;
import com.medianexus.orchestrator.mapper.UserMapper;
import com.medianexus.orchestrator.model.User;
import com.medianexus.orchestrator.service.BgmWishlistSourceStore.*;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** BGM 异步受理、内容例外与最终提交；请求线程不搜索、不扣额、不创建任务。 */
@Service
public class BgmWishlistOperationService {
    private static final Logger log = LoggerFactory.getLogger(BgmWishlistOperationService.class);
    private static final Set<String> BUSY = Set.of("QUEUED", "RUNNING");
    private static final Set<String> WAITING = Set.of("WAITING_RESOURCE", "WAITING_CONFIRMATION", "DEFERRED");
    private final AuthService auth;
    private final BgmWishlistService wishlist;
    private final BgmWishlistSourceStore sources;
    private final BgmWishlistExecutionStore executions;
    private final BgmWishlistOperationStore records;
    private final BgmWishlistSessionLocks locks;
    private final BgmWishlistTaskGateway tasks;
    private final UserActionQuotaService quota;
    private final UserMapper users;
    private final IngestExceptionNotifier notifications;
    private final ExecutorService workers;
    private final Set<String> localWork = ConcurrentHashMap.newKeySet();

    @Autowired
    public BgmWishlistOperationService(AuthService auth, BgmWishlistService wishlist, BgmWishlistSourceStore sources,
            BgmWishlistExecutionStore executions, BgmWishlistOperationStore records, BgmWishlistSessionLocks locks,
            BgmWishlistTaskGateway tasks, UserActionQuotaService quota, UserMapper users, IngestExceptionNotifier notifications) {
        this(auth, wishlist, sources, executions, records, locks, tasks, quota, users, notifications,
                Executors.newFixedThreadPool(4, runnable -> { Thread thread = new Thread(runnable, "bgm-ingest"); thread.setDaemon(true); return thread; }));
    }

    BgmWishlistOperationService(AuthService auth, BgmWishlistService wishlist, BgmWishlistSourceStore sources,
            BgmWishlistExecutionStore executions, BgmWishlistOperationStore records, BgmWishlistSessionLocks locks,
            BgmWishlistTaskGateway tasks, UserActionQuotaService quota, UserMapper users, IngestExceptionNotifier notifications,
            ExecutorService workers) {
        this.auth = auth; this.wishlist = wishlist; this.sources = sources; this.executions = executions;
        this.records = records; this.locks = locks; this.tasks = tasks; this.quota = quota; this.users = users;
        this.notifications = notifications; this.workers = workers;
    }

    public Operation submit(String sourceId, List<Long> ids) {
        User actor = auth.requireAdminUser();
        if (ids == null || ids.isEmpty() || ids.stream().anyMatch(id -> id == null || id <= 0)) throw bad("请选择当前来源作品");
        return accept(sourceId, ids.size() == 1 ? "SINGLE" : "SELECTED", "MANUAL", actor.getId(), List.copyOf(new LinkedHashSet<>(ids)));
    }

    public Operation latest(String sourceId) {
        return accept(sourceId, "LATEST", "MANUAL", auth.requireAdminUser().getId(), List.of());
    }

    private Operation accept(String sourceId, String action, String trigger, Long actor, List<Long> ids) {
        Operation accepted = executions.withSourceLock(sourceId,
                () -> recordAccepted(sources.get(sourceId), action, trigger, actor, ids));
        if ("QUEUED".equals(accepted.status())) launch(accepted.id());
        return live(accepted);
    }

    /** 调用方在来源锁内完成认领；保存后才允许工作线程启动。 */
    private Operation recordAccepted(SourceState state, String action, String trigger, Long actor, List<Long> ids) {
        String sourceId = state.source().id();
        for (long id : ids) BgmWishlistSourceStore.requireSubject(state, id);
        for (Operation existing : records.open()) {
            if (!existing.source().id().equals(sourceId)) continue;
            boolean overlaps = ids.isEmpty() ? BUSY.contains(existing.status())
                    : existing.requestedIds().stream().anyMatch(ids::contains)
                        || existing.entries().stream().anyMatch(entry -> ids.contains(entry.item().subjectId())
                            && (WAITING.contains(entry.status()) || Set.of("SEARCHING", "CREATING").contains(entry.status())));
            if (overlaps) {
                if (ids.isEmpty() && !existing.action().equals(action)
                        || ids.size() > 1 && !new HashSet<>(ids).equals(new HashSet<>(existing.requestedIds()))) {
                    throw new BusinessException(ErrorCode.CONFLICT, "所选范围与已有操作重叠，请查看操作记录或仅选择其他作品", HttpStatus.CONFLICT);
                }
                return existing;
            }
        }
        Instant now = Instant.now();
        List<Entry> entries = ids.stream().map(id -> entry(snapshot(state, id), "SEARCHING", now)).toList();
        Operation operation = new Operation(UUID.randomUUID().toString(), state.source(), action, trigger, actor,
                now, now, "QUEUED", ids, entries, null);
        records.create(operation);
        return operation;
    }

    public Operation confirm(String id, CandidateInput input) {
        User actor = auth.requireAdminUser();
        Operation existing = records.get(id);
        if (!Objects.equals(existing.actorUserId(), actor.getId()) && !"SCHEDULED".equals(existing.triggerType())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "该操作不属于当前管理员", HttpStatus.FORBIDDEN);
        }
        Operation changed = records.update(id, operation -> {
            if (BUSY.contains(operation.status())) throw bad("本轮仍在执行，请等待本轮结果");
            Entry selected = find(operation, input.subjectId());
            if (!"WAITING_CONFIRMATION".equals(selected.status())) throw bad("该作品当前不需要确认");
            Candidate candidate = selected.candidates().stream().filter(value -> value.key().equals(input.candidateKey()))
                    .findFirst().orElseThrow(() -> bad("候选发布已变化，请查看当前记录"));
            return replace(operation, new Entry(selected.item(), "CREATING", selected.startedAt(), selected.searches(),
                    selected.candidates(), candidate, null, null, selected.notification()), "QUEUED");
        });
        launch(id);
        return live(changed);
    }

    public Operation supply(String id, SupplyInput input) {
        User actor = auth.requireAdminUser();
        Operation changed = records.update(id, operation -> {
            if (!Objects.equals(operation.actorUserId(), actor.getId()) && !"SCHEDULED".equals(operation.triggerType()))
                throw new BusinessException(ErrorCode.FORBIDDEN, "该操作不属于当前管理员", HttpStatus.FORBIDDEN);
            if (BUSY.contains(operation.status())) throw bad("本轮仍在执行");
            Entry previous = find(operation, input.subjectId());
            if (previous.task() != null || !WAITING.contains(previous.status())) throw bad("当前记录不能补充来源");
            Identity identity = previous.item().identity();
            if (identity == null || !identity.mediaType().equals(input.identity().mediaType())
                    || identity.tmdbId() != input.identity().tmdbId()
                    || !Objects.equals(identity.seasonNumber(), input.identity().seasonNumber())) throw bad("补充来源不能更换目标作品或季度");
            ProwlarrReleaseItemResponse release = input.release();
            if (release.title() == null || release.title().isBlank() || release.downloadRef() == null || release.downloadRef().isBlank()
                    || release.indexerId() == null && !release.downloadRef().regionMatches(true, 0, "magnet:", 0, 7)
                    || release.resolutionTags() == null) throw bad("发布来源信息不完整");
            boolean qualityMatches = release.resolutionTags().contains(operation.source().quality())
                    || release.resolutionTags().contains("2160p")
                    || "720p".equals(operation.source().quality()) && release.resolutionTags().contains("1080p")
                    || "2160p".equals(operation.source().quality()) && release.resolutionTags().contains("1080p");
            if (!qualityMatches) throw bad("补充来源不符合本次清晰度条件");
            Candidate candidate = new Candidate(candidateKey(release), release);
            return replace(operation, new Entry(previous.item(), "CREATING", Instant.now(), previous.searches(),
                    List.of(candidate), candidate, null, null, previous.notification()), "QUEUED");
        });
        launch(id); return live(changed);
    }

    public Operation retry(String id, long subjectId) {
        auth.requireAdminUser();
        Operation changed = records.update(id, operation -> {
            if (BUSY.contains(operation.status())) throw bad("本轮仍在执行");
            Entry previous = find(operation, subjectId);
            if (previous.task() != null || !Set.of("WAITING_RESOURCE", "WAITING_CONFIRMATION", "DEFERRED", "FAILED", "INTERRUPTED").contains(previous.status())) {
                throw bad("已创建任务请前往任务中心处理");
            }
            return replace(operation, entry(previous.item(), "SEARCHING", Instant.now()), "QUEUED");
        });
        launch(id); return live(changed);
    }

    public Operation defer(String id, long subjectId) {
        auth.requireAdminUser();
        return live(records.update(id, operation -> {
            Entry previous = find(operation, subjectId);
            if (BUSY.contains(operation.status()) || !WAITING.contains(previous.status())) throw bad("当前作品不能暂缓");
            return replace(operation, new Entry(previous.item(), "DEFERRED", previous.startedAt(), previous.searches(),
                    previous.candidates(), previous.selected(), null, previous.reason(), previous.notification()), "WAITING");
        }));
    }

    public Operations list(String sourceId, int page, int pageSize) {
        auth.requireAdminUser(); sources.get(sourceId);
        if (page < 1 || pageSize < 1) throw bad("分页参数必须为正整数");
        Operations values = records.list(sourceId, page, pageSize);
        return new Operations(values.items().stream().map(this::live).toList(), values.total(), page, pageSize);
    }

    public Operation get(String id) { auth.requireAdminUser(); return live(records.get(id)); }

    public Operation schedule(String sourceId, ZonedDateTime time) {
        ZonedDateTime local = time.withZoneSameInstant(ZoneId.of("Asia/Shanghai"));
        Operation accepted = executions.withSourceLock(sourceId, () -> {
            SourceState state = sources.get(sourceId);
            Source source = state.source();
            if (!source.enabled() || !source.baselineInitialized()
                    || !source.scheduleTime().equals(local.format(DateTimeFormatter.ofPattern("HH:mm")))) return null;
            if (!executions.claimDay(sourceId, local.toLocalDate())) return null;
            return recordAccepted(state, "DAILY", "SCHEDULED", null, List.of());
        });
        if (accepted != null && "QUEUED".equals(accepted.status())) launch(accepted.id());
        return accepted == null ? null : live(accepted);
    }

    private void launch(String id) {
        if (!localWork.add(id)) return;
        try {
            workers.execute(() -> {
                try {
                    locks.withLock("bgm-work:" + id, () -> new WorkOwnedElsewhere(), () -> {
                        if ("QUEUED".equals(records.get(id).status())) execute(id);
                        return null;
                    });
                } catch (WorkOwnedElsewhere ignored) { /* 另一实例持有完整工作租约。 */ }
                catch (RuntimeException exception) { fail(id, exception); }
                finally { localWork.remove(id); }
            });
        } catch (RejectedExecutionException exception) { localWork.remove(id); fail(id, exception); }
    }

    private void execute(String id) {
        Operation operation = records.update(id, value -> status(value, "RUNNING", null));
        SourceState refreshed = wishlist.refreshSourceForOperation(operation.source().id());
        requireConfig(operation.source(), refreshed.source());
        List<Long> ids = operation.entries().isEmpty() ? refreshed.subjects().stream()
                .sorted(Comparator.comparing((CollectedSubject subject) -> Instant.parse(subject.updatedAt())).reversed().thenComparingLong(CollectedSubject::subjectId))
                .filter(subject -> !"DAILY".equals(operation.action()) || refreshed.candidateSubjectIds().contains(subject.subjectId()))
                .map(CollectedSubject::subjectId).toList()
                : operation.entries().stream().filter(value -> Set.of("SEARCHING", "CREATING").contains(value.status()))
                    .map(value -> value.item().subjectId()).toList();
        int created = 0;
        for (long subjectId : ids) {
            if (Set.of("LATEST", "DAILY").contains(operation.action()) && created >= operation.source().targetCount()) break;
            Operation current = records.get(id);
            if (current.entries().stream().noneMatch(value -> value.item().subjectId() == subjectId)) {
                change(id, value -> replace(value, entry(snapshot(refreshed, subjectId), "SEARCHING", Instant.now()), "RUNNING"));
            }
            requireConfig(operation.source(), sources.get(operation.source().id()).source());
            try { if (process(id, subjectId)) created++; }
            catch (BgmWishlistTaskGateway.CreatedTaskRecordException exception) {
                var task = exception.task();
                updateEntry(id, subjectId, previous -> new Entry(previous.item(), "CREATED", previous.startedAt(), previous.searches(),
                        previous.candidates(), previous.selected(), new Task(task.taskType(), task.taskId(), task.status(), "created", null, null),
                        reason("TASK_RECORD_FAILED", "任务已创建，关联保存失败；请在任务中心查看"), previous.notification()));
                throw exception;
            } catch (BgmWishlistTaskGateway.EligibilityRefusal refusal) {
                updateEntry(id, subjectId, previous -> outcome(previous, "SKIPPED", refusal.reason()));
            } catch (BusinessException exception) {
                updateEntry(id, subjectId, previous -> outcome(previous, "SKIPPED", reason("REJECTED", exception.getMessage())));
            } catch (RuntimeException exception) {
                if (find(records.get(id), subjectId).task() != null) throw exception;
                log.warn("BGM 异步作品处理失败 operationId={} subjectId={}", id, subjectId, exception);
                updateEntry(id, subjectId, previous -> outcome(previous, "FAILED", reason("PROCESS_FAILED", "处理失败，请查看记录后重新搜索")));
            }
        }
        finish(id);
    }

    private boolean process(String id, long subjectId) {
        Operation operation = records.get(id);
        requireConfig(operation.source(), sources.get(operation.source().id()).source());
        Entry original = find(operation, subjectId);
        Item item = wishlist.evaluateForOperation(operation.source().id(), subjectId);
        if (original.item().identity() != null && !sameTarget(original.item().identity(), item.identity())) throw bad("目录目标已变化，请重新发起");
        updateEntry(id, subjectId, previous -> new Entry(item, previous.status(), previous.startedAt(), previous.searches(),
                previous.candidates(), previous.selected(), previous.task(), previous.reason(), previous.notification()));
        if (!item.eligible()) {
            updateEntry(id, subjectId, previous -> outcome(previous, "SKIPPED", item.reasons().get(0))); return false;
        }
        if ("SCHEDULED".equals(operation.triggerType()) && item.deletionTask() != null) {
            updateEntry(id, subjectId, previous -> outcome(previous, "SKIPPED", reason("MANUAL_REINGEST", "删除后请手动入库")));
            return false;
        }
        User owner = users.selectById(operation.source().ownerUserId());
        if (owner == null) throw bad("任务归属成员不存在");
        var availableQuota = quota.dailyContentCreateQuota(owner);
        if (!availableQuota.exempt() && availableQuota.remaining() == 0) {
            updateEntry(id, subjectId, previous -> outcome(previous, "SKIPPED", reason("QUOTA_EXHAUSTED", "任务归属成员今日创建次数已耗尽"))); return false;
        }
        Candidate selected = original.selected();
        if (!"CREATING".equals(original.status())) {
            List<Candidate> candidates;
            try {
                candidates = tasks.candidates(new BgmWishlistTaskGateway.Target(item.identity().mediaType(), item.identity().tmdbId(),
                        item.identity().seasonNumber(), item.identity().title(), item.identity().originalTitle(),
                        item.identity().year(), item.title()), operation.source().quality(), progress ->
                    updateEntry(id, subjectId, previous -> {
                        List<Search> searches = new ArrayList<>(previous.searches());
                        Search search = new Search(progress.query(), progress.source(), progress.status(), progress.elapsedMs(), progress.resultCount());
                        int queryIndex = -1;
                        for (int index = 0; index < searches.size(); index++) if (searches.get(index).query().equals(progress.query())) { queryIndex = index; break; }
                        if (queryIndex < 0) searches.add(search); else searches.set(queryIndex, search);
                        return new Entry(previous.item(), previous.status(), previous.startedAt(), List.copyOf(searches),
                                previous.candidates(), previous.selected(), null, previous.reason(), previous.notification());
                    })).stream().map(release -> new Candidate(candidateKey(release), release)).toList();
            } catch (ProwlarrReleaseIngestService.NoMatchingReleaseException noMatch) {
                updateEntry(id, subjectId, previous -> new Entry(previous.item(),
                        noMatch.candidates().isEmpty() ? "WAITING_RESOURCE" : "WAITING_CONFIRMATION",
                        previous.startedAt(), previous.searches(), noMatch.candidates().stream()
                                .map(release -> new Candidate(candidateKey(release), release)).toList(),
                        null, null, reason(noMatch.reasonCode(), noMatch.getMessage() + "，尚未创建任务"), null));
                notifyContent(id, subjectId);
                return false;
            } catch (RuntimeException exception) {
                updateEntry(id, subjectId, previous -> outcome(previous, "FAILED", reason("RELEASE_CHECK_FAILED", "发布搜索失败，无法判断是否有合适资源；请重新搜索")));
                return false;
            }
            selected = candidates.stream().filter(value -> hasChineseTitle(value.release().title())).findFirst().orElse(null);
            Candidate choice = selected;
            updateEntry(id, subjectId, previous -> new Entry(previous.item(), choice != null ? "CREATING" : candidates.isEmpty() ? "WAITING_RESOURCE" : "WAITING_CONFIRMATION",
                    previous.startedAt(), previous.searches(), candidates, choice, null,
                    choice != null ? null : reason(candidates.isEmpty() ? "NO_RELEASE" : "NON_CHINESE", candidates.isEmpty()
                            ? "未找到可推荐的发布资源，尚未创建任务" : "发布标题未检测到中文，字幕未经验证；尚未下载"), null));
            if (selected == null) { notifyContent(id, subjectId); return false; }
        }
        // 搜索期间配置、目标或在库可能变化；最终提交必须再次核验，人工确认不绕过此边界。
        requireConfig(operation.source(), sources.get(operation.source().id()).source());
        Item checked = wishlist.evaluateForOperation(operation.source().id(), subjectId);
        if (!sameTarget(item.identity(), checked.identity())) throw bad("目录目标已变化，请重新发起");
        if (!checked.eligible()) {
            updateEntry(id, subjectId, previous -> outcome(previous, "SKIPPED", checked.reasons().get(0))); return false;
        }
        if ("SCHEDULED".equals(operation.triggerType()) && checked.deletionTask() != null) {
            updateEntry(id, subjectId, previous -> outcome(previous, "SKIPPED", reason("MANUAL_REINGEST", "删除后请手动入库")));
            return false;
        }
        BgmWishlistTaskGateway.ActiveTask task;
        try { task = tasks.create(target(checked.identity()), selected.release(), owner); }
        catch (BusinessException exception) {
            if (exception.getCode().equals(ErrorCode.TOO_MANY_REQUESTS.getCode())) {
                updateEntry(id, subjectId, previous -> outcome(previous, "SKIPPED", reason("QUOTA_EXHAUSTED", "任务归属成员今日创建次数已耗尽")));
                return false;
            }
            throw new IllegalStateException("创建任务失败，已消耗额度按现行规则保留", exception);
        }
        Task link = new Task(task.taskType(), task.taskId(), task.status(), "created", null, null);
        updateEntry(id, subjectId, previous -> new Entry(checked, "CREATED", previous.startedAt(), previous.searches(),
                previous.candidates(), previous.selected(), link, null, previous.notification()));
        sources.recordCreated(operation.source().id(), subjectId, new TaskLink(task.taskType(), task.taskId(), task.status()));
        return true;
    }

    private void notifyContent(String id, long subjectId) {
        Operation operation = records.get(id); Entry entry = find(operation, subjectId);
        String key = "bgm:" + operation.source().id() + ":" + subjectId + ":" + entry.reason().code()
                + (entry.candidates().isEmpty() ? "" : ":" + entry.candidates().get(0).key());
        updateEntry(id, subjectId, previous -> notice(previous, "PENDING"));
        String result = notifications.sendOperationalAlert(key, (entry.candidates().isEmpty() ? "入库待补充资源｜" : "入库待确认｜") + entry.item().title(),
                entry.reason().message() + "。请查看操作记录处理。", "/automation/bgm-wishlist?operation=" + id);
        updateEntry(id, subjectId, previous -> notice(previous, result));
    }

    private Operation live(Operation operation) {
        List<Entry> entries = operation.entries().stream().map(entry -> {
            if (entry.task() == null) return entry;
            Task current = tasks.currentTask(entry.task().taskType(), entry.task().taskId());
            if (current == null) current = new Task(entry.task().taskType(), entry.task().taskId(), "UNKNOWN", null, "关联任务不存在，请查看任务中心", null);
            String notification = Set.of("FAILED", "INTERRUPTED").contains(current.status())
                    ? notifications.status("task:" + current.taskType().toLowerCase(Locale.ROOT) + ":" + current.taskId()) : null;
            return new Entry(entry.item(), entry.status(), entry.startedAt(), entry.searches(), entry.candidates(), entry.selected(),
                    new Task(current.taskType(), current.taskId(), current.status(), current.stage(), current.error(), notification), entry.reason(), entry.notification());
        }).toList();
        return new Operation(operation.id(), operation.source(), operation.action(), operation.triggerType(), operation.actorUserId(),
                operation.startedAt(), operation.updatedAt(), operation.status(), operation.requestedIds(), entries, operation.reason());
    }

    /** 完整工作租约存在时不接管；崩溃后只收敛记录，不重放有副作用的创建。 */
    @Scheduled(fixedDelay = 30000)
    public void reconcile() {
        for (Operation operation : records.open()) {
            if (localWork.contains(operation.id())) continue;
            if ("QUEUED".equals(operation.status())) { launch(operation.id()); continue; }
            if (!"RUNNING".equals(operation.status())) continue;
            try {
                locks.withLock("bgm-work:" + operation.id(), () -> new WorkOwnedElsewhere(), () -> {
                    change(operation.id(), value -> {
                        if (!"RUNNING".equals(value.status())) return value;
                        List<Entry> entries = value.entries().stream().map(entry -> {
                            if (!Set.of("SEARCHING", "CREATING").contains(entry.status())) return entry;
                            if (entry.item().identity() != null) {
                                var target = target(entry.item().identity());
                                var previous = tasks.previousTask(target);
                                var deletion = tasks.latestDeletion(target);
                                if (previous != null && (deletion == null || !deletion.replaces(previous.taskId()))) {
                                    return new Entry(entry.item(), "CREATED", entry.startedAt(), entry.searches(), entry.candidates(), entry.selected(),
                                            new Task(previous.taskType(), previous.taskId(), previous.status(), null, null, null), null, entry.notification());
                                }
                            }
                            return outcome(entry, "INTERRUPTED", reason("INTERRUPTED", "后台执行已中断；请先核对任务中心，再重新搜索"));
                        }).toList();
                        return new Operation(value.id(), value.source(), value.action(), value.triggerType(), value.actorUserId(), value.startedAt(),
                                Instant.now(), "INTERRUPTED", value.requestedIds(), entries, reason("INTERRUPTED", "后台执行中断，未自动重提任务"));
                    }); return null;
                });
            } catch (WorkOwnedElsewhere ignored) { /* 工作仍由另一实例执行。 */ }
            catch (RuntimeException exception) { log.warn("BGM 操作收敛失败 operationId={}", operation.id(), exception); }
        }
    }

    private void fail(String id, RuntimeException exception) {
        log.warn("BGM 异步操作失败 operationId={}", id, exception);
        try {
            change(id, operation -> new Operation(operation.id(), operation.source(), operation.action(), operation.triggerType(), operation.actorUserId(),
                    operation.startedAt(), Instant.now(), "FAILED", operation.requestedIds(), operation.entries().stream()
                    .map(entry -> Set.of("SEARCHING", "CREATING").contains(entry.status()) ? outcome(entry, "FAILED", reason("OPERATION_FAILED", "本轮执行未完成，请核对记录后重新搜索")) : entry).toList(),
                    reason("OPERATION_FAILED", exception instanceof BusinessException ? exception.getMessage() : "本轮执行失败，已保留逐项结果")));
        } catch (RuntimeException persistenceFailure) { log.error("BGM 操作失败状态未保存 operationId={}", id, persistenceFailure); }
    }
    private void finish(String id) {
        change(id, operation -> {
            boolean waiting = operation.entries().stream().anyMatch(entry -> WAITING.contains(entry.status()));
            boolean failed = operation.entries().stream().anyMatch(entry -> Set.of("FAILED", "INTERRUPTED").contains(entry.status()));
            boolean created = operation.entries().stream().anyMatch(entry -> "CREATED".equals(entry.status()));
            return status(operation, waiting ? "WAITING" : failed ? created ? "PARTIAL_SUCCESS" : "FAILED" : "SUCCEEDED", null);
        });
    }
    private void updateEntry(String id, long subjectId, UnaryOperator<Entry> update) {
        change(id, operation -> replace(operation, update.apply(find(operation, subjectId)), operation.status()));
    }
    private Operation change(String id, UnaryOperator<Operation> update) { return records.update(id, update); }
    private static Operation replace(Operation operation, Entry replacement, String status) {
        List<Entry> entries = new ArrayList<>(operation.entries());
        int index = -1;
        for (int current = 0; current < entries.size(); current++) {
            if (entries.get(current).item().subjectId() == replacement.item().subjectId()) { index = current; break; }
        }
        if (index < 0) entries.add(replacement); else entries.set(index, replacement);
        return new Operation(operation.id(), operation.source(), operation.action(), operation.triggerType(), operation.actorUserId(),
                operation.startedAt(), Instant.now(), status, operation.requestedIds(), List.copyOf(entries), operation.reason());
    }
    private static Operation status(Operation operation, String status, Reason reason) {
        return new Operation(operation.id(), operation.source(), operation.action(), operation.triggerType(), operation.actorUserId(),
                operation.startedAt(), Instant.now(), status, operation.requestedIds(), operation.entries(), reason);
    }
    private static Entry find(Operation operation, long subjectId) {
        return operation.entries().stream().filter(entry -> entry.item().subjectId() == subjectId).findFirst().orElseThrow(() -> bad("作品不属于本次操作"));
    }
    private static Entry entry(Item item, String status, Instant at) { return new Entry(item, status, at, List.of(), List.of(), null, null, null, null); }
    private static Entry outcome(Entry entry, String status, Reason reason) {
        return new Entry(entry.item(), status, entry.startedAt(), entry.searches(), entry.candidates(), entry.selected(), entry.task(), reason, entry.notification());
    }
    private static Entry notice(Entry entry, String state) {
        return new Entry(entry.item(), entry.status(), entry.startedAt(), entry.searches(), entry.candidates(), entry.selected(), entry.task(), entry.reason(), state);
    }
    private static Item snapshot(SourceState state, long id) {
        var subject = BgmWishlistSourceStore.requireSubject(state, id);
        return new Item(id, subject.title(), subject.originalTitle(), subject.coverUrl(), subject.year(), subject.updatedAt(), state.identities().get(id),
                "UNKNOWN", "UNKNOWN", false, List.of(), null, state.candidateSubjectIds().contains(id), "NOT_CHECKED", null, null, false);
    }
    static boolean hasChineseTitle(String title) { return title != null && java.util.regex.Pattern.compile("[\\u3400-\\u9fff]").matcher(title).find(); }
    private static String candidateKey(ProwlarrReleaseItemResponse release) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest((release.indexerId() + "\\n" + release.downloadRef() + "\\n" + release.title()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 24);
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private static boolean sameTarget(Identity a, Identity b) {
        return a != null && b != null && a.mediaType().equals(b.mediaType()) && a.tmdbId() == b.tmdbId() && Objects.equals(a.seasonNumber(), b.seasonNumber());
    }
    private static void requireConfig(Source before, Source after) {
        if (!before.equals(after)) throw bad("来源配置已变化，请重新发起");
    }
    private static BgmWishlistTaskGateway.Target target(Identity identity) {
        return new BgmWishlistTaskGateway.Target(identity.mediaType(), identity.tmdbId(), identity.seasonNumber(), identity.title(), identity.originalTitle(), identity.year());
    }
    private static Reason reason(String code, String message) { return new Reason(code, message); }
    private static BusinessException bad(String message) { return new BusinessException(ErrorCode.BAD_REQUEST, message, HttpStatus.BAD_REQUEST); }
    private static class WorkOwnedElsewhere extends RuntimeException {}
    @PreDestroy public void close() { workers.shutdownNow(); }
}
