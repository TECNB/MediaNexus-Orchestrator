package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.integration.wecom.WeComClient;
import com.medianexus.orchestrator.integration.wecom.WeComClient.Directory;
import com.medianexus.orchestrator.integration.wecom.WeComClient.Recipient;
import com.medianexus.orchestrator.integration.wecom.WeComClient.Scope;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WeComBindings {
    public record Accounts(Scope scope, List<Account> accounts) { }
    public record Account(Long id, String username, String role, String memberId) { }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final WeComClient client;
    private boolean tableReady;

    public WeComBindings(JdbcTemplate jdbc, TransactionTemplate transactions, WeComClient client) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.client = client;
    }

    public Accounts accounts() {
        ensureTable();
        Scope scope = client.scope();
        return new Accounts(scope, jdbc.query("""
                SELECT u.id, u.username, u.user_role, b.member_id
                FROM users u LEFT JOIN wecom_user_bindings b
                  ON b.user_id = u.id AND b.corp_id = ? AND b.agent_id = ?
                ORDER BY u.username, u.id
                """, (row, n) -> new Account(row.getLong(1), row.getString(2), row.getString(3), row.getString(4)),
                scope.corpId(), scope.agentId()));
    }

    public Directory visibleMembers() {
        try {
            return client.visibleMembers();
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.BAD_GATEWAY, "无法读取企微成员，请检查应用凭据、可见范围与通讯录权限：" + e.getMessage(), HttpStatus.BAD_GATEWAY);
        }
    }

    public void bind(Long userId, Scope scope, String memberId) {
        if (!WeComClient.isMemberId(memberId)) throw new BusinessException(ErrorCode.BAD_REQUEST, "请选择一个有效企微成员");
        Recipient recipient;
        try {
            recipient = client.verifyMember(memberId);
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无法确认该成员在应用可见范围内，绑定未修改：" + e.getMessage());
        }
        if (!recipient.scope().equals(scope)) throw new BusinessException(ErrorCode.CONFLICT, "企微应用已变更，请重新读取成员", HttpStatus.CONFLICT);
        ensureTable();
        try {
            transactions.executeWithoutResult(status -> {
                if (jdbc.queryForList("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, userId).isEmpty()) {
                    throw new BusinessException(ErrorCode.NOT_FOUND, "站内用户不存在", HttpStatus.NOT_FOUND);
                }
                delete(userId, scope);
                jdbc.update("INSERT INTO wecom_user_bindings (user_id, corp_id, agent_id, member_id) VALUES (?, ?, ?, ?)",
                        userId, scope.corpId(), scope.agentId(), recipient.memberId());
            });
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.CONFLICT, "该企微成员已绑定其他站内用户", HttpStatus.CONFLICT);
        }
    }

    public void unbind(Long userId, Scope scope) {
        if (!client.scope().equals(scope)) throw new BusinessException(ErrorCode.CONFLICT, "企微应用已变更，请刷新绑定", HttpStatus.CONFLICT);
        ensureTable();
        delete(userId, scope);
    }

    public Recipient recipient(Long userId) {
        if (userId == null) return null;
        ensureTable();
        Scope scope = client.scope();
        return jdbc.queryForList("SELECT member_id FROM wecom_user_bindings WHERE user_id = ? AND corp_id = ? AND agent_id = ?",
                String.class, userId, scope.corpId(), scope.agentId()).stream()
                .findFirst().map(id -> new Recipient(scope, id)).orElse(null);
    }

    /** 反查当前应用的绑定；通知收件人不能替代站内用户绑定。 */
    public Long userIdForMember(String memberId) {
        if (!WeComClient.isMemberId(memberId)) return null;
        ensureTable();
        Scope scope = client.scope();
        return jdbc.queryForList("SELECT user_id FROM wecom_user_bindings WHERE corp_id = ? AND agent_id = ? AND member_id = ?",
                Long.class, scope.corpId(), scope.agentId(), memberId).stream().findFirst().orElse(null);
    }

    private void delete(Long userId, Scope scope) {
        jdbc.update("DELETE FROM wecom_user_bindings WHERE user_id = ? AND corp_id = ? AND agent_id = ?",
                userId, scope.corpId(), scope.agentId());
    }

    private synchronized void ensureTable() {
        if (tableReady) return;
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS wecom_user_bindings (
                    user_id BIGINT NOT NULL,
                    corp_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                    agent_id BIGINT NOT NULL,
                    member_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_ci NOT NULL,
                    PRIMARY KEY (user_id, corp_id, agent_id),
                    UNIQUE KEY uk_wecom_member (corp_id, agent_id, member_id),
                    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
                ) ENGINE=InnoDB
                """);
        tableReady = true;
    }
}
