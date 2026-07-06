package com.medianexus.orchestrator.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medianexus.orchestrator.mapper.projection.EmbyMediaWatchRankingRow;
import com.medianexus.orchestrator.mapper.projection.EmbyUserWatchRankingRow;
import com.medianexus.orchestrator.mapper.projection.EmbyWatchSummaryRow;
import com.medianexus.orchestrator.model.EmbyWatchSession;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface EmbyWatchSessionMapper extends BaseMapper<EmbyWatchSession> {

    @Update("""
            CREATE TABLE IF NOT EXISTS emby_watch_sessions (
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
                start_time DATETIME NOT NULL,
                stop_time DATETIME NOT NULL,
                start_position_ticks BIGINT NULL,
                stop_position_ticks BIGINT NULL,
                watch_seconds INT NOT NULL,
                watch_date DATE NOT NULL,
                device_name VARCHAR(255) NULL,
                client_name VARCHAR(255) NULL,
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                UNIQUE KEY uk_emby_watch_settlement (emby_session_id, item_id, stop_time),
                KEY idx_emby_watch_date (watch_date),
                KEY idx_emby_watch_user_date (emby_user_id, watch_date),
                KEY idx_emby_watch_item_date (item_id, watch_date),
                KEY idx_emby_watch_type_date (item_type, watch_date)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """)
    void createTableIfNotExists();

    @Select("""
            SELECT COUNT(*)
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'emby_watch_sessions'
              AND COLUMN_NAME = 'start_position_ticks'
              AND IS_NULLABLE = 'NO'
            """)
    Integer countRequiredStartPositionTicksColumn();

    @Select("""
            SELECT COUNT(*)
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'emby_watch_sessions'
              AND COLUMN_NAME = 'stop_position_ticks'
              AND IS_NULLABLE = 'NO'
            """)
    Integer countRequiredStopPositionTicksColumn();

    @Select("""
            SELECT COUNT(*)
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'emby_watch_sessions'
              AND COLUMN_NAME = 'season_number'
            """)
    Integer countSeasonNumberColumn();

    @Update("""
            ALTER TABLE emby_watch_sessions
            ADD COLUMN season_number INT NULL AFTER series_name,
            ADD COLUMN episode_number INT NULL AFTER season_number
            """)
    void addEpisodePositionColumns();

    @Update("""
            ALTER TABLE emby_watch_sessions
            MODIFY start_position_ticks BIGINT NULL
            """)
    void makeStartPositionTicksNullable();

    @Update("""
            ALTER TABLE emby_watch_sessions
            MODIFY stop_position_ticks BIGINT NULL
            """)
    void makeStopPositionTicksNullable();

    @Insert("""
            INSERT INTO emby_watch_sessions (
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
                start_time,
                stop_time,
                start_position_ticks,
                stop_position_ticks,
                watch_seconds,
                watch_date,
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
                #{session.startTime},
                #{session.stopTime},
                #{session.startPositionTicks},
                #{session.stopPositionTicks},
                #{session.watchSeconds},
                #{session.watchDate},
                #{session.deviceName},
                #{session.clientName}
            )
            ON DUPLICATE KEY UPDATE id = id
            """)
    void insertWatchSessionIfAbsent(@Param("session") EmbyWatchSession session);

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
                   start_time,
                   stop_time,
                   start_position_ticks,
                   stop_position_ticks,
                   watch_seconds,
                   watch_date,
                   device_name,
                   client_name,
                   created_at
            FROM emby_watch_sessions
            WHERE emby_session_id = #{embySessionId}
              AND item_id = #{itemId}
              AND start_time = #{startTime}
            ORDER BY id DESC
            LIMIT 1
            FOR UPDATE
            """)
    EmbyWatchSession selectWatchSessionByStartForUpdate(
            @Param("embySessionId") String embySessionId,
            @Param("itemId") String itemId,
            @Param("startTime") LocalDateTime startTime
    );

    @Update("""
            UPDATE emby_watch_sessions
            SET emby_user_id = #{session.embyUserId},
                emby_user_name = #{session.embyUserName},
                item_type = #{session.itemType},
                item_name = #{session.itemName},
                series_id = #{session.seriesId},
                series_name = #{session.seriesName},
                season_number = #{session.seasonNumber},
                episode_number = #{session.episodeNumber},
                runtime_ticks = #{session.runtimeTicks},
                stop_time = #{session.stopTime},
                start_position_ticks = #{session.startPositionTicks},
                stop_position_ticks = #{session.stopPositionTicks},
                watch_seconds = #{session.watchSeconds},
                watch_date = #{session.watchDate},
                device_name = #{session.deviceName},
                client_name = #{session.clientName}
            WHERE id = #{session.id}
            """)
    int updateWatchSessionById(@Param("session") EmbyWatchSession session);

    @Select("""
            SELECT COUNT(DISTINCT emby_user_id) AS active_user_count,
                   COALESCE(SUM(watch_seconds), 0) AS total_watch_seconds,
                   COUNT(*) AS total_play_count,
                   MAX(stop_time) AS last_watched_at
            FROM emby_watch_sessions
            WHERE watch_date >= #{startDate}
              AND watch_date < #{endDate}
            """)
    EmbyWatchSummaryRow selectSummary(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    @Select("""
            SELECT sessions.emby_user_id,
                   (
                       SELECT latest.emby_user_name
                       FROM emby_watch_sessions latest
                       WHERE latest.watch_date >= #{startDate}
                         AND latest.watch_date < #{endDate}
                         AND latest.emby_user_id = sessions.emby_user_id
                       ORDER BY latest.stop_time DESC, latest.id DESC
                       LIMIT 1
                   ) AS user_name,
                   SUM(sessions.watch_seconds) AS watch_seconds,
                   COUNT(*) AS play_count,
                   MAX(sessions.stop_time) AS last_watched_at,
                   (
                       SELECT CASE
                                  WHEN latest.item_type = 'Episode'
                                      AND latest.season_number IS NOT NULL
                                      AND latest.episode_number IS NOT NULL
                                  THEN CONCAT(
                                          COALESCE(NULLIF(latest.series_name, ''), latest.item_name, latest.item_id),
                                          ' S',
                                          LPAD(latest.season_number, 2, '0'),
                                          'E',
                                          LPAD(latest.episode_number, 2, '0')
                                  )
                                  ELSE COALESCE(NULLIF(latest.series_name, ''), latest.item_name)
                              END
                       FROM emby_watch_sessions latest
                       WHERE latest.watch_date >= #{startDate}
                         AND latest.watch_date < #{endDate}
                         AND latest.emby_user_id = sessions.emby_user_id
                       ORDER BY latest.stop_time DESC, latest.id DESC
                       LIMIT 1
                   ) AS last_item_name
            FROM emby_watch_sessions sessions
            WHERE sessions.watch_date >= #{startDate}
              AND sessions.watch_date < #{endDate}
            GROUP BY sessions.emby_user_id
            ORDER BY watch_seconds DESC, play_count DESC, user_name ASC
            LIMIT #{limit}
            """)
    List<EmbyUserWatchRankingRow> selectUserRankings(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("limit") int limit
    );

    @Select("""
            SELECT sessions.item_id AS media_id,
                   (
                       SELECT latest.item_name
                       FROM emby_watch_sessions latest
                       WHERE latest.watch_date >= #{startDate}
                         AND latest.watch_date < #{endDate}
                         AND latest.item_type = 'Movie'
                         AND latest.item_id = sessions.item_id
                       ORDER BY latest.stop_time DESC, latest.id DESC
                       LIMIT 1
                   ) AS title,
                   SUM(sessions.watch_seconds) AS watch_seconds,
                   COUNT(*) AS play_count,
                   MAX(sessions.stop_time) AS last_played_at
            FROM emby_watch_sessions sessions
            WHERE sessions.watch_date >= #{startDate}
              AND sessions.watch_date < #{endDate}
              AND sessions.item_type = 'Movie'
            GROUP BY sessions.item_id
            ORDER BY watch_seconds DESC, play_count DESC, title ASC
            LIMIT #{limit}
            """)
    List<EmbyMediaWatchRankingRow> selectMovieRankings(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("limit") int limit
    );

    @Select("""
            SELECT grouped.group_key AS media_id,
                   (
                       SELECT COALESCE(NULLIF(latest.series_name, ''), latest.item_name)
                       FROM emby_watch_sessions latest
                       WHERE latest.watch_date >= #{startDate}
                         AND latest.watch_date < #{endDate}
                         AND latest.item_type = 'Episode'
                         AND COALESCE(NULLIF(latest.series_id, ''), NULLIF(latest.series_name, ''), latest.item_id) = grouped.group_key
                       ORDER BY latest.stop_time DESC, latest.id DESC
                       LIMIT 1
                   ) AS title,
                   grouped.watch_seconds,
                   grouped.play_count,
                   grouped.last_played_at
            FROM (
                SELECT COALESCE(NULLIF(series_id, ''), NULLIF(series_name, ''), item_id) AS group_key,
                       SUM(watch_seconds) AS watch_seconds,
                       COUNT(*) AS play_count,
                       MAX(stop_time) AS last_played_at
                FROM emby_watch_sessions
                WHERE watch_date >= #{startDate}
                  AND watch_date < #{endDate}
                  AND item_type = 'Episode'
                GROUP BY COALESCE(NULLIF(series_id, ''), NULLIF(series_name, ''), item_id)
            ) grouped
            ORDER BY grouped.watch_seconds DESC, grouped.play_count DESC, title ASC
            LIMIT #{limit}
            """)
    List<EmbyMediaWatchRankingRow> selectSeriesRankings(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("limit") int limit
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
                   start_time,
                   stop_time,
                   start_position_ticks,
                   stop_position_ticks,
                   watch_seconds,
                   watch_date,
                   device_name,
                   client_name,
                   created_at
            FROM emby_watch_sessions
            ORDER BY stop_time DESC, id DESC
            LIMIT #{limit}
            """)
    List<EmbyWatchSession> selectRecentWatchSessions(@Param("limit") int limit);
}
