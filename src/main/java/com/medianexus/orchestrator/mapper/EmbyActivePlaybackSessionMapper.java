package com.medianexus.orchestrator.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medianexus.orchestrator.model.EmbyActivePlaybackSession;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface EmbyActivePlaybackSessionMapper extends BaseMapper<EmbyActivePlaybackSession> {

    @Update("""
            CREATE TABLE IF NOT EXISTS emby_active_playback_sessions (
                id BIGINT NOT NULL AUTO_INCREMENT,
                emby_session_id VARCHAR(128) NOT NULL,
                emby_user_id VARCHAR(128) NOT NULL,
                emby_user_name VARCHAR(255) NULL,
                item_id VARCHAR(128) NOT NULL,
                item_type VARCHAR(64) NOT NULL,
                item_name VARCHAR(512) NULL,
                series_id VARCHAR(128) NULL,
                series_name VARCHAR(512) NULL,
                season_number INT NULL,
                episode_number INT NULL,
                runtime_ticks BIGINT NULL,
                start_position_ticks BIGINT NULL,
                start_time DATETIME NOT NULL,
                device_name VARCHAR(255) NULL,
                client_name VARCHAR(255) NULL,
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                UNIQUE KEY uk_emby_active_session_item (emby_session_id, item_id),
                KEY idx_emby_active_user (emby_user_id),
                KEY idx_emby_active_start_time (start_time)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """)
    void createTableIfNotExists();

    @Select("""
            SELECT COUNT(*)
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'emby_active_playback_sessions'
              AND COLUMN_NAME = 'start_position_ticks'
              AND IS_NULLABLE = 'NO'
            """)
    Integer countRequiredStartPositionTicksColumn();

    @Select("""
            SELECT COUNT(*)
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'emby_active_playback_sessions'
              AND COLUMN_NAME = 'season_number'
            """)
    Integer countSeasonNumberColumn();

    @Update("""
            ALTER TABLE emby_active_playback_sessions
            ADD COLUMN season_number INT NULL AFTER series_name,
            ADD COLUMN episode_number INT NULL AFTER season_number
            """)
    void addEpisodePositionColumns();

    @Update("""
            ALTER TABLE emby_active_playback_sessions
            MODIFY start_position_ticks BIGINT NULL
            """)
    void makeStartPositionTicksNullable();

    @Insert("""
            INSERT INTO emby_active_playback_sessions (
                emby_session_id,
                emby_user_id,
                emby_user_name,
                item_id,
                item_type,
                item_name,
                series_id,
                series_name,
                season_number,
                episode_number,
                runtime_ticks,
                start_position_ticks,
                start_time,
                device_name,
                client_name
            )
            VALUES (
                #{session.embySessionId},
                #{session.embyUserId},
                #{session.embyUserName},
                #{session.itemId},
                #{session.itemType},
                #{session.itemName},
                #{session.seriesId},
                #{session.seriesName},
                #{session.seasonNumber},
                #{session.episodeNumber},
                #{session.runtimeTicks},
                #{session.startPositionTicks},
                #{session.startTime},
                #{session.deviceName},
                #{session.clientName}
            )
            ON DUPLICATE KEY UPDATE
                emby_user_id = VALUES(emby_user_id),
                emby_user_name = VALUES(emby_user_name),
                item_type = VALUES(item_type),
                item_name = VALUES(item_name),
                series_id = VALUES(series_id),
                series_name = VALUES(series_name),
                season_number = VALUES(season_number),
                episode_number = VALUES(episode_number),
                runtime_ticks = VALUES(runtime_ticks),
                start_position_ticks = VALUES(start_position_ticks),
                start_time = VALUES(start_time),
                device_name = VALUES(device_name),
                client_name = VALUES(client_name)
            """)
    void upsertActiveSession(@Param("session") EmbyActivePlaybackSession session);

    @Select("""
            SELECT id,
                   emby_session_id,
                   emby_user_id,
                   emby_user_name,
                   item_id,
                   item_type,
                   item_name,
                   series_id,
                   series_name,
                   season_number,
                   episode_number,
                   runtime_ticks,
                   start_position_ticks,
                   start_time,
                   device_name,
                   client_name,
                   created_at,
                   updated_at
            FROM emby_active_playback_sessions
            WHERE emby_session_id = #{embySessionId}
              AND emby_user_id = #{embyUserId}
            FOR UPDATE
            """)
    List<EmbyActivePlaybackSession> selectActiveSessionsForContextForUpdate(
            @Param("embySessionId") String embySessionId,
            @Param("embyUserId") String embyUserId
    );

    @Select("""
            SELECT id,
                   emby_session_id,
                   emby_user_id,
                   emby_user_name,
                   item_id,
                   item_type,
                   item_name,
                   series_id,
                   series_name,
                   season_number,
                   episode_number,
                   runtime_ticks,
                   start_position_ticks,
                   start_time,
                   device_name,
                   client_name,
                   created_at,
                   updated_at
            FROM emby_active_playback_sessions
            WHERE emby_session_id = #{embySessionId}
              AND item_id = #{itemId}
            FOR UPDATE
            """)
    EmbyActivePlaybackSession selectActiveSessionForUpdate(
            @Param("embySessionId") String embySessionId,
            @Param("itemId") String itemId
    );

    @Update("""
            DELETE FROM emby_active_playback_sessions
            WHERE emby_session_id = #{embySessionId}
              AND item_id = #{itemId}
            """)
    void deleteActiveSession(
            @Param("embySessionId") String embySessionId,
            @Param("itemId") String itemId
    );

    @Update("""
            DELETE FROM emby_active_playback_sessions
            WHERE updated_at < #{cutoff}
            """)
    int deleteSessionsUpdatedBefore(@Param("cutoff") LocalDateTime cutoff);

    @Select("""
            SELECT COUNT(*)
            FROM emby_active_playback_sessions
            """)
    long countActiveSessions();

    @Select("""
            SELECT id,
                   emby_session_id,
                   emby_user_id,
                   emby_user_name,
                   item_id,
                   item_type,
                   item_name,
                   series_id,
                   series_name,
                   season_number,
                   episode_number,
                   runtime_ticks,
                   start_position_ticks,
                   start_time,
                   device_name,
                   client_name,
                   created_at,
                   updated_at
            FROM emby_active_playback_sessions
            ORDER BY start_time DESC, id DESC
            LIMIT #{limit}
            """)
    List<EmbyActivePlaybackSession> selectRecentActiveSessions(@Param("limit") int limit);
}
