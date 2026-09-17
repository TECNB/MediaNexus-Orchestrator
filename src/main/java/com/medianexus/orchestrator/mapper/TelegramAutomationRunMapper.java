package com.medianexus.orchestrator.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medianexus.orchestrator.model.TelegramAutomationRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TelegramAutomationRunMapper extends BaseMapper<TelegramAutomationRun> {

    @Update("""
            CREATE TABLE IF NOT EXISTS telegram_automation_runs (
                id VARCHAR(36) NOT NULL,
                trigger_type VARCHAR(16) NOT NULL,
                triggered_by_user_id BIGINT NULL,
                execution_mode VARCHAR(32) NOT NULL,
                status VARCHAR(32) NOT NULL,
                stage VARCHAR(64) NOT NULL,
                channel_count INT NOT NULL DEFAULT 0,
                succeeded_channel_count INT NOT NULL DEFAULT 0,
                failed_channel_count INT NOT NULL DEFAULT 0,
                selected_resource_count INT NOT NULL DEFAULT 0,
                duplicate_resource_count INT NOT NULL DEFAULT 0,
                forwarded_resource_count INT NOT NULL DEFAULT 0,
                forwarded_message_count INT NOT NULL DEFAULT 0,
                result_json LONGTEXT NULL,
                error_message VARCHAR(1024) NULL,
                started_at DATETIME NOT NULL,
                finished_at DATETIME NULL,
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                KEY idx_telegram_automation_runs_status (status, started_at),
                KEY idx_telegram_automation_runs_started_at (started_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """)
    void createTableIfNotExists();
}
