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

    /** 一行对应一次有效已结算会话；自然日贡献先按会话求和，再进行人数及播放次数统计。 */
    String RANGE_SESSIONS = """
            WITH range_sessions AS (
                SELECT sessions.*,
                       CASE WHEN sessions.accounting_basis = 'calendar'
                            THEN daily.range_watch_seconds ELSE sessions.watch_seconds END AS range_watch_seconds
                FROM emby_watch_sessions sessions
                LEFT JOIN (
                    SELECT emby_session_id, item_id, start_time, SUM(watch_seconds) AS range_watch_seconds
                    FROM emby_watch_daily_contributions
                    WHERE watch_date >= #{startDate} AND #{endDate} > watch_date
                    GROUP BY emby_session_id, item_id, start_time
                ) daily ON daily.emby_session_id = sessions.emby_session_id
                       AND daily.item_id = sessions.item_id AND daily.start_time = sessions.start_time
                WHERE (sessions.accounting_basis = 'calendar' AND daily.range_watch_seconds > 0)
                   OR (COALESCE(sessions.accounting_basis, 'legacy') = 'legacy'
                       AND sessions.watch_date >= #{startDate} AND #{endDate} > sessions.watch_date)
            )
            """;

    @Update("""
            CREATE TABLE IF NOT EXISTS emby_watch_sessions (
                id BIGINT NOT NULL AUTO_INCREMENT,
                emby_session_id VARCHAR(128) NOT NULL,
                emby_user_id VARCHAR(128) NOT NULL,
                emby_user_name VARCHAR(255) NULL,
                item_id VARCHAR(128) NOT NULL,
                item_type VARCHAR(64) NOT NULL,
                watch_category VARCHAR(16) NULL,
                library_id VARCHAR(128) NULL,
                library_name VARCHAR(255) NULL,
                item_name VARCHAR(512) NULL,
                series_id VARCHAR(128) NULL,
                series_name VARCHAR(512) NULL,
                season_number INT NULL,
                episode_number INT NULL,
                runtime_ticks BIGINT NULL,
                start_time DATETIME NOT NULL,
                stop_time DATETIME NOT NULL,
                stop_time_source VARCHAR(16) NULL,
                start_position_ticks BIGINT NULL,
                stop_position_ticks BIGINT NULL,
                accounting_basis VARCHAR(16) NULL,
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
            SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'emby_watch_sessions'
              AND COLUMN_NAME = 'stop_time_source'
            """)
    Integer countStopTimeSourceColumn();

    @Update("""
            ALTER TABLE emby_watch_sessions ADD COLUMN stop_time_source VARCHAR(16) NULL
            """)
    void addStopTimeSourceColumn();

    @Select("""
            SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'emby_watch_sessions'
              AND COLUMN_NAME = 'accounting_basis'
            """)
    Integer countAccountingBasisColumn();

    @Update("""
            ALTER TABLE emby_watch_sessions ADD COLUMN accounting_basis VARCHAR(16) NULL
            """)
    void addAccountingBasisColumn();

    @Update("""
            CREATE TABLE IF NOT EXISTS emby_watch_daily_contributions (
                emby_session_id VARCHAR(128) NOT NULL,
                item_id VARCHAR(128) NOT NULL,
                start_time DATETIME NOT NULL,
                watch_date DATE NOT NULL,
                watch_seconds INT NOT NULL,
                PRIMARY KEY (emby_session_id, item_id, start_time, watch_date),
                KEY idx_emby_daily_watch_date (watch_date)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """)
    void createDailyContributionsTableIfNotExists();

    @Update("""
            DELETE FROM emby_watch_daily_contributions
            WHERE emby_session_id = #{session.embySessionId} AND item_id = #{session.itemId}
              AND start_time = #{session.startTime}
            """)
    void deleteDailyContributions(@Param("session") EmbyWatchSession session);

    @Insert("""
            INSERT INTO emby_watch_daily_contributions
                (emby_session_id, item_id, start_time, watch_date, watch_seconds)
            VALUES (#{session.embySessionId}, #{session.itemId}, #{session.startTime}, #{watchDate}, #{seconds})
            """)
    void insertDailyContribution(@Param("session") EmbyWatchSession session,
            @Param("watchDate") LocalDate watchDate, @Param("seconds") int seconds);

    @Select("""
            SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'emby_watch_sessions'
              AND COLUMN_NAME = 'watch_category'
            """)
    Integer countWatchCategoryColumn();

    @Update("""
            ALTER TABLE emby_watch_sessions
            ADD COLUMN watch_category VARCHAR(16) NULL,
            ADD COLUMN library_id VARCHAR(128) NULL,
            ADD COLUMN library_name VARCHAR(255) NULL
            """)
    void addWatchClassificationColumns();

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
                watch_category,
                library_id,
                library_name,
                item_name,
                series_id,
                series_name,
                season_number,
                episode_number,
                runtime_ticks,
                start_time,
                stop_time,
                stop_time_source,
                start_position_ticks,
                stop_position_ticks,
                accounting_basis,
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
                #{session.watchCategory},
                #{session.libraryId},
                #{session.libraryName},
                #{session.itemName},
                #{session.seriesId},
                #{session.seriesName},
                #{session.seasonNumber},
                #{session.episodeNumber},
                #{session.runtimeTicks},
                #{session.startTime},
                #{session.stopTime},
                #{session.stopTimeSource},
                #{session.startPositionTicks},
                #{session.stopPositionTicks},
                #{session.accountingBasis},
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
                   watch_category,
                   library_id,
                   library_name,
                   item_name,
                   series_id,
                   series_name,
                   season_number,
                   episode_number,
                   runtime_ticks,
                   start_time,
                   stop_time,
                   stop_time_source,
                   start_position_ticks,
                   stop_position_ticks,
                   accounting_basis,
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
                watch_category = #{session.watchCategory},
                library_id = #{session.libraryId},
                library_name = #{session.libraryName},
                item_name = #{session.itemName},
                series_id = #{session.seriesId},
                series_name = #{session.seriesName},
                season_number = #{session.seasonNumber},
                episode_number = #{session.episodeNumber},
                runtime_ticks = #{session.runtimeTicks},
                stop_time = #{session.stopTime},
                stop_time_source = #{session.stopTimeSource},
                start_position_ticks = #{session.startPositionTicks},
                stop_position_ticks = #{session.stopPositionTicks},
                accounting_basis = #{session.accountingBasis},
                watch_seconds = #{session.watchSeconds},
                watch_date = #{session.watchDate},
                device_name = #{session.deviceName},
                client_name = #{session.clientName}
            WHERE id = #{session.id}
            """)
    int updateWatchSessionById(@Param("session") EmbyWatchSession session);

    @Select(RANGE_SESSIONS + """
            SELECT COUNT(DISTINCT emby_user_id) AS active_user_count,
                   COALESCE(SUM(range_watch_seconds), 0) AS total_watch_seconds,
                   COUNT(*) AS total_play_count,
                   MAX(stop_time) AS last_watched_at,
                   SUM(CASE WHEN accounting_basis = 'calendar' THEN 1 ELSE 0 END) AS calendar_session_count,
                   SUM(CASE WHEN COALESCE(accounting_basis, 'legacy') = 'legacy' THEN 1 ELSE 0 END) AS legacy_session_count,
                   COUNT(DISTINCT CONCAT(COALESCE(watch_category, 'unknown'), ':',
                       CASE WHEN watch_category = 'series' THEN COALESCE(NULLIF(series_id, ''), item_id) ELSE item_id END)) AS total_work_count
            FROM range_sessions

            """)
    EmbyWatchSummaryRow selectSummary(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    @Select(RANGE_SESSIONS + """
            SELECT sessions.emby_user_id,
                   (
                       SELECT latest.emby_user_name
                       FROM range_sessions latest
                       WHERE latest.emby_user_id = sessions.emby_user_id
                       ORDER BY latest.stop_time DESC, latest.id DESC
                       LIMIT 1
                   ) AS user_name,
                   SUM(sessions.range_watch_seconds) AS watch_seconds,
                   COUNT(*) AS play_count,
                   MAX(sessions.stop_time) AS last_watched_at,
                   (
                       SELECT CASE
                                  WHEN latest.watch_category = 'adult' THEN '私密观看'
                                  WHEN COALESCE(latest.watch_category, 'unknown') NOT IN ('movie', 'series') THEN '分类待确认'
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
                       FROM range_sessions latest
                       WHERE latest.emby_user_id = sessions.emby_user_id
                       ORDER BY latest.stop_time DESC, latest.id DESC
                       LIMIT 1
                   ) AS last_item_name,
                   (
                       SELECT latest.item_id FROM range_sessions latest
                       WHERE latest.emby_user_id = sessions.emby_user_id
                       ORDER BY latest.stop_time DESC, latest.id DESC LIMIT 1
                   ) AS last_item_id,
                   (
                       SELECT CASE WHEN latest.watch_category = 'series' THEN COALESCE(NULLIF(latest.series_id, ''), latest.item_id) ELSE latest.item_id END FROM range_sessions latest
                       WHERE latest.emby_user_id = sessions.emby_user_id
                       ORDER BY latest.stop_time DESC, latest.id DESC LIMIT 1
                   ) AS last_media_id,
                   (
                       SELECT COALESCE(latest.watch_category, 'unknown') FROM range_sessions latest
                       WHERE latest.emby_user_id = sessions.emby_user_id
                       ORDER BY latest.stop_time DESC, latest.id DESC LIMIT 1
                   ) AS last_category
            FROM range_sessions sessions

            GROUP BY sessions.emby_user_id
            ORDER BY watch_seconds DESC, play_count DESC, user_name ASC
            LIMIT #{limit}
            """)
    List<EmbyUserWatchRankingRow> selectUserRankings(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("limit") int limit
    );

    @Select(RANGE_SESSIONS + """
            SELECT sessions.item_id AS media_id,
                   (
                       SELECT latest.item_name
                       FROM range_sessions latest
                       WHERE latest.watch_category = 'movie'
                         AND latest.item_id = sessions.item_id
                       ORDER BY latest.stop_time DESC, latest.id DESC
                       LIMIT 1
                   ) AS title,
                   SUM(sessions.range_watch_seconds) AS watch_seconds,
                   COUNT(*) AS play_count,
                   MAX(sessions.stop_time) AS last_played_at
            FROM range_sessions sessions
            WHERE sessions.watch_category = 'movie'
            GROUP BY sessions.item_id
            ORDER BY watch_seconds DESC, play_count DESC, title ASC
            LIMIT #{limit}
            """)
    List<EmbyMediaWatchRankingRow> selectMovieRankings(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("limit") int limit
    );

    @Select(RANGE_SESSIONS + """
            SELECT sessions.item_id AS media_id,
                   (
                       SELECT '私密观看'
                       FROM range_sessions latest
                       WHERE latest.watch_category = 'adult'
                         AND latest.item_id = sessions.item_id
                       ORDER BY latest.stop_time DESC, latest.id DESC
                       LIMIT 1
                   ) AS title,
                   SUM(sessions.range_watch_seconds) AS watch_seconds,
                   COUNT(*) AS play_count,
                   MAX(sessions.stop_time) AS last_played_at
            FROM range_sessions sessions
            WHERE sessions.watch_category = 'adult'
            GROUP BY sessions.item_id
            ORDER BY watch_seconds DESC, play_count DESC, title ASC
            LIMIT #{limit}
            """)
    List<EmbyMediaWatchRankingRow> selectAdultRankings(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("limit") int limit
    );

    @Select(RANGE_SESSIONS + """
            SELECT grouped.group_key AS media_id,
                   (
                       SELECT COALESCE(NULLIF(latest.series_name, ''), latest.item_name)
                       FROM range_sessions latest
                       WHERE latest.watch_category = 'series'
                         AND COALESCE(NULLIF(latest.series_id, ''), latest.item_id) = grouped.group_key
                       ORDER BY latest.stop_time DESC, latest.id DESC
                       LIMIT 1
                   ) AS title,
                   grouped.watch_seconds,
                   grouped.play_count,
                   grouped.last_played_at
            FROM (
                SELECT COALESCE(NULLIF(series_id, ''), item_id) AS group_key,
                       SUM(range_watch_seconds) AS watch_seconds,
                       COUNT(*) AS play_count,
                       MAX(stop_time) AS last_played_at
                FROM range_sessions
                WHERE watch_category = 'series'
                GROUP BY COALESCE(NULLIF(series_id, ''), item_id)
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
                   watch_category,
                   library_id,
                   library_name,
                   item_name,
                   series_id,
                   series_name,
                   season_number,
                   episode_number,
                   runtime_ticks,
                   start_time,
                   stop_time,
                   stop_time_source,
                   start_position_ticks,
                   stop_position_ticks,
                   accounting_basis,
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
    @Select("""
            <script>
            SELECT item_id, item_id AS media_id, item_name AS title
            FROM (
                SELECT item_id, item_name,
                       ROW_NUMBER() OVER (PARTITION BY item_id ORDER BY event_time DESC, id DESC) AS row_number_in_item
                FROM (
                    SELECT id, item_id, item_name, stop_time AS event_time FROM emby_watch_sessions
                    WHERE watch_category = 'adult' AND item_id IN
                    <foreach collection="itemIds" item="itemId" open="(" separator="," close=")">#{itemId}</foreach>
                    UNION ALL
                    SELECT id, item_id, item_name, start_time AS event_time FROM emby_active_playback_sessions
                    WHERE watch_category = 'adult' AND item_id IN
                    <foreach collection="itemIds" item="itemId" open="(" separator="," close=")">#{itemId}</foreach>
                ) candidates
            ) numbered
            WHERE row_number_in_item = 1
            </script>
            """)
    List<com.medianexus.orchestrator.dto.emby.response.EmbyPrivateWatchTitle> selectPrivateTitles(
            @Param("itemIds") List<String> itemIds
    );

}
