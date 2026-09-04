package com.medianexus.orchestrator.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medianexus.orchestrator.model.QuarkIngestTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface QuarkIngestTaskMapper extends BaseMapper<QuarkIngestTask> {

    @Update("""
            CREATE TABLE IF NOT EXISTS quark_ingest_tasks (
                id VARCHAR(36) NOT NULL,
                created_by_user_id BIGINT NOT NULL,
                media_type VARCHAR(16) NOT NULL,
                source_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL_QUARK',
                title VARCHAR(255) NOT NULL,
                status VARCHAR(32) NOT NULL,
                stage VARCHAR(64) NOT NULL,
                task_names TEXT NOT NULL,
                save_path VARCHAR(1024) NOT NULL,
                immediate_execution_started TINYINT(1) NOT NULL DEFAULT 0,
                created_task_count INT NOT NULL DEFAULT 0,
                planned_task_count INT NOT NULL DEFAULT 0,
                message VARCHAR(1024) NOT NULL,
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                KEY idx_quark_ingest_tasks_owner_created_at (created_by_user_id, created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """)
    void createTableIfNotExists();

    @org.apache.ibatis.annotations.Select("""
            SELECT COUNT(*)
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'quark_ingest_tasks'
              AND COLUMN_NAME = 'source_type'
            """)
    Integer countSourceTypeColumn();

    @Update("ALTER TABLE quark_ingest_tasks ADD COLUMN source_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL_QUARK' AFTER media_type")
    void addSourceTypeColumn();
}
