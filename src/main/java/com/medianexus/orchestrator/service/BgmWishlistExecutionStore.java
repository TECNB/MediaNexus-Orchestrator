package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistExecutionContract.Preview;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistExecutionContract.Run;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistExecutionContract.Runs;
import com.medianexus.orchestrator.mapper.BgmWishlistExecutionMapper;
import java.time.LocalDate;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 持久证明和执行历史；会话锁不包裹任务事务，保持既有失败扣额语义。 */
@Service
public class BgmWishlistExecutionStore {
    private final BgmWishlistExecutionMapper mapper;
    private final ObjectMapper json;
    private final BgmWishlistSessionLocks locks;
    private volatile boolean ready;

    public BgmWishlistExecutionStore(BgmWishlistExecutionMapper mapper, ObjectMapper json, BgmWishlistSessionLocks locks) {
        this.mapper = mapper;
        this.json = json;
        this.locks = locks;
    }

    public void savePreview(Preview preview, Long actorId) {
        ensureTable();
        mapper.insert(preview.id(), "PREVIEW", preview.source().id(), actorId, encode(new PreviewProof(actorId, preview)));
    }

    public PreviewProof preview(String id) {
        ensureTable();
        return decodeRequired(mapper.select(id, "PREVIEW"), PreviewProof.class);
    }

    public void consumePreview(String id, Long actorId) {
        if (mapper.consumePreview(id, actorId) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "预览已被确认，请查看执行历史或重新预览", HttpStatus.CONFLICT);
        }
    }

    /** 唯一键跨进程和重启保留当天认领；整轮失败也不在当天自动重试。 */
    public boolean claimDay(String sourceId, LocalDate day) {
        ensureTable();
        try {
            mapper.insert("daily:" + sourceId + ":" + day, "DAY", sourceId, null, "{}");
            return true;
        } catch (DuplicateKeyException alreadyClaimed) {
            return false;
        }
    }

    public void start(Run run) {
        ensureTable();
        mapper.insert(run.id(), "RUN", run.sourceId(), run.actorUserId(), encode(run));
    }

    public void save(Run run) {
        mapper.updateRun(run.id(), encode(run));
    }

    public Run run(String id) {
        ensureTable();
        return decodeRequired(mapper.select(id, "RUN"), Run.class);
    }

    public Runs runs(String sourceId, int page, int pageSize) {
        ensureTable();
        return new Runs(mapper.listRuns(sourceId, (long) (page - 1) * pageSize, pageSize).stream()
                .map(raw -> decodeRequired(raw, Run.class)).toList(), mapper.countRuns(sourceId), page, pageSize);
    }

    public <T> T withSourceLock(String sourceId, Supplier<T> operation) {
        String name = "bgm-source:" + sourceId;
        try {
            return locks.withLock(name,
                    () -> new BusinessException(ErrorCode.CONFLICT, "该来源正在执行，请查看执行记录", HttpStatus.CONFLICT), operation);
        } catch (BgmWishlistSessionLocks.LockUnavailableException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "来源执行锁不可用，未继续执行", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("BGM 执行记录无法保存", exception);
        }
    }

    private <T> T decodeRequired(String raw, Class<T> type) {
        if (raw == null) throw new BusinessException(ErrorCode.NOT_FOUND, "BGM 执行记录或预览不存在", HttpStatus.NOT_FOUND);
        try {
            return json.readValue(raw, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("BGM 执行记录无法读取，未覆盖原记录", exception);
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

    public record PreviewProof(Long actorUserId, Preview preview) {
    }
}
