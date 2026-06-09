package com.medianexus.orchestrator.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medianexus.orchestrator.model.UserAdminAuditLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserAdminAuditLogMapper extends BaseMapper<UserAdminAuditLog> {

    @Update("""
            CREATE TABLE IF NOT EXISTS user_admin_audit_logs (
                id BIGINT NOT NULL AUTO_INCREMENT,
                admin_user_id BIGINT NOT NULL,
                target_user_id BIGINT NULL,
                action_type VARCHAR(64) NOT NULL,
                old_value TEXT NULL,
                new_value TEXT NULL,
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                KEY idx_user_admin_audit_logs_admin_time (admin_user_id, created_at),
                KEY idx_user_admin_audit_logs_target_time (target_user_id, created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """)
    void createTableIfNotExists();
}
