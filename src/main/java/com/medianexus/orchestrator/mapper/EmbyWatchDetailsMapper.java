package com.medianexus.orchestrator.mapper;

import com.medianexus.orchestrator.dto.emby.response.EmbyWatchDetails;
import com.medianexus.orchestrator.model.EmbyActivePlaybackSession;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface EmbyWatchDetailsMapper {
    String DETAIL_SESSIONS = EmbyWatchSessionMapper.RANGE_SESSIONS + """
            , detail_sessions AS (
                SELECT s.*,
                       CASE WHEN s.watch_category = 'series'
                            THEN COALESCE(NULLIF(s.series_id, ''), s.item_id) ELSE s.item_id END AS media_id,
                       COALESCE(s.watch_category, 'unknown') AS category,
                       CASE WHEN s.watch_category = 'adult' THEN '私密观看'
                            WHEN COALESCE(s.watch_category, 'unknown') NOT IN ('movie', 'series') THEN '分类待确认'
                            WHEN s.watch_category = 'series' THEN COALESCE(NULLIF(s.series_name, ''), s.item_name, s.item_id)
                            ELSE COALESCE(NULLIF(s.item_name, ''), s.item_id) END AS public_title
                FROM range_sessions s
            )
            """;

    @Select(DETAIL_SESSIONS + """
            SELECT COUNT(DISTINCT emby_user_id) FROM detail_sessions
            """)
    long countUsers(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Select(DETAIL_SESSIONS + """
            , ordered_users AS (
                SELECT s.*, ROW_NUMBER() OVER (PARTITION BY emby_user_id ORDER BY stop_time DESC, id DESC) AS recent_order
                FROM detail_sessions s
            )
            SELECT ROW_NUMBER() OVER (ORDER BY SUM(range_watch_seconds) DESC, COUNT(*) DESC, emby_user_id ASC) AS `rank`,
                   emby_user_id,
                   COALESCE(MAX(CASE WHEN recent_order = 1 THEN NULLIF(emby_user_name, '') END), emby_user_id) AS user_name,
                   SUM(range_watch_seconds) AS watch_seconds,
                   COUNT(*) AS play_count,
                   COUNT(DISTINCT CONCAT(category, ':', media_id)) AS work_count,
                   MAX(CASE WHEN recent_order = 1 THEN stop_time END) AS last_watched_at,
                   MAX(CASE WHEN recent_order = 1 THEN public_title END) AS last_item_name,
                   MAX(CASE WHEN recent_order = 1 THEN item_id END) AS last_item_id,
                   MAX(CASE WHEN recent_order = 1 THEN media_id END) AS last_media_id,
                   MAX(CASE WHEN recent_order = 1 THEN category END) AS last_category,
                   MAX(CASE WHEN recent_order = 1 THEN season_number END) AS last_season_number,
                   MAX(CASE WHEN recent_order = 1 THEN episode_number END) AS last_episode_number
            FROM ordered_users
            GROUP BY emby_user_id
            ORDER BY watch_seconds DESC, play_count DESC, emby_user_id ASC
            LIMIT #{pageSize} OFFSET #{offset}
            """)
    List<EmbyWatchDetails.User> selectUsers(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate,
            @Param("offset") int offset, @Param("pageSize") int pageSize);

    String SCOPED_WORKS = DETAIL_SESSIONS + """
            , scoped_works AS (
                SELECT s.* FROM detail_sessions s
                WHERE (#{userId} IS NULL OR s.emby_user_id = #{userId})
                  AND (#{category} IS NULL OR s.category = #{category})
            ), ordered_works AS (
                SELECT s.*, ROW_NUMBER() OVER (PARTITION BY category, media_id ORDER BY stop_time DESC, id DESC) AS recent_order
                FROM scoped_works s
            )
            """;

    @Select(SCOPED_WORKS + """
            SELECT COUNT(*) FROM (SELECT category, media_id FROM ordered_works GROUP BY category, media_id) work_groups
            """)
    long countWorks(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate,
            @Param("userId") String userId, @Param("category") String category);

    @Select(SCOPED_WORKS + """
            SELECT ROW_NUMBER() OVER (ORDER BY
                       CASE WHEN #{sort} = 'viewers' THEN COUNT(DISTINCT emby_user_id)
                            WHEN #{sort} = 'plays' THEN COUNT(*) ELSE SUM(range_watch_seconds) END DESC,
                       SUM(range_watch_seconds) DESC, COUNT(*) DESC, category ASC, media_id ASC) AS `rank`,
                   media_id,
                   MAX(CASE WHEN recent_order = 1 THEN public_title END) AS title,
                   category,
                   MAX(CASE WHEN library_name = '综艺' THEN '综艺' END) AS content_label,
                   MAX(CASE WHEN recent_order = 1 AND category = 'movie' THEN item_id
                            WHEN recent_order = 1 AND category = 'series' THEN NULLIF(series_id, '') END) AS poster_item_id,
                   SUM(range_watch_seconds) AS watch_seconds,
                   COUNT(*) AS play_count,
                   COUNT(DISTINCT emby_user_id) AS viewer_count,
                   MAX(CASE WHEN recent_order = 1 THEN stop_time END) AS last_played_at,
                   MAX(CASE WHEN recent_order = 1 THEN season_number END) AS season_number,
                   MAX(CASE WHEN recent_order = 1 THEN episode_number END) AS episode_number
            FROM ordered_works
            GROUP BY category, media_id
            ORDER BY CASE WHEN #{sort} = 'viewers' THEN viewer_count
                          WHEN #{sort} = 'plays' THEN play_count ELSE watch_seconds END DESC,
                     watch_seconds DESC, play_count DESC, category ASC, media_id ASC
            LIMIT #{pageSize} OFFSET #{offset}
            """)
    List<EmbyWatchDetails.Work> selectWorks(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate,
            @Param("userId") String userId, @Param("category") String category, @Param("sort") String sort,
            @Param("offset") int offset, @Param("pageSize") int pageSize);

    @Select(DETAIL_SESSIONS + """
            SELECT COUNT(*) FROM detail_sessions
            WHERE emby_user_id = #{userId} AND (#{mediaId} IS NULL OR media_id = #{mediaId}) AND (#{category} IS NULL OR category = #{category})
            """)
    long countSessions(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate,
            @Param("userId") String userId, @Param("mediaId") String mediaId, @Param("category") String category);

    @Select(DETAIL_SESSIONS + """
            SELECT CONCAT('settled:', id) AS id, media_id, item_id, public_title AS title, category,
                   season_number, episode_number, start_time, stop_time, watch_seconds, range_watch_seconds,
                   COALESCE(accounting_basis, 'legacy') AS accounting_basis,
                   COALESCE(stop_time_source, 'unknown') AS stop_time_source,
                   device_name, client_name, emby_user_id,
                   COALESCE(NULLIF(emby_user_name, ''), emby_user_id) AS user_name,
                   'settled' AS state, NULL AS observed_at
            FROM detail_sessions
            WHERE emby_user_id = #{userId} AND (#{mediaId} IS NULL OR media_id = #{mediaId}) AND (#{category} IS NULL OR category = #{category})
            ORDER BY start_time DESC, id DESC
            LIMIT #{pageSize} OFFSET #{offset}
            """)
    List<EmbyWatchDetails.Session> selectSessions(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate,
            @Param("userId") String userId, @Param("mediaId") String mediaId, @Param("category") String category, @Param("offset") int offset, @Param("pageSize") int pageSize);

    @Select(DETAIL_SESSIONS + """
            SELECT COUNT(DISTINCT emby_user_id) FROM detail_sessions WHERE media_id = #{mediaId} AND category = #{category}
            """)
    long countViewers(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate, @Param("mediaId") String mediaId, @Param("category") String category);

    @Select(DETAIL_SESSIONS + """
            , ordered_viewers AS (
                SELECT s.*, ROW_NUMBER() OVER (PARTITION BY emby_user_id ORDER BY stop_time DESC, id DESC) AS recent_order
                FROM detail_sessions s WHERE media_id = #{mediaId} AND category = #{category}
            )
            SELECT emby_user_id,
                   COALESCE(MAX(CASE WHEN recent_order = 1 THEN NULLIF(emby_user_name, '') END), emby_user_id) AS user_name,
                   SUM(range_watch_seconds) AS watch_seconds, COUNT(*) AS play_count
            FROM ordered_viewers
            GROUP BY emby_user_id
            ORDER BY watch_seconds DESC, play_count DESC, emby_user_id ASC
            LIMIT #{pageSize} OFFSET #{offset}
            """)
    List<EmbyWatchDetails.Viewer> selectViewers(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate,
            @Param("mediaId") String mediaId, @Param("category") String category, @Param("offset") int offset, @Param("pageSize") int pageSize);

    /** 核验快照不伪装成新的播放事件；并发核验只能向前推进。 */
    @Update("""
            UPDATE emby_active_playback_sessions
            SET last_verified_at = GREATEST(COALESCE(last_verified_at, #{observedAt}), #{observedAt}),
                updated_at = updated_at
            WHERE id = #{id}
            """)
    void recordVerifiedPlayback(@Param("id") long id, @Param("observedAt") LocalDateTime observedAt);

    @Select("""
            SELECT * FROM emby_active_playback_sessions
            WHERE start_time < #{endAt} AND (#{userId} IS NULL OR emby_user_id = #{userId})
            ORDER BY start_time DESC, id DESC
            """)
    List<EmbyActivePlaybackSession> selectTrackedSessions(@Param("endAt") LocalDateTime endAt, @Param("userId") String userId);
}
