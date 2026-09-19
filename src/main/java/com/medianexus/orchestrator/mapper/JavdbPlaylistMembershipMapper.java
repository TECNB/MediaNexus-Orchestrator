package com.medianexus.orchestrator.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medianexus.orchestrator.model.JavdbPlaylistMembership;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface JavdbPlaylistMembershipMapper extends BaseMapper<JavdbPlaylistMembership> {

    @Update("""
            CREATE TABLE IF NOT EXISTS javdb_playlist_memberships (
                id VARCHAR(36) NOT NULL,
                code VARCHAR(64) NOT NULL,
                playlist_key VARCHAR(32) NOT NULL,
                source_run_id VARCHAR(36) NULL,
                adult_task_id VARCHAR(36) NULL,
                source_rank INT NULL,
                source_year INT NULL,
                emby_item_id VARCHAR(64) NULL,
                status VARCHAR(32) NOT NULL DEFAULT 'WAITING_EMBY',
                last_sync_run_id VARCHAR(36) NULL,
                synced_at DATETIME NULL,
                error_message VARCHAR(1024) NULL,
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                UNIQUE KEY uk_javdb_playlist_membership (code, playlist_key),
                KEY idx_javdb_playlist_membership_status (status, playlist_key),
                KEY idx_javdb_playlist_membership_run (source_run_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """)
    void createTableIfNotExists();

    @Insert("""
            INSERT INTO javdb_playlist_memberships (
                id, code, playlist_key, source_run_id, adult_task_id, source_rank, source_year,
                status, created_at, updated_at
            ) VALUES (
                #{id}, #{code}, #{playlistKey}, #{sourceRunId}, #{adultTaskId}, #{sourceRank}, #{sourceYear},
                'WAITING_EMBY', #{createdAt}, #{updatedAt}
            )
            ON DUPLICATE KEY UPDATE
                source_run_id = COALESCE(VALUES(source_run_id), source_run_id),
                adult_task_id = COALESCE(VALUES(adult_task_id), adult_task_id),
                source_rank = COALESCE(VALUES(source_rank), source_rank),
                source_year = COALESCE(VALUES(source_year), source_year),
                updated_at = VALUES(updated_at)
            """)
    void upsertIntent(JavdbPlaylistMembership membership);
}
