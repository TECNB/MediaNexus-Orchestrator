package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.common.exception.*;
import com.medianexus.orchestrator.dto.douban.DoubanChartContract.*;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.Identity;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.Reason;
import com.medianexus.orchestrator.mapper.DoubanChartMapper;
import java.time.*;
import java.util.*;
import java.util.function.UnaryOperator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 所有行锁事务只保存状态，不包裹网络调用或入库任务事务。 */
@Service
public class DoubanChartStore {
    private final DoubanChartMapper mapper;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private volatile boolean ready;
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public DoubanChartStore(DoubanChartMapper mapper, ObjectMapper json, PlatformTransactionManager transactions) {
        this.mapper = mapper; this.json = json; this.transactions = new TransactionTemplate(transactions);
    }

    private synchronized void initialize() {
        if (ready) return;
        mapper.createTableIfNotExists();
        mapper.insert("settings", "SETTINGS", null, encode(new Limits(null, null)));
        for (ChartId id : ChartId.values()) mapper.insert(boardKey(id), "BOARD", id.name(), encode(new BoardState(
                new Board(id, id.label(), false, null, "NEVER", null, null, null, null, List.of()), null)));
        ready = true;
    }

    public Board board(ChartId id) { return state(id).board(); }
    private BoardState state(ChartId id) { initialize(); return decode(mapper.select(boardKey(id)), BoardState.class); }
    public Board updateBoard(ChartId id, UnaryOperator<Board> change) {
        initialize();
        return transactions.execute(tx -> {
            BoardState previous = decode(mapper.lock(boardKey(id)), BoardState.class);
            Board changed = change.apply(previous.board());
            mapper.update(boardKey(id), encode(new BoardState(changed, previous.activeOperationId())));
            return changed;
        });
    }

    public Identity identity(long id) {
        initialize(); String raw = mapper.select("identity:" + id);
        return raw == null ? null : decode(raw, Identity.class);
    }
    public void identity(long id, Identity identity) {
        initialize(); String key = "identity:" + id;
        transactions.executeWithoutResult(tx -> {
            mapper.insert(key, "IDENTITY", null, encode(identity));
            mapper.lock(key); mapper.update(key, encode(identity));
        });
    }

    public ProgressRecord progress(ChartId chart, long id) {
        initialize(); String raw = mapper.select("progress:" + chart + ":" + id);
        return raw == null ? null : decode(raw, ProgressRecord.class);
    }

    /** 写入前在榜单行锁下校验快照；身份再次变更时读取侧同样过滤，不把旧证据伪装为新身份。 */
    public void saveProgress(ChartId chart, ProgressRecord record) {
        initialize();
        transactions.executeWithoutResult(tx -> {
            BoardState state = decode(mapper.lock(boardKey(chart)), BoardState.class);
            boolean present = state.board().candidates().stream().anyMatch(candidate -> candidate.current()
                    && candidate.subject().equals(record.subject()));
            if (!present || !Objects.equals(identity(record.subject().id()), record.correctedIdentity())) return;
            String key = "progress:" + chart + ":" + record.subject().id();
            mapper.insert(key, "PROGRESS", chart.name(), encode(record));
            mapper.lock(key); mapper.update(key, encode(record));
        });
    }
    public record ProgressRecord(Subject subject, Identity correctedIdentity, ProgressEvidence evidence) {}

    /** 本榜行锁将手动和定时的查找/创建收敛到同一个持久操作。 */
    public Run submit(ChartId chart, Long actor, String trigger) {
        initialize();
        return transactions.execute(tx -> {
            BoardState state = decode(mapper.lock(boardKey(chart)), BoardState.class);
            if (state.activeOperationId() != null) {
                Run active = run(state.activeOperationId());
                if (active(active.operation().status())) return active;
            }
            Instant now = Instant.now();
            Operation operation = new Operation(UUID.randomUUID().toString(), chart, chart.label(), trigger, actor,
                    now, now, "QUEUED", List.of(), null);
            Run run = new Run(operation, List.of(), false, false);
            mapper.insert("operation:" + operation.id(), "OPERATION", chart.name(), encode(run));
            mapper.update(boardKey(chart), encode(new BoardState(state.board(), operation.id())));
            return run;
        });
    }
    public Run run(String id) {
        initialize(); String raw = mapper.select("operation:" + id);
        if (raw == null) throw new BusinessException(ErrorCode.NOT_FOUND, "榜单操作不存在", HttpStatus.NOT_FOUND);
        return decode(raw, Run.class);
    }
    public Run updateRun(String id, UnaryOperator<Run> change) {
        initialize();
        return transactions.execute(tx -> {
            String key = "operation:" + id;
            String raw = mapper.lock(key);
            if (raw == null) throw new BusinessException(ErrorCode.NOT_FOUND, "榜单操作不存在", HttpStatus.NOT_FOUND);
            Run changed = change.apply(decode(raw, Run.class));
            mapper.update(key, encode(changed)); return changed;
        });
    }
    public List<Run> open() { initialize(); return mapper.openOperations().stream().map(raw -> decode(raw, Run.class)).toList(); }
    public Operations operations(ChartId id, int page, int size) {
        initialize(); return new Operations(mapper.operations(id.name(), (long)(page - 1) * size, size).stream()
                .map(raw -> decode(raw, Run.class).operation()).toList(), mapper.operationCount(id.name()), page, size);
    }

    public Limits limits() { initialize(); return decode(mapper.select("settings"), Limits.class); }
    public void limits(LimitsInput input) {
        initialize(); transactions.executeWithoutResult(tx -> {
            mapper.lock("settings"); mapper.update("settings", encode(new Limits(input.dailyCheckLimit(), input.dailyCreateLimit())));
        });
    }
    public Counts counts() {
        initialize(); String raw = mapper.select(dayKey());
        return raw == null ? new Counts(0, 0) : decode(raw, Counts.class);
    }
    public boolean reserveCheck() {
        initialize();
        return transactions.execute(tx -> {
            Limits limits = decode(mapper.lock("settings"), Limits.class);
            String day = dayKey(); mapper.insert(day, "COUNTS", null, encode(new Counts(0, 0)));
            Counts counts = decode(mapper.lock(day), Counts.class);
            if (limits.checks() != null && counts.checked() >= limits.checks()) return false;
            mapper.update(day, encode(new Counts(counts.checked() + 1, counts.created()))); return true;
        });
    }
    /** 创建先占位，明确无副作用的拒绝才归还；崩溃/未知结果保守占用，避免超限重放。 */
    public String reserveCreate() {
        initialize();
        return transactions.execute(tx -> {
            Limits limits = decode(mapper.lock("settings"), Limits.class);
            String day = dayKey(); mapper.insert(day, "COUNTS", null, encode(new Counts(0, 0)));
            Counts counts = decode(mapper.lock(day), Counts.class);
            if (limits.creates() != null && counts.created() >= limits.creates()) return null;
            mapper.update(day, encode(new Counts(counts.checked(), counts.created() + 1))); return day;
        });
    }
    public void recordTask(String target, BgmWishlistTaskGateway.ActiveTask task) {
        initialize(); String key = "task:" + target;
        transactions.executeWithoutResult(tx -> {
            mapper.insert(key, "TASK", null, encode(task));
            mapper.lock(key); mapper.update(key, encode(task));
        });
    }
    public BgmWishlistTaskGateway.ActiveTask recordedTask(String target) {
        initialize(); String raw = mapper.select("task:" + target);
        return raw == null ? null : decode(raw, BgmWishlistTaskGateway.ActiveTask.class);
    }
    /** 独立的副作用疑点标记：即使网关账本写入窗口崩溃，后续榜单也不能重放。 */
    public boolean claimTarget(String target, String operationId) {
        initialize(); return mapper.insert("uncertain:" + target, "UNCERTAIN", null, encode(operationId)) == 1;
    }
    public void resolveTarget(String target) { mapper.delete("uncertain:" + target); }

    public void releaseCreate(String day) {
        transactions.executeWithoutResult(tx -> {
            Counts counts = decode(mapper.lock(day), Counts.class);
            mapper.update(day, encode(new Counts(counts.checked(), counts.created() - 1)));
        });
    }
    public boolean claimDaily(ChartId id, LocalDate date) {
        initialize(); return mapper.insert("daily:" + date + ":" + id, "DAILY", id.name(), "{}") == 1;
    }
    /** 日认领与已启用榜单的持久操作同事务提交；崩溃后至少存在可恢复的 QUEUED 操作。 */
    public DailyWork claimDailyWork(ChartId id, LocalDate date) {
        initialize();
        return transactions.execute(tx -> {
            BoardState state = decode(mapper.lock(boardKey(id)), BoardState.class);
            if (!claimDaily(id, date)) return null;
            Run run = state.board().autoEnabled() ? submit(id, state.board().ownerUserId(), "SCHEDULED") : null;
            return new DailyWork(run);
        });
    }
    public record DailyWork(Run run) {}

    public static boolean active(String status) { return Set.of("QUEUED", "RUNNING", "STOPPING").contains(status); }
    private String dayKey() { return "counts:" + LocalDate.now(ZONE); }
    private String boardKey(ChartId id) { return "board:" + id.name(); }
    private String encode(Object object) {
        try { return json.writeValueAsString(object); }
        catch (Exception exception) { throw new IllegalStateException("榜单记录保存失败", exception); }
    }
    private <T> T decode(String raw, Class<T> type) {
        try { return json.readValue(raw, type); }
        catch (Exception exception) { throw new IllegalStateException("榜单记录读取失败", exception); }
    }
    public record BoardState(Board board, String activeOperationId) {}
    public record Run(Operation operation, List<Candidate> scope, boolean scopeCaptured, boolean stopRequested) {}
    public record Limits(Integer checks, Integer creates) {}
    public record Counts(int checked, int created) {}
}
