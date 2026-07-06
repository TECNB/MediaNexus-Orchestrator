package com.medianexus.orchestrator.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medianexus.orchestrator.model.AdultOtherCollectionSyncGroup;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AdultOtherCollectionSyncGroupMapper extends BaseMapper<AdultOtherCollectionSyncGroup> {

    @Update("""
            CREATE TABLE IF NOT EXISTS adult_other_collection_sync_groups (
                id BIGINT NOT NULL AUTO_INCREMENT,
                run_id VARCHAR(36) NOT NULL,
                collection_name VARCHAR(512) NOT NULL,
                source_folder_path VARCHAR(1024) NOT NULL,
                item_count INT NOT NULL DEFAULT 0,
                eligible TINYINT(1) NOT NULL DEFAULT 0,
                action VARCHAR(32) NOT NULL,
                emby_collection_id VARCHAR(128) NULL,
                added_item_count INT NOT NULL DEFAULT 0,
                skip_reason VARCHAR(512) NULL,
                sample_item_names_json LONGTEXT NULL,
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                KEY idx_adult_other_collection_groups_run_id (run_id),
                KEY idx_adult_other_collection_groups_name (collection_name)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """)
    void createTableIfNotExists();

    @Select("""
            SELECT id,
                   run_id,
                   collection_name,
                   source_folder_path,
                   item_count,
                   eligible,
                   action,
                   emby_collection_id,
                   added_item_count,
                   skip_reason,
                   sample_item_names_json,
                   created_at
            FROM adult_other_collection_sync_groups
            WHERE run_id = #{runId}
            ORDER BY eligible DESC, item_count DESC, collection_name ASC
            """)
    List<AdultOtherCollectionSyncGroup> selectByRunId(@Param("runId") String runId);
}
