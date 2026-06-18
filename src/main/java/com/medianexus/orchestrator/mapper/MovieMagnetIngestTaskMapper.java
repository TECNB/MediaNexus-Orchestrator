package com.medianexus.orchestrator.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medianexus.orchestrator.model.MovieMagnetIngestTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MovieMagnetIngestTaskMapper extends BaseMapper<MovieMagnetIngestTask> {

    @Update("""
            CREATE TABLE IF NOT EXISTS movie_magnet_ingest_tasks (
                id VARCHAR(36) NOT NULL,
                status VARCHAR(32) NOT NULL,
                stage VARCHAR(64) NOT NULL,
                magnet TEXT NOT NULL,
                magnet_hash VARCHAR(64) NOT NULL,
                title VARCHAR(255) NOT NULL,
                original_title VARCHAR(255) NULL,
                year INT NOT NULL,
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
                KEY idx_movie_magnet_tasks_hash_status (magnet_hash, status),
                KEY idx_movie_magnet_tasks_owner_created_at (created_by_user_id, created_at),
                KEY idx_movie_magnet_tasks_created_at (created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """)
    void createTableIfNotExists();
}
