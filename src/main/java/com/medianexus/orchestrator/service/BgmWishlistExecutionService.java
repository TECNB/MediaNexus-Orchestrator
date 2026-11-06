package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.*;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistExecutionContract.*;
import com.medianexus.orchestrator.dto.resources.response.ProwlarrReleaseItemResponse;
import com.medianexus.orchestrator.mapper.UserMapper;
import com.medianexus.orchestrator.model.User;
import com.medianexus.orchestrator.service.BgmWishlistSourceStore.CollectedSubject;
import com.medianexus.orchestrator.service.BgmWishlistSourceStore.SourceState;
import com.medianexus.orchestrator.service.BgmWishlistTaskGateway.Target;
import com.medianexus.orchestrator.service.BgmWishlistTaskGateway.EligibilityRefusal;
import com.medianexus.orchestrator.service.BgmWishlistTaskGateway.CreatedTaskRecordException;
import com.medianexus.orchestrator.service.UserActionQuotaService.DailyContentCreateQuota;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * BGM 想看应用执行边界。手动确认只能缩减服务器预览；每日候选可在失败后继续。
 * 任务创建、扣额由既有网关完成，本服务不预扣、退额或依赖调度线程登录态。
 */
@Service
public class BgmWishlistExecutionService {
    private static final Logger log = LoggerFactory.getLogger(BgmWishlistExecutionService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final AuthService auth;
    private final BgmWishlistService wishlist;
    private final BgmWishlistSourceStore sources;
    private final BgmWishlistExecutionStore executions;
    private final BgmWishlistTaskGateway tasks;
    private final UserActionQuotaService quota;
    private final UserMapper users;

    public BgmWishlistExecutionService(AuthService auth, BgmWishlistService wishlist,
                                        BgmWishlistSourceStore sources, BgmWishlistExecutionStore executions,
                                        BgmWishlistTaskGateway tasks, UserActionQuotaService quota, UserMapper users) {
        this.auth = auth;
        this.wishlist = wishlist;
        this.sources = sources;
        this.executions = executions;
        this.tasks = tasks;
        this.quota = quota;
        this.users = users;
    }

    public Preview preview(String sourceId, PreviewInput input) {
        User actor = auth.requireAdminUser();
        Set<Long> selected = new LinkedHashSet<>(input.subjectIds());
        if (selected.isEmpty() || selected.stream().anyMatch(id -> id == null || id <= 0)) {
            throw bad("请选择当前来源的作品");
        }
        Wishlist refreshed = successfulRefresh(sourceId);
        List<Item> items = new ArrayList<>();
        for (Long id : selected) {
            items.add(refreshed.items().stream().filter(item -> item.subjectId() == id).findFirst()
                    .orElseThrow(() -> bad("所选作品已不在当前来源清单中，请重新选择")));
        }
        return savePreview(actor, refreshed.source(), items, selected.size() == 1 ? "SINGLE" : "SELECTED", selected.size());
    }

    public Preview previewLatest(String sourceId) {
        User actor = auth.requireAdminUser();
        Wishlist refreshed = successfulRefresh(sourceId);
        return savePreview(actor, refreshed.source(), latest(refreshed.items()), "LATEST", refreshed.source().targetCount());
    }

    private Preview savePreview(User actor, Source source, List<Item> candidates, String action, int targetCount) {
        DailyContentCreateQuota before = quota.dailyContentCreateQuota(owner(source));
        List<Item> eligible = new ArrayList<>();
        List<ItemOutcome> skipped = new ArrayList<>();
        Set<String> targets = new HashSet<>();
        for (Item item : candidates) {
            if (eligible.size() >= targetCount) break;
            if (!item.eligible()) {
                skipped.add(skip(item, firstReason(item), taskLink(item)));
                continue;
            }
            if (!targets.add(targetKey(item.identity()))) {
                skipped.add(skip(item, reason("DUPLICATE_TARGET", "本次预览已包含同一电影或季度"), null));
                continue;
            }
            try {
                if (tasks.recommend(target(item.identity()), source.quality()) == null) {
                    skipped.add(skip(releaseStatus(item, "UNAVAILABLE"), reason("NO_RELEASE", "没有符合清晰度条件的发布资源"), null));
                    continue;
                }
            } catch (RuntimeException exception) {
                log.warn("BGM 预览发布查询失败 sourceId={} subjectId={}", source.id(), item.subjectId(), exception);
                skipped.add(skip(releaseStatus(item, "UNKNOWN"), reason("RELEASE_CHECK_FAILED", "发布资源查询失败，请稍后重新预览"), null));
                continue;
            }
            Item available = releaseStatus(item, "AVAILABLE");
            if (!before.exempt() && eligible.size() >= before.remaining()) {
                skipped.add(skip(available, reason("QUOTA_EXHAUSTED", "任务归属成员今日剩余创建次数不足；预览不预留额度"), null));
            } else {
                eligible.add(available);
            }
        }
        Preview preview = new Preview(UUID.randomUUID().toString(), source, Instant.now(), action,
                List.copyOf(eligible), List.copyOf(skipped), before, eligible.size());
        executions.savePreview(preview, actor.getId());
        return preview;
    }

    public Run execute(String sourceId, ExecuteInput input) {
        User actor = auth.requireAdminUser();
        return executions.withSourceLock(sourceId, () -> {
            var proof = executions.preview(input.previewId());
            Preview preview = proof.preview();
            if (!Objects.equals(actor.getId(), proof.actorUserId()) || !sourceId.equals(preview.source().id())) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "预览不属于当前管理员或来源", HttpStatus.FORBIDDEN);
            }
            if (!sameConfig(preview.source(), sources.get(sourceId).source())) {
                throw bad("来源配置已变化，请重新预览");
            }
            executions.consumePreview(preview.id(), actor.getId());
            Run run = start(preview.source(), preview.action(), "MANUAL", actor.getId());
            Run withSkips = progress(run, List.of(), List.of(), preview.skipped());
            executions.save(withSkips);
            return executeManual(withSkips, preview.items());
        });
    }

    /** 只在配置分钟执行，不补跑错过的时间；每日认领先于刷新，失败不会重复扫描提交。 */
    public Run runScheduledIfDue(String sourceId, ZonedDateTime at) {
        Source source = sources.get(sourceId).source();
        ZonedDateTime local = at.withZoneSameInstant(ZONE);
        if (!due(source, local)) return null;
        return executions.withSourceLock(sourceId, () -> {
            Source current = sources.get(sourceId).source();
            if (!due(current, local) || !executions.claimDay(sourceId, local.toLocalDate())) return null;
            return executeScheduled(start(current, "DAILY", "SCHEDULED", null));
        });
    }

    private boolean due(Source source, ZonedDateTime local) {
        return source.enabled() && source.baselineInitialized()
                && source.scheduleTime().equals(local.format(DateTimeFormatter.ofPattern("HH:mm")));
    }

    private Run start(Source source, String action, String trigger, Long actorId) {
        DailyContentCreateQuota before = quota.dailyContentCreateQuota(owner(source));
        Run run = new Run(UUID.randomUUID().toString(), source.id(), source, action, trigger, actorId,
                source.ownerUserId(), Instant.now(), null, "RUNNING", List.of(), List.of(), List.of(), before, before, null);
        executions.start(run);
        return run;
    }

    private Run executeManual(Run run, List<Item> confirmed) {
        Wishlist refreshed;
        try {
            refreshed = successfulRefresh(run.sourceId());
        } catch (RuntimeException exception) {
            log.warn("BGM 手动执行完整刷新失败 runId={}", run.id(), exception);
            return finish(run, owner(run.source()), reason("SNAPSHOT_UNAVAILABLE", "完整清单刷新失败，本轮未创建任务"));
        }
        if (!sameConfig(run.source(), refreshed.source())) {
            return finish(run, owner(run.source()), stoppedReason());
        }
        // 确认范围保持预览身份；共享提交循环对照新清单，只能减少，不能补入新作品。
        return submitItems(run, List.copyOf(confirmed), confirmed.size());
    }

    private Run executeScheduled(Run run) {
        Wishlist refreshed;
        try {
            refreshed = successfulRefresh(run.sourceId());
        } catch (RuntimeException exception) {
            log.warn("BGM 每日执行完整刷新失败 runId={}", run.id(), exception);
            return finish(run, owner(run.source()), reason("SNAPSHOT_UNAVAILABLE", "完整清单刷新失败，本轮未创建任务"));
        }
        if (!sameConfig(run.source(), refreshed.source())) {
            return finish(run, owner(run.source()), stoppedReason());
        }
        List<Item> candidates = latest(refreshed.items()).stream().filter(Item::automaticCandidate).toList();
        return submitItems(run, candidates, run.source().targetCount());
    }

    private Run submitItems(Run run, List<Item> candidates, int successTarget) {
        User owner = owner(run.source());
        List<ItemOutcome> created = new ArrayList<>(run.created());
        List<ItemOutcome> failed = new ArrayList<>(run.failed());
        List<ItemOutcome> skipped = new ArrayList<>(run.skipped());
        Set<String> createdTargets = new HashSet<>();
        Reason stopped = null;
        for (int index = 0; index < candidates.size() && created.size() < successTarget; index++) {
            Item snapshot = candidates.get(index);
            SourceState state = sources.get(run.sourceId());
            if (!sameConfig(run.source(), state.source())) {
                stopped = reason("SOURCE_CHANGED", "来源配置已变化，本轮停止创建");
                candidates.subList(index, candidates.size()).forEach(item -> skipped.add(skip(item, stoppedReason(), null)));
                break;
            }
            CollectedSubject subject = state.subjects().stream().filter(s -> s.subjectId() == snapshot.subjectId()).findFirst().orElse(null);
            if (subject == null) {
                skipped.add(skip(snapshot, reason("NOT_IN_WISHLIST", "作品已不在当前想看清单中"), null));
                continue;
            }
            if (!sameTarget(snapshot.identity(), state.identities().get(snapshot.subjectId()))) {
                skipped.add(skip(snapshot, reason("IDENTITY_CHANGED", "目录目标已变化，请重新预览或等待下次每日执行"), null));
                continue;
            }
            Item item = wishlist.evaluate(state, subject);
            if (!item.eligible()) {
                skipped.add(skip(item, firstReason(item), taskLink(item)));
                continue;
            }
            String key = targetKey(item.identity());
            if (createdTargets.contains(key)) {
                skipped.add(skip(item, reason("DUPLICATE_TARGET", "本轮已创建同一电影或季度任务"), null));
                continue;
            }
            DailyContentCreateQuota currentQuota = quota.dailyContentCreateQuota(owner);
            if (!currentQuota.exempt() && currentQuota.remaining() == 0) {
                stopped = reason("QUOTA_EXHAUSTED", "任务归属成员今日创建次数已耗尽；未创建作品保留至下次处理");
                Reason exhausted = stopped;
                candidates.subList(index, candidates.size()).forEach(candidate -> skipped.add(skip(candidate, exhausted, null)));
                break;
            }
            Target target = target(item.identity());
            ProwlarrReleaseItemResponse release;
            try {
                release = tasks.recommend(target, run.source().quality());
            } catch (RuntimeException exception) {
                log.warn("BGM 执行发布查询失败 runId={} subjectId={}", run.id(), item.subjectId(), exception);
                skipped.add(skip(releaseStatus(item, "UNKNOWN"), reason("RELEASE_CHECK_FAILED", "发布资源查询失败，本次跳过"), null));
                continue;
            }
            if (release == null) {
                skipped.add(skip(releaseStatus(item, "UNAVAILABLE"), reason("NO_RELEASE", "没有符合清晰度条件的发布资源"), null));
                continue;
            }
            item = releaseStatus(item, "AVAILABLE");
            TaskLink link;
            Reason recordFailure = null;
            try {
                var task = tasks.create(target, release, owner);
                link = new TaskLink(task.taskType(), task.taskId(), task.status());
            } catch (EligibilityRefusal refusal) {
                Item current = refusedItem(item, refusal);
                skipped.add(skip(current, refusal.reason(), taskLink(current)));
                run = progress(run, created, failed, skipped);
                executions.save(run);
                continue;
            } catch (CreatedTaskRecordException exception) {
                log.warn("BGM 任务已创建但目录关联保存失败 runId={} subjectId={}", run.id(), item.subjectId(), exception);
                var task = exception.task();
                link = new TaskLink(task.taskType(), task.taskId(), task.status());
                recordFailure = reason("TARGET_RECORD_FAILED", "任务已创建，但目录目标关联保存失败；本轮停止，请在任务中心查看原任务");
            } catch (RuntimeException exception) {
                log.warn("BGM 单项创建未成功 runId={} subjectId={}", run.id(), item.subjectId(), exception);
                if (exception instanceof BusinessException business && business.getCode().equals(ErrorCode.TOO_MANY_REQUESTS.getCode())) {
                    skipped.add(skip(item, reason("QUOTA_EXHAUSTED", "任务归属成员今日创建次数已达上限"), null));
                } else {
                    failed.add(new ItemOutcome(item, "FAILED", reason("CREATE_FAILED", "创建任务失败；已消耗的额度按现行规则保留"), null));
                }
                // 不按成功数推断额度；下一项再次读取额度服务的真实用量。
                run = progress(run, created, failed, skipped);
                executions.save(run);
                continue;
            }
            createdTargets.add(key);
            created.add(new ItemOutcome(item, "CREATED", null, link));
            run = progress(run, created, failed, skipped);
            // 创建结果先落运行记录；关联保存失败必须暴露，不能误报创建失败后继续提交。
            executions.save(run);
            try {
                sources.recordCreated(run.sourceId(), item.subjectId(), link);
            } catch (RuntimeException exception) {
                log.warn("BGM 任务已创建但来源关联保存失败 runId={} subjectId={}", run.id(), item.subjectId(), exception);
                return finish(run, owner, reason("SOURCE_RECORD_FAILED", "任务已创建，但来源关联保存失败；本轮停止，请在任务中心查看原任务"));
            }
            if (recordFailure != null) return finish(run, owner, recordFailure);
        }
        return finish(progress(run, created, failed, skipped), owner, stopped);
    }

    public Runs runs(String sourceId, int page, int pageSize) {
        auth.requireAdminUser();
        if (page < 1 || pageSize < 1) throw bad("分页参数必须为正整数");
        return executions.runs(sourceId, page, pageSize);
    }

    public Run run(String id) {
        auth.requireAdminUser();
        return executions.run(id);
    }

    private Run progress(Run run, List<ItemOutcome> created, List<ItemOutcome> failed, List<ItemOutcome> skipped) {
        return new Run(run.id(), run.sourceId(), run.source(), run.action(), run.triggerType(), run.actorUserId(),
                run.ownerUserId(), run.startedAt(), null, "RUNNING", List.copyOf(created), List.copyOf(failed),
                List.copyOf(skipped), run.quotaBefore(), run.quotaAfter(), null);
    }

    private Run finish(Run run, User owner, Reason reason) {
        String status = reason != null || !run.failed().isEmpty()
                ? (run.created().isEmpty() ? "FAILED" : "PARTIAL_SUCCESS") : "SUCCEEDED";
        Run finished = new Run(run.id(), run.sourceId(), run.source(), run.action(), run.triggerType(), run.actorUserId(),
                run.ownerUserId(), run.startedAt(), Instant.now(), status, run.created(), run.failed(), run.skipped(),
                run.quotaBefore(), quota.dailyContentCreateQuota(owner), reason);
        executions.save(finished);
        return finished;
    }

    private Wishlist successfulRefresh(String sourceId) {
        Wishlist refreshed = wishlist.refreshForExecution(sourceId);
        if (!"SUCCESS".equals(refreshed.refreshStatus())) {
            throw new BusinessException(ErrorCode.BAD_GATEWAY, "完整想看清单刷新失败，本轮不能创建任务", HttpStatus.BAD_GATEWAY);
        }
        return refreshed;
    }

    private User owner(Source source) {
        User owner = users.selectById(source.ownerUserId());
        if (owner == null) throw bad("配置的任务归属成员已不存在");
        return owner;
    }

    private static List<Item> latest(List<Item> items) {
        return items.stream().sorted(Comparator.comparing((Item item) -> Instant.parse(item.updatedAt()))
                .reversed().thenComparingLong(Item::subjectId)).toList();
    }

    private static boolean sameConfig(Source left, Source right) {
        return left.id().equals(right.id()) && left.bgmUsername().equals(right.bgmUsername())
                && left.ownerUserId().equals(right.ownerUserId()) && left.enabled() == right.enabled()
                && left.scheduleTime().equals(right.scheduleTime()) && left.targetCount() == right.targetCount()
                && left.quality().equals(right.quality()) && left.initialScope().equals(right.initialScope())
                && left.timezone().equals(right.timezone());
    }

    private static boolean sameTarget(Identity left, Identity right) {
        return left == null ? right == null : right != null && targetKey(left).equals(targetKey(right));
    }

    private static String targetKey(Identity identity) {
        return identity.mediaType() + ":" + identity.tmdbId() + ":" + identity.seasonNumber();
    }

    private static Target target(Identity identity) {
        return new Target(identity.mediaType(), identity.tmdbId(), identity.seasonNumber(), identity.title(), identity.originalTitle(), identity.year());
    }

    private static Item releaseStatus(Item item, String status) {
        return new Item(item.subjectId(), item.title(), item.originalTitle(), item.coverUrl(), item.year(), item.updatedAt(),
                item.identity(), item.libraryStatus(), item.availability(), item.eligible(), item.reasons(), item.activeTask(),
                item.automaticCandidate(), status, item.associatedTask());
    }

    private static TaskLink taskLink(Item item) {
        return item.associatedTask() != null ? item.associatedTask() : item.activeTask();
    }

    private static Item refusedItem(Item item, EligibilityRefusal refusal) {
        var task = refusal.task();
        TaskLink link = task == null ? null : new TaskLink(task.taskType(), task.taskId(), task.status());
        boolean previous = "PREVIOUS_TASK".equals(refusal.reason().code());
        return new Item(item.subjectId(), item.title(), item.originalTitle(), item.coverUrl(), item.year(), item.updatedAt(),
                item.identity(), refusal.libraryStatus() == null ? item.libraryStatus() : refusal.libraryStatus(),
                item.availability(), false, List.of(refusal.reason()),
                "ACTIVE_TASK".equals(refusal.reason().code()) ? link : item.activeTask(),
                !previous && item.automaticCandidate(), item.releaseStatus(), previous ? link : item.associatedTask());
    }

    private static Reason firstReason(Item item) {
        return item.reasons().isEmpty() ? reason("INELIGIBLE", "作品当前不满足入库条件") : item.reasons().get(0);
    }

    private static Reason stoppedReason() {
        return reason("SOURCE_CHANGED", "来源配置已变化，本轮停止创建");
    }

    private static ItemOutcome skip(Item item, Reason reason, TaskLink task) {
        return new ItemOutcome(item, "SKIPPED", reason, task);
    }

    private static Reason reason(String code, String message) {
        return new Reason(code, message);
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
