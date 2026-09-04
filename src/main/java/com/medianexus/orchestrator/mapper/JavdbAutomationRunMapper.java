package com.medianexus.orchestrator.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medianexus.orchestrator.model.JavdbAutomationRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface JavdbAutomationRunMapper extends BaseMapper<JavdbAutomationRun> {

    @Update("""
            CREATE TABLE IF NOT EXISTS javdb_automation_runs (
                id VARCHAR(36) NOT NULL,
                trigger_type VARCHAR(16) NOT NULL,
                triggered_by_user_id BIGINT NULL,
                execution_mode VARCHAR(16) NOT NULL,
                status VARCHAR(32) NOT NULL,
                stage VARCHAR(64) NOT NULL,
                config_snapshot LONGTEXT NULL,
                ranking_entries INT NOT NULL DEFAULT 0,
                unique_movies INT NOT NULL DEFAULT 0,
                duplicate_entries_removed INT NOT NULL DEFAULT 0,
                already_in_emby INT NOT NULL DEFAULT 0,
                history_duplicates INT NOT NULL DEFAULT 0,
                active_duplicates INT NOT NULL DEFAULT 0,
                remaining_movies INT NOT NULL DEFAULT 0,
                submitted_count INT NOT NULL DEFAULT 0,
                adult_task_count INT NOT NULL DEFAULT 0,
                error_message VARCHAR(1024) NULL,
                started_at DATETIME NOT NULL,
                finished_at DATETIME NULL,
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                KEY idx_javdb_automation_runs_status (status, started_at),
                KEY idx_javdb_automation_runs_started_at (started_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """)
    void createTableIfNotExists();
}
