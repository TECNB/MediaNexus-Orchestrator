package com.medianexus.orchestrator.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medianexus.orchestrator.model.AdultMagnetIngestTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
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
                download_links_json LONGTEXT NULL,
                openlist_task_ids TEXT NULL,
                attempt_group_id VARCHAR(128) NULL,
                retry_of_task_type VARCHAR(32) NULL,
                retry_of_task_id VARCHAR(36) NULL,
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
                KEY idx_adult_magnet_tasks_owner_created_at (created_by_user_id, created_at),
                KEY idx_adult_magnet_tasks_attempt_group (attempt_group_id, created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """)
    void createTableIfNotExists();

    @Select("""
            SELECT COUNT(*)
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'adult_magnet_ingest_tasks'
              AND COLUMN_NAME = 'attempt_group_id'
            """)
    Integer countAttemptChainColumns();

    @Update("""
            ALTER TABLE adult_magnet_ingest_tasks
            ADD COLUMN attempt_group_id VARCHAR(128) NULL AFTER openlist_task_ids,
            ADD COLUMN retry_of_task_type VARCHAR(32) NULL AFTER attempt_group_id,
            ADD COLUMN retry_of_task_id VARCHAR(36) NULL AFTER retry_of_task_type
            """)
    void addAttemptChainColumns();

    @Select("""
            SELECT COUNT(*)
            FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'adult_magnet_ingest_tasks'
              AND INDEX_NAME = 'idx_adult_magnet_tasks_attempt_group'
            """)
    Integer countAttemptGroupIndex();

    @Update("""
            CREATE INDEX idx_adult_magnet_tasks_attempt_group
            ON adult_magnet_ingest_tasks (attempt_group_id, created_at)
            """)
    void addAttemptGroupIndex();

    @Select("""
            SELECT COUNT(*)
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'adult_magnet_ingest_tasks'
              AND COLUMN_NAME = 'download_links_json'
            """)
    Integer countDownloadLinksJsonColumn();

    @Update("""
            ALTER TABLE adult_magnet_ingest_tasks
            ADD COLUMN download_links_json LONGTEXT NULL AFTER magnet_hashes
            """)
    void addDownloadLinksJsonColumn();
}
