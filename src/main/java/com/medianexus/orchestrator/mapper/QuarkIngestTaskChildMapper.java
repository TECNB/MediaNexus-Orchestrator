package com.medianexus.orchestrator.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medianexus.orchestrator.model.QuarkIngestTaskChild;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface QuarkIngestTaskChildMapper extends BaseMapper<QuarkIngestTaskChild> {

    @Update("""
            CREATE TABLE IF NOT EXISTS quark_ingest_task_children (
                id VARCHAR(36) NOT NULL,
                aggregate_task_id VARCHAR(36) NOT NULL,
                task_name VARCHAR(255) NOT NULL,
                source_url VARCHAR(2048) NOT NULL,
                save_path VARCHAR(1024) NOT NULL,
                pattern TEXT NULL,
                replace_rule TEXT NULL,
                version_label VARCHAR(255) NULL,
                status VARCHAR(32) NOT NULL,
                failure_reason VARCHAR(1024) NULL,
                task_reference VARCHAR(1024) NULL,
                season_number INT NULL,
                retry_count INT NOT NULL DEFAULT 0,
                subscription_enabled TINYINT(1) NOT NULL DEFAULT 0,
                planned_file_count INT NOT NULL DEFAULT 0,
                processed_file_count INT NOT NULL DEFAULT 0,
                renamed_file_count INT NOT NULL DEFAULT 0,
                ignored_file_count INT NOT NULL DEFAULT 0,
                failed_file_count INT NOT NULL DEFAULT 0,
                unknown_file_count INT NOT NULL DEFAULT 0,
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                KEY idx_quark_task_children_aggregate (aggregate_task_id, created_at),
                KEY idx_quark_task_children_status (status)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """)
    void createTableIfNotExists();
}
