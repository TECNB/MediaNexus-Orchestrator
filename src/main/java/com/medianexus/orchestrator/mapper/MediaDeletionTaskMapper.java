package com.medianexus.orchestrator.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medianexus.orchestrator.model.MediaDeletionTask;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MediaDeletionTaskMapper extends BaseMapper<MediaDeletionTask> {
    @Update("""
            CREATE TABLE IF NOT EXISTS media_deletion_tasks (
                id VARCHAR(36) NOT NULL,
                library VARCHAR(32) NOT NULL,
                item_id VARCHAR(128) NOT NULL,
                season_id VARCHAR(128) NULL,
                season_number INT NULL,
                title VARCHAR(255) NOT NULL,
                target_label VARCHAR(255) NOT NULL,
                status VARCHAR(32) NOT NULL,
                stage VARCHAR(32) NOT NULL,
                source_paths LONGTEXT NOT NULL,
                strm_paths LONGTEXT NOT NULL,
                emby_item_ids LONGTEXT NOT NULL,
                bgm_target_key VARCHAR(64) NULL,
                bgm_previous_task_id VARCHAR(64) NULL,
                error_message VARCHAR(1024) NULL,
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                finished_at DATETIME NULL,
                PRIMARY KEY (id),
                KEY idx_media_deletions_status_created (status, created_at),
                KEY idx_media_deletions_item_created (item_id, created_at),
                KEY idx_media_deletions_season_created (season_id, created_at),
                KEY idx_media_deletions_bgm_target (bgm_target_key, created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """)
    void createTableIfNotExists();

    @Select("""
            SELECT COUNT(*) FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'media_deletion_tasks' AND column_name = 'bgm_target_key'
            """)
    int countBgmTargetColumn();

    @Update("""
            ALTER TABLE media_deletion_tasks ADD COLUMN bgm_target_key VARCHAR(64) NULL,
            ADD COLUMN bgm_previous_task_id VARCHAR(64) NULL,
            ADD INDEX idx_media_deletions_bgm_target (bgm_target_key, created_at)
            """)
    void addBgmTargetColumns();

    @Select("""
            SELECT * FROM media_deletion_tasks WHERE bgm_target_key = #{targetKey}
            ORDER BY created_at DESC, id DESC LIMIT 1
            """)
    MediaDeletionTask findLatestBgmDeletion(@Param("targetKey") String targetKey);

    @Select("""
            SELECT * FROM media_deletion_tasks
            ORDER BY created_at DESC
            LIMIT 100
            """)
    List<MediaDeletionTask> listRecent();

    @Select("""
            SELECT * FROM media_deletion_tasks
            WHERE status IN ('PENDING', 'RUNNING')
            ORDER BY created_at ASC
            LIMIT 1
            """)
    MediaDeletionTask findNextActive();

    @Select("SELECT * FROM media_deletion_tasks WHERE status IN ('PENDING', 'RUNNING')")
    List<MediaDeletionTask> listActive();

    @Select("""
            SELECT COUNT(*) FROM media_deletion_tasks
            WHERE (item_id = #{itemId} OR season_id = #{itemId})
              AND status IN ('PENDING', 'RUNNING')
            """)
    int countActiveTarget(@Param("itemId") String itemId);

    @Select("""
            SELECT COUNT(*) FROM media_deletion_tasks
            WHERE (item_id = #{itemId} OR season_id = #{itemId})
              AND status IN ('RUNNING', 'SUCCEEDED', 'FAILED')
              AND stage IN ('NOTIFYING_EMBY', 'VERIFYING', 'COMPLETED')
            """)
    int countReingestAllowedTarget(@Param("itemId") String itemId);
}
