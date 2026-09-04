package com.medianexus.orchestrator.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medianexus.orchestrator.model.JavdbAutomationRunItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface JavdbAutomationRunItemMapper extends BaseMapper<JavdbAutomationRunItem> {

    @Update("""
            CREATE TABLE IF NOT EXISTS javdb_automation_run_items (
                id VARCHAR(36) NOT NULL,
                run_id VARCHAR(36) NOT NULL,
                code VARCHAR(64) NOT NULL,
                title VARCHAR(1024) NULL,
                detail_url VARCHAR(1024) NULL,
                appearances_json TEXT NULL,
                status VARCHAR(32) NOT NULL,
                reason VARCHAR(64) NULL,
                candidates_json LONGTEXT NULL,
                selected_infohash VARCHAR(128) NULL,
                selected_magnet LONGTEXT NULL,
                selected_reason VARCHAR(256) NULL,
                adult_task_id VARCHAR(36) NULL,
                error_message VARCHAR(1024) NULL,
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                KEY idx_javdb_automation_run_items_run (run_id, created_at),
                KEY idx_javdb_automation_run_items_code (code)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """)
    void createTableIfNotExists();
}
