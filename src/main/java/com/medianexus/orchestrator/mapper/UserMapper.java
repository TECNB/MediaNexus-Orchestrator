package com.medianexus.orchestrator.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medianexus.orchestrator.mapper.projection.AdminUserUsageRow;
import com.medianexus.orchestrator.mapper.projection.UserUsagePeakRow;
import com.medianexus.orchestrator.model.User;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    @Update("""
            CREATE TABLE IF NOT EXISTS users (
                id BIGINT NOT NULL AUTO_INCREMENT,
                username VARCHAR(32) NOT NULL,
                email VARCHAR(128) NOT NULL,
                password_hash VARCHAR(100) NOT NULL,
                user_role VARCHAR(32) NOT NULL,
                emby_user_id VARCHAR(128) NULL,
                daily_content_create_limit_override INT NULL,
                invited_by_user_id BIGINT NULL,
                invited_by_username VARCHAR(32) NULL,
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                UNIQUE KEY uk_users_username (username),
                UNIQUE KEY uk_users_email (email),
                UNIQUE KEY uk_users_emby_user_id (emby_user_id),
                KEY idx_users_role (user_role)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """)
    void createTableIfNotExists();

    @Select("""
            SELECT COUNT(*)
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'users'
              AND COLUMN_NAME = 'daily_content_create_limit_override'
            """)
    Integer countDailyContentCreateLimitOverrideColumn();

    @Update("""
            ALTER TABLE users
            ADD COLUMN daily_content_create_limit_override INT NULL AFTER user_role
            """)
    void addDailyContentCreateLimitOverrideColumn();

    @Select("""
            SELECT COUNT(*)
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'users'
              AND COLUMN_NAME = 'emby_user_id'
            """)
    Integer countEmbyUserIdColumn();

    @Update("""
            ALTER TABLE users
            ADD COLUMN emby_user_id VARCHAR(128) NULL AFTER user_role
            """)
    void addEmbyUserIdColumn();

    @Select("""
            SELECT COUNT(*)
            FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'users'
              AND INDEX_NAME = 'uk_users_emby_user_id'
            """)
    Integer countEmbyUserIdUniqueIndex();

    @Update("""
            ALTER TABLE users
            ADD UNIQUE KEY uk_users_emby_user_id (emby_user_id)
            """)
    void addEmbyUserIdUniqueIndex();

    @Select("""
            SELECT COUNT(*)
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'users'
              AND COLUMN_NAME = 'invited_by_user_id'
            """)
    Integer countInvitedByUserIdColumn();

    @Update("""
            ALTER TABLE users
            ADD COLUMN invited_by_user_id BIGINT NULL AFTER daily_content_create_limit_override
            """)
    void addInvitedByUserIdColumn();

    @Select("""
            SELECT COUNT(*)
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'users'
              AND COLUMN_NAME = 'invited_by_username'
            """)
    Integer countInvitedByUsernameColumn();

    @Update("""
            ALTER TABLE users
            ADD COLUMN invited_by_username VARCHAR(32) NULL AFTER invited_by_user_id
            """)
    void addInvitedByUsernameColumn();

    @Update("""
            UPDATE users
            SET emby_user_id = #{embyUserId}
            WHERE id = #{userId}
            """)
    void updateEmbyUserId(
            @Param("userId") Long userId,
            @Param("embyUserId") String embyUserId
    );

    @Update("""
            UPDATE users
            SET daily_content_create_limit_override = #{quotaOverride,jdbcType=INTEGER}
            WHERE id = #{userId}
            """)
    void updateDailyContentCreateLimitOverride(
            @Param("userId") Long userId,
            @Param("quotaOverride") Integer quotaOverride
    );

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM users u
            WHERE 1 = 1
            <if test="keyword != null and keyword != ''">
              AND (LOWER(u.username) LIKE #{keyword} OR LOWER(u.email) LIKE #{keyword})
            </if>
            <if test="role != null and role != ''">
              AND UPPER(u.user_role) = #{role}
            </if>
            </script>
            """)
    Long countAdminUsers(
            @Param("keyword") String keyword,
            @Param("role") String role
    );

    @Select("""
            <script>
            SELECT
                u.id,
                u.username,
                u.email,
                u.user_role AS role,
                u.daily_content_create_limit_override,
                u.invited_by_user_id,
                u.invited_by_username,
                u.created_at,
                u.updated_at,
                COALESCE(SUM(usage_row.used_count), 0) AS used_count,
                COALESCE(SUM(CASE WHEN usage_row.action_type = 'MAGNET_INGEST_CREATE' THEN usage_row.used_count ELSE 0 END), 0)
                    AS magnet_ingest_create_count,
                COALESCE(SUM(CASE WHEN usage_row.action_type = 'ANIME_SUBSCRIBE_CREATE' THEN usage_row.used_count ELSE 0 END), 0)
                    AS anime_subscribe_create_count
            FROM users u
            LEFT JOIN user_action_usage usage_row
              ON usage_row.user_id = u.id
             AND usage_row.usage_date = #{usageDate}
             AND usage_row.action_type IN
             <foreach collection="actionTypes" item="actionType" open="(" separator="," close=")">
                #{actionType}
             </foreach>
            WHERE 1 = 1
            <if test="keyword != null and keyword != ''">
              AND (LOWER(u.username) LIKE #{keyword} OR LOWER(u.email) LIKE #{keyword})
            </if>
            <if test="role != null and role != ''">
              AND UPPER(u.user_role) = #{role}
            </if>
            GROUP BY
                u.id,
                u.username,
                u.email,
                u.user_role,
                u.daily_content_create_limit_override,
                u.invited_by_user_id,
                u.invited_by_username,
                u.created_at,
                u.updated_at
            <choose>
              <when test="sort == 'CREATED_AT_ASC'">
                ORDER BY u.created_at ASC, u.id ASC
              </when>
              <when test="sort == 'USED_COUNT_DESC'">
                ORDER BY used_count DESC, u.created_at DESC, u.id DESC
              </when>
              <when test="sort == 'USED_COUNT_ASC'">
                ORDER BY used_count ASC, u.created_at DESC, u.id DESC
              </when>
              <otherwise>
                ORDER BY u.created_at DESC, u.id DESC
              </otherwise>
            </choose>
            LIMIT #{pageSize} OFFSET #{offset}
            </script>
            """)
    List<AdminUserUsageRow> selectAdminUsers(
            @Param("keyword") String keyword,
            @Param("role") String role,
            @Param("usageDate") LocalDate usageDate,
            @Param("actionTypes") List<String> actionTypes,
            @Param("sort") String sort,
            @Param("pageSize") int pageSize,
            @Param("offset") int offset
    );

    @Select("""
            <script>
            SELECT used_count, COUNT(*) AS user_count
            FROM (
                SELECT u.id, COALESCE(SUM(usage_row.used_count), 0) AS used_count
                FROM users u
                LEFT JOIN user_action_usage usage_row
                  ON usage_row.user_id = u.id
                 AND usage_row.usage_date = #{usageDate}
                 AND usage_row.action_type IN
                 <foreach collection="actionTypes" item="actionType" open="(" separator="," close=")">
                    #{actionType}
                 </foreach>
                WHERE UPPER(u.user_role) = 'USER'
                GROUP BY u.id
            ) daily_usage
            GROUP BY used_count
            ORDER BY used_count DESC
            LIMIT 1
            </script>
            """)
    UserUsagePeakRow selectUserUsagePeak(
            @Param("usageDate") LocalDate usageDate,
            @Param("actionTypes") List<String> actionTypes
    );

    @Select("""
            SELECT COUNT(*)
            FROM users
            WHERE UPPER(user_role) = #{role}
            """)
    Long countUsersByRole(@Param("role") String role);
}
