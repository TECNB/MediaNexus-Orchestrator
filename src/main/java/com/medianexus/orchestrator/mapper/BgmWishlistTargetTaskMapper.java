package com.medianexus.orchestrator.mapper;

import com.medianexus.orchestrator.service.BgmWishlistTaskGateway.ActiveTask;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 只记录 BGM 创建过的目录目标；普通入口的历史失败不属于此账本。 */
@Mapper
public interface BgmWishlistTargetTaskMapper {
    @Update("""
            CREATE TABLE IF NOT EXISTS bgm_wishlist_target_tasks (
                target_key VARCHAR(64) NOT NULL PRIMARY KEY,
                task_type VARCHAR(16) NOT NULL,
                task_id VARCHAR(64) NOT NULL,
                status VARCHAR(32) NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """)
    void createTableIfNotExists();

    @Select("SELECT task_type AS taskType, task_id AS taskId, status FROM bgm_wishlist_target_tasks WHERE target_key = #{key}")
    ActiveTask select(@Param("key") String key);

    @Insert("""
            INSERT INTO bgm_wishlist_target_tasks (target_key, task_type, task_id, status)
            VALUES (#{key}, #{task.taskType}, #{task.taskId}, #{task.status})
            """)
    void insert(@Param("key") String key, @Param("task") ActiveTask task);
}
