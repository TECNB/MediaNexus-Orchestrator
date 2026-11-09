package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.Identity;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.Source;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.SourceInput;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.TaskLink;
import com.medianexus.orchestrator.mapper.BgmWishlistSourceMapper;
import com.medianexus.orchestrator.mapper.UserMapper;
import com.medianexus.orchestrator.model.User;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 来源配置、完整快照、基线和任务关联的持久边界。所有更新在行锁内合并最新状态，
 * 外部读取不持有数据库事务；不以扫描完成或更新时间变化消费自动候选。
 */
@Service
public class BgmWishlistSourceStore {
    private final BgmWishlistSourceMapper mapper;
    private final UserMapper users;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private volatile boolean ready;

    public BgmWishlistSourceStore(BgmWishlistSourceMapper mapper, UserMapper users, ObjectMapper json,
                                  PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.users = users;
        this.json = json;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public List<SourceState> list() {
        ensureTable();
        return mapper.listStates().stream().map(this::decode).toList();
    }

    List<SourceState> listOwned(long ownerId) {
        ensureTable();
        return mapper.listOwnedStates(ownerId).stream().map(this::decode).toList();
    }

    List<String> ownedProviders(long ownerId) {
        ensureTable();
        return mapper.listOwnedProviders(ownerId);
    }

    public SourceState get(String sourceId) {
        ensureTable();
        return required(mapper.selectState(sourceId));
    }

    public SourceState create(SourceInput input) {
        Source source = configured(UUID.randomUUID().toString(), input, false);
        if (source.enabled()) throw bad("首次启用必须先完成清单刷新");
        return insert(empty(source));
    }

    public SourceState createWithRefresh(SourceInput input, List<CollectedSubject> subjects, Instant at) {
        Source source = configured(UUID.randomUUID().toString(), input, false);
        return insert(refreshed(empty(source), subjects, at));
    }

    public SourceState update(String sourceId, SourceInput input) {
        return mutate(sourceId, state -> {
            Source source = updatedConfig(state, input);
            if (source.enabled() && !source.baselineInitialized()) throw bad("首次启用必须先完成清单刷新");
            return withSource(state, source);
        });
    }

    /** 完整读取完成后才同时保存首次启用及初始基线；更新不覆盖并行创建的任务关联。 */
    public SourceState updateWithRefresh(String sourceId, SourceInput input, List<CollectedSubject> subjects, Instant at) {
        return mutate(sourceId, state -> {
            if (state.lastAttemptAt() != null && at.isBefore(state.lastAttemptAt())) {
                throw new BusinessException(ErrorCode.CONFLICT, "启用期间清单已变化，请重新保存以建立完整基线", HttpStatus.CONFLICT);
            }
            return refreshed(withSource(state, updatedConfig(state, input)), subjects, at);
        });
    }

    public SourceState recordRefresh(String sourceId, List<CollectedSubject> subjects, Instant at) {
        return mutate(sourceId, state -> refreshed(state, subjects, at));
    }

    public SourceState recordRefreshFailure(String sourceId, Instant at) {
        return mutate(sourceId, state -> {
            if (state.lastAttemptAt() != null && at.isBefore(state.lastAttemptAt())) return state;
            return new SourceState(state.source(), state.subjects(), state.knownSubjectIds(),
                    state.candidateSubjectIds(), state.identities(), state.createdTasks(), "FAILED",
                    "想看清单未完整读取，已保留上次有效清单；本轮不能用于新提交", at, state.lastSuccessAt());
        });
    }

    public SourceState saveIdentity(String sourceId, long subjectId, Identity identity) {
        return mutate(sourceId, state -> {
            requireSubject(state, subjectId);
            Map<Long, Identity> identities = new HashMap<>(state.identities());
            identities.put(subjectId, identity);
            return new SourceState(state.source(), state.subjects(), state.knownSubjectIds(), state.candidateSubjectIds(),
                    Map.copyOf(identities), state.createdTasks(), state.refreshStatus(), state.refreshError(),
                    state.lastAttemptAt(), state.lastSuccessAt());
        });
    }

    /** 自动映射只补齐当前条目的空身份；行锁内再次核对，不能覆盖并行人工纠正。 */
    public SourceState saveAutomaticIdentities(String sourceId, Map<Long, Identity> verified) {
        return mutate(sourceId, state -> {
            Map<Long, Identity> identities = new HashMap<>(state.identities());
            Set<Long> current = new HashSet<>();
            state.subjects().forEach(subject -> current.add(subject.subjectId()));
            verified.forEach((id, identity) -> {
                if (current.contains(id)) identities.putIfAbsent(id, identity);
            });
            return new SourceState(state.source(), state.subjects(), state.knownSubjectIds(), state.candidateSubjectIds(),
                    Map.copyOf(identities), state.createdTasks(), state.refreshStatus(), state.refreshError(),
                    state.lastAttemptAt(), state.lastSuccessAt());
        });
    }

    public void recordCreated(String sourceId, long subjectId, TaskLink task) {
        mutate(sourceId, state -> {
            Set<Long> candidates = new HashSet<>(state.candidateSubjectIds());
            candidates.remove(subjectId);
            Map<Long, TaskLink> tasks = new HashMap<>(state.createdTasks());
            tasks.put(subjectId, task);
            return new SourceState(state.source(), state.subjects(), state.knownSubjectIds(), Set.copyOf(candidates),
                    state.identities(), Map.copyOf(tasks), state.refreshStatus(), state.refreshError(),
                    state.lastAttemptAt(), state.lastSuccessAt());
        });
    }

    private SourceState refreshed(SourceState state, List<CollectedSubject> subjects, Instant at) {
        // 重叠请求中较早开始的刷新不能覆盖较新的成功或失败观察。
        if (state.lastAttemptAt() != null && at.isBefore(state.lastAttemptAt())) return state;
        Source source = state.source();
        Set<Long> known = new HashSet<>(state.knownSubjectIds());
        Set<Long> candidates = new HashSet<>(state.candidateSubjectIds());
        if (!source.baselineInitialized() && source.enabled()) {
            if ("INCLUDE_EXISTING".equals(source.initialScope())) {
                subjects.forEach(subject -> candidates.add(subject.subjectId()));
            }
            subjects.forEach(subject -> known.add(subject.subjectId()));
            source = new Source(source.id(), source.bgmUsername(), source.ownerUserId(), source.ownerUsername(),
                    source.enabled(), source.scheduleTime(), source.targetCount(), source.quality(),
                    source.initialScope(), source.timezone(), true, source.provider());
        } else if (source.baselineInitialized()) {
            for (CollectedSubject subject : subjects) {
                if (known.add(subject.subjectId()) && !state.createdTasks().containsKey(subject.subjectId())) {
                    candidates.add(subject.subjectId());
                }
            }
        }
        return new SourceState(source, List.copyOf(subjects), Set.copyOf(known), Set.copyOf(candidates),
                state.identities(), state.createdTasks(), "SUCCESS", null, at, at);
    }

    private Source updatedConfig(SourceState state, SourceInput input) {
        Source source = configured(state.source().id(), input, state.source().baselineInitialized());
        if (source.provider() != state.source().provider() || !source.bgmUsername().equals(state.source().bgmUsername())) {
            throw bad("已有来源不能更换平台或来源用户，请另建来源以保留原基线与任务记录");
        }
        if (source.baselineInitialized() && !source.initialScope().equals(state.source().initialScope())) {
            throw bad("首次范围已建立基线，不能重置；暂停和重开沿用原记录");
        }
        return source;
    }

    private Source configured(String id, SourceInput input, boolean initialized) {
        User owner = users.selectById(input.ownerUserId());
        if (owner == null) throw bad("任务归属成员不存在");
        return new Source(id, input.bgmUsername(), owner.getId(), owner.getUsername(), Boolean.TRUE.equals(input.enabled()),
                input.scheduleTime() == null ? "09:00" : input.scheduleTime(),
                input.targetCount() == null ? 3 : input.targetCount(), input.quality() == null ? "1080p" : input.quality(),
                input.initialScope() == null ? "NEW_ONLY" : input.initialScope(), "Asia/Shanghai", initialized, input.provider());
    }

    private SourceState empty(Source source) {
        return new SourceState(source, List.of(), Set.of(), Set.of(), Map.of(), Map.of(), "NEVER", null, null, null);
    }

    private SourceState withSource(SourceState state, Source source) {
        return new SourceState(source, state.subjects(), state.knownSubjectIds(), state.candidateSubjectIds(),
                state.identities(), state.createdTasks(), state.refreshStatus(), state.refreshError(),
                state.lastAttemptAt(), state.lastSuccessAt());
    }

    private SourceState insert(SourceState state) {
        ensureTable();
        mapper.insertState(state.source().id(), encode(state));
        return state;
    }

    private SourceState mutate(String sourceId, UnaryOperator<SourceState> mutation) {
        ensureTable();
        return transaction.execute(status -> {
            SourceState state = mutation.apply(required(mapper.selectStateForUpdate(sourceId)));
            mapper.updateState(sourceId, encode(state));
            return state;
        });
    }

    private SourceState required(String raw) {
        if (raw == null) throw new BusinessException(ErrorCode.NOT_FOUND, "想看来源不存在", HttpStatus.NOT_FOUND);
        return decode(raw);
    }

    private SourceState decode(String raw) {
        try {
            return json.readValue(raw, SourceState.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("想看来源持久状态无法读取，未覆盖原记录", exception);
        }
    }

    private String encode(SourceState state) {
        try {
            return json.writeValueAsString(state);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("想看来源持久状态无法保存", exception);
        }
    }

    private void ensureTable() {
        if (!ready) {
            synchronized (this) {
                if (!ready) {
                    mapper.createTableIfNotExists();
                    ready = true;
                }
            }
        }
    }

    static CollectedSubject requireSubject(SourceState state, long subjectId) {
        return state.subjects().stream().filter(subject -> subject.subjectId() == subjectId).findFirst()
                .orElseThrow(() -> bad("该作品不在当前来源想看清单中"));
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }

    public record CollectedSubject(long subjectId, String title, String originalTitle,
                                   String coverUrl, Integer year, String updatedAt) {
    }

    public record SourceState(Source source, List<CollectedSubject> subjects, Set<Long> knownSubjectIds,
                              Set<Long> candidateSubjectIds, Map<Long, Identity> identities,
                              Map<Long, TaskLink> createdTasks, String refreshStatus, String refreshError,
                              Instant lastAttemptAt, Instant lastSuccessAt) {
    }
}
