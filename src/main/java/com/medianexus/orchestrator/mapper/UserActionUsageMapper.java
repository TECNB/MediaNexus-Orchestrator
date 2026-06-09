package com.medianexus.orchestrator.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medianexus.orchestrator.model.UserActionUsage;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserActionUsageMapper extends BaseMapper<UserActionUsage> {

    @Update("""
            CREATE TABLE IF NOT EXISTS user_action_usage (
                id BIGINT NOT NULL AUTO_INCREMENT,
                user_id BIGINT NOT NULL,
                action_type VARCHAR(64) NOT NULL,
                usage_date DATE NOT NULL,
                used_count INT NOT NULL DEFAULT 0,
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                UNIQUE KEY uk_user_action_usage_user_date_action (user_id, usage_date, action_type),
                KEY idx_user_action_usage_user_date (user_id, usage_date)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """)
    void createTableIfNotExists();

    @Insert("""
            INSERT INTO user_action_usage (user_id, action_type, usage_date, used_count)
            VALUES (#{userId}, #{actionType}, #{usageDate}, 0)
            ON DUPLICATE KEY UPDATE action_type = action_type
            """)
    void ensureUsageRow(
            @Param("userId") Long userId,
            @Param("actionType") String actionType,
            @Param("usageDate") LocalDate usageDate
    );

    @Select("""
            <script>
            SELECT id, user_id, action_type, usage_date, used_count, created_at, updated_at
            FROM user_action_usage
            WHERE user_id = #{userId}
              AND usage_date = #{usageDate}
              AND action_type IN
              <foreach collection="actionTypes" item="actionType" open="(" separator="," close=")">
                  #{actionType}
              </foreach>
            FOR UPDATE
            </script>
            """)
    List<UserActionUsage> selectUsageRowsForUpdate(
            @Param("userId") Long userId,
            @Param("usageDate") LocalDate usageDate,
            @Param("actionTypes") List<String> actionTypes
    );

    @Select("""
            <script>
            SELECT COALESCE(SUM(used_count), 0)
            FROM user_action_usage
            WHERE user_id = #{userId}
              AND usage_date = #{usageDate}
              AND action_type IN
              <foreach collection="actionTypes" item="actionType" open="(" separator="," close=")">
                  #{actionType}
              </foreach>
            </script>
            """)
    Integer sumUsageCount(
            @Param("userId") Long userId,
            @Param("usageDate") LocalDate usageDate,
            @Param("actionTypes") List<String> actionTypes
    );

    @Update("""
            UPDATE user_action_usage
            SET used_count = used_count + 1
            WHERE user_id = #{userId}
              AND action_type = #{actionType}
              AND usage_date = #{usageDate}
            """)
    void incrementUsageCount(
            @Param("userId") Long userId,
            @Param("actionType") String actionType,
            @Param("usageDate") LocalDate usageDate
    );
}
