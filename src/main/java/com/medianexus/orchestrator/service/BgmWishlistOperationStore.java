package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.common.exception.*;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistOperationContract.*;
import com.medianexus.orchestrator.mapper.BgmWishlistExecutionMapper;
import java.util.List;
import java.util.function.UnaryOperator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 复用 BGM 执行表，短行锁事务保证并行搜索回报不丢失，不包裹任务或额度副作用。 */
@Service
public class BgmWishlistOperationStore {
    private final BgmWishlistExecutionMapper mapper;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private volatile boolean ready;
    public BgmWishlistOperationStore(BgmWishlistExecutionMapper mapper, ObjectMapper json, PlatformTransactionManager transactions) {
        this.mapper = mapper; this.json = json; this.transactions = new TransactionTemplate(transactions);
    }
    synchronized void ensureTable() {
        if (!ready) { mapper.createTableIfNotExists(); ready = true; }
    }
    public void create(Operation operation) {
        ensureTable(); mapper.insert(operation.id(), "OPERATION", operation.source().id(), operation.actorUserId(), encode(operation));
    }
    public Operation get(String id) {
        ensureTable(); String raw = mapper.select(id, "OPERATION");
        if (raw == null) throw new BusinessException(ErrorCode.NOT_FOUND, "操作记录不存在", HttpStatus.NOT_FOUND);
        return decode(raw);
    }
    public Operation update(String id, UnaryOperator<Operation> change) {
        ensureTable();
        return transactions.execute(transaction -> {
            String raw = mapper.lockOperation(id);
            if (raw == null) throw new BusinessException(ErrorCode.NOT_FOUND, "操作记录不存在", HttpStatus.NOT_FOUND);
            Operation changed = change.apply(decode(raw));
            mapper.updateOperation(id, encode(changed));
            return changed;
        });
    }
    public Operations list(String sourceId, int page, int size) {
        ensureTable(); return new Operations(mapper.listOperations(sourceId, (long)(page - 1) * size, size).stream()
                .map(this::decode).toList(), mapper.countOperations(sourceId), page, size);
    }
    public Operations listAllWeCom(int page, int size) {
        ensureTable();
        return new Operations(mapper.listAllWeComOperations((long) (page - 1) * size, size).stream()
                .map(this::decode).toList(), mapper.countAllWeComOperations(), page, size);
    }
    List<Operation> recentOwned(String sourceId, long ownerId) {
        ensureTable();
        return mapper.recentOwnedOperations(sourceId, ownerId).stream().map(this::decode).toList();
    }
    public List<Operation> open() { ensureTable(); return mapper.openOperations().stream().map(this::decode).toList(); }
    private String encode(Operation operation) {
        try { return json.writeValueAsString(operation); }
        catch (Exception exception) { throw new IllegalStateException("操作记录保存失败", exception); }
    }
    private Operation decode(String raw) {
        try { return json.readValue(raw, Operation.class); }
        catch (Exception exception) { throw new IllegalStateException("操作记录读取失败", exception); }
    }
}
