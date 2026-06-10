package com.medianexus.orchestrator.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medianexus.orchestrator.model.AnimeMagnetIngestTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AnimeMagnetIngestTaskMapper extends BaseMapper<AnimeMagnetIngestTask> {

    @Update("""
            CREATE TABLE IF NOT EXISTS anime_magnet_ingest_tasks (
                id VARCHAR(36) NOT NULL,
                status VARCHAR(32) NOT NULL,
                stage VARCHAR(64) NOT NULL,
                magnet TEXT NOT NULL,
                magnet_hash VARCHAR(64) NOT NULL,
                bgm_id VARCHAR(64) NOT NULL,
                bgm_url VARCHAR(255) NULL,
                title VARCHAR(255) NOT NULL,
                name_cn VARCHAR(255) NULL,
                name VARCHAR(255) NULL,
                season_number INT NOT NULL,
                save_path VARCHAR(1024) NOT NULL,
                temp_path VARCHAR(1024) NOT NULL,
                openlist_task_id VARCHAR(128) NULL,
                created_by_user_id BIGINT NULL,
                organized_count INT NOT NULL DEFAULT 0,
                skipped_count INT NOT NULL DEFAULT 0,
                error_message VARCHAR(1024) NULL,
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                finished_at DATETIME NULL,
                PRIMARY KEY (id),
                KEY idx_anime_magnet_tasks_hash_status (magnet_hash, status),
                KEY idx_anime_magnet_tasks_owner_created_at (created_by_user_id, created_at),
                KEY idx_anime_magnet_tasks_created_at (created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """)
    void createTableIfNotExists();

    @Select("""
            SELECT COUNT(*)
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'anime_magnet_ingest_tasks'
              AND COLUMN_NAME = 'created_by_user_id'
            """)
    Integer countCreatedByUserIdColumn();

    @Update("""
            ALTER TABLE anime_magnet_ingest_tasks
            ADD COLUMN created_by_user_id BIGINT NULL AFTER openlist_task_id
            """)
    void addCreatedByUserIdColumn();

    @Select("""
            SELECT COUNT(*)
            FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'anime_magnet_ingest_tasks'
              AND INDEX_NAME = 'idx_anime_magnet_tasks_owner_created_at'
            """)
    Integer countOwnerCreatedAtIndex();

    @Update("""
            CREATE INDEX idx_anime_magnet_tasks_owner_created_at
            ON anime_magnet_ingest_tasks (created_by_user_id, created_at)
            """)
    void addOwnerCreatedAtIndex();
}
