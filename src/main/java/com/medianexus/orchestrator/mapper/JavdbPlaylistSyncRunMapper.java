package com.medianexus.orchestrator.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medianexus.orchestrator.model.JavdbPlaylistSyncRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface JavdbPlaylistSyncRunMapper extends BaseMapper<JavdbPlaylistSyncRun> {

    @Update("""
            CREATE TABLE IF NOT EXISTS javdb_playlist_sync_runs (
                id VARCHAR(36) NOT NULL,
                trigger_type VARCHAR(16) NOT NULL,
                status VARCHAR(32) NOT NULL,
                desired_count INT NOT NULL DEFAULT 0,
                added_count INT NOT NULL DEFAULT 0,
                existing_count INT NOT NULL DEFAULT 0,
                waiting_count INT NOT NULL DEFAULT 0,
                failed_count INT NOT NULL DEFAULT 0,
                detail_json LONGTEXT NULL,
                error_message VARCHAR(1024) NULL,
                started_at DATETIME NOT NULL,
                finished_at DATETIME NULL,
                PRIMARY KEY (id),
                KEY idx_javdb_playlist_sync_started (started_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """)
    void createTableIfNotExists();
}
