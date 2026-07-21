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
                bgm_id VARCHAR(64) NULL,
                bgm_url VARCHAR(255) NULL,
                tmdb_id INT NULL,
                title VARCHAR(255) NOT NULL,
                name_cn VARCHAR(255) NULL,
                name VARCHAR(255) NULL,
                season_number INT NOT NULL,
                source_type VARCHAR(32) NULL,
                release_title VARCHAR(1024) NULL,
                release_indexer VARCHAR(255) NULL,
                release_size BIGINT NULL,
                release_indexer_id INT NULL,
                release_guid VARCHAR(1024) NULL,
                resolution_tags VARCHAR(255) NULL,
                quality_tag VARCHAR(32) NULL,
                dynamic_range_tags VARCHAR(255) NULL,
                save_path VARCHAR(1024) NOT NULL,
                temp_path VARCHAR(1024) NOT NULL,
                openlist_task_id VARCHAR(128) NULL,
                attempt_group_id VARCHAR(128) NULL,
                retry_of_task_type VARCHAR(32) NULL,
                retry_of_task_id VARCHAR(36) NULL,
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
                KEY idx_anime_magnet_tasks_attempt_group (attempt_group_id, created_at),
                KEY idx_anime_magnet_tasks_created_at (created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """)
    void createTableIfNotExists();

    @Select("""
            SELECT COUNT(*)
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'anime_magnet_ingest_tasks'
              AND COLUMN_NAME = 'tmdb_id'
            """)
    Integer countCatalogIdentityColumns();

    @Update("""
            ALTER TABLE anime_magnet_ingest_tasks
            ADD COLUMN tmdb_id INT NULL AFTER bgm_url
            """)
    void addCatalogIdentityColumns();

    @Select("""
            SELECT COUNT(*)
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'anime_magnet_ingest_tasks'
              AND COLUMN_NAME = 'bgm_id'
              AND IS_NULLABLE = 'NO'
            """)
    Integer countRequiredBgmIdColumns();

    @Update("""
            ALTER TABLE anime_magnet_ingest_tasks
            MODIFY COLUMN bgm_id VARCHAR(64) NULL
            """)
    void makeBgmIdNullable();

    @Select("""
            SELECT COUNT(*)
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'anime_magnet_ingest_tasks'
              AND COLUMN_NAME = 'attempt_group_id'
            """)
    Integer countAttemptChainColumns();

    @Update("""
            ALTER TABLE anime_magnet_ingest_tasks
            ADD COLUMN attempt_group_id VARCHAR(128) NULL AFTER openlist_task_id,
            ADD COLUMN retry_of_task_type VARCHAR(32) NULL AFTER attempt_group_id,
            ADD COLUMN retry_of_task_id VARCHAR(36) NULL AFTER retry_of_task_type
            """)
    void addAttemptChainColumns();

    @Select("""
            SELECT COUNT(*)
            FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'anime_magnet_ingest_tasks'
              AND INDEX_NAME = 'idx_anime_magnet_tasks_attempt_group'
            """)
    Integer countAttemptGroupIndex();

    @Update("""
            CREATE INDEX idx_anime_magnet_tasks_attempt_group
            ON anime_magnet_ingest_tasks (attempt_group_id, created_at)
            """)
    void addAttemptGroupIndex();

    @Select("""
            SELECT COUNT(*)
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'anime_magnet_ingest_tasks'
              AND COLUMN_NAME = 'source_type'
            """)
    Integer countSourceTypeColumn();

    @Update("""
            ALTER TABLE anime_magnet_ingest_tasks
            ADD COLUMN source_type VARCHAR(32) NULL AFTER season_number,
            ADD COLUMN release_title VARCHAR(1024) NULL AFTER source_type,
            ADD COLUMN release_indexer VARCHAR(255) NULL AFTER release_title,
            ADD COLUMN release_size BIGINT NULL AFTER release_indexer,
            ADD COLUMN release_indexer_id INT NULL AFTER release_size,
            ADD COLUMN release_guid VARCHAR(1024) NULL AFTER release_indexer_id,
            ADD COLUMN resolution_tags VARCHAR(255) NULL AFTER release_guid,
            ADD COLUMN quality_tag VARCHAR(32) NULL AFTER resolution_tags,
            ADD COLUMN dynamic_range_tags VARCHAR(255) NULL AFTER quality_tag
            """)
    void addReleaseMetadataColumns();

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
