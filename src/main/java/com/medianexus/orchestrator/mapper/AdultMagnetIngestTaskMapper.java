package com.medianexus.orchestrator.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medianexus.orchestrator.model.AdultMagnetIngestTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AdultMagnetIngestTaskMapper extends BaseMapper<AdultMagnetIngestTask> {

    @Update("""
            CREATE TABLE IF NOT EXISTS adult_magnet_ingest_tasks (
                id VARCHAR(36) NOT NULL,
                created_by_user_id BIGINT NULL,
                category VARCHAR(16) NOT NULL,
                status VARCHAR(32) NOT NULL,
                stage VARCHAR(64) NOT NULL,
                date_folder VARCHAR(32) NOT NULL,
                target_path VARCHAR(1024) NOT NULL,
                magnet_hashes TEXT NOT NULL,
                openlist_task_ids TEXT NULL,
                magnet_count INT NOT NULL DEFAULT 0,
                submitted_count INT NOT NULL DEFAULT 0,
                succeeded_count INT NOT NULL DEFAULT 0,
                failed_count INT NOT NULL DEFAULT 0,
                duplicate_count INT NOT NULL DEFAULT 0,
                kept_count INT NOT NULL DEFAULT 0,
                deleted_count INT NOT NULL DEFAULT 0,
                error_message VARCHAR(1024) NULL,
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                finished_at DATETIME NULL,
                PRIMARY KEY (id),
                KEY idx_adult_magnet_tasks_status_created_at (status, created_at),
                KEY idx_adult_magnet_tasks_category_created_at (category, created_at),
                KEY idx_adult_magnet_tasks_owner_created_at (created_by_user_id, created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """)
    void createTableIfNotExists();
}
