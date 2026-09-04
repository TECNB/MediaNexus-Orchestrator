package com.medianexus.orchestrator.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medianexus.orchestrator.model.QuarkIngestTaskAttempt;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface QuarkIngestTaskAttemptMapper extends BaseMapper<QuarkIngestTaskAttempt> {

    @Update("""
            CREATE TABLE IF NOT EXISTS quark_ingest_task_attempts (
                id VARCHAR(36) NOT NULL,
                aggregate_task_id VARCHAR(36) NOT NULL,
                attempt_no INT NOT NULL,
                trigger_type VARCHAR(32) NOT NULL,
                target_child_task_ids TEXT NULL,
                status VARCHAR(32) NOT NULL,
                started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                ended_at DATETIME NULL,
                message VARCHAR(1024) NULL,
                created_by_user_id BIGINT NULL,
                PRIMARY KEY (id),
                KEY idx_quark_task_attempts_aggregate (aggregate_task_id, attempt_no)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """)
    void createTableIfNotExists();
}
