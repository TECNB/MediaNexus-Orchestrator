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
                error_message VARCHAR(1024) NULL,
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                finished_at DATETIME NULL,
                PRIMARY KEY (id),
                KEY idx_media_deletions_status_created (status, created_at),
                KEY idx_media_deletions_item_created (item_id, created_at),
                KEY idx_media_deletions_season_created (season_id, created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """)
    void createTableIfNotExists();

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
