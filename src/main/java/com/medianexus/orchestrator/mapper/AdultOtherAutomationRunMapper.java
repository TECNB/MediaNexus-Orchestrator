package com.medianexus.orchestrator.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medianexus.orchestrator.model.AdultOtherAutomationRun;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AdultOtherAutomationRunMapper extends BaseMapper<AdultOtherAutomationRun> {

    @Update("""
            CREATE TABLE IF NOT EXISTS adult_other_automation_runs (
                id VARCHAR(36) NOT NULL,
                trigger_type VARCHAR(16) NOT NULL,
                status VARCHAR(16) NOT NULL,
                stage VARCHAR(32) NOT NULL,
                event_count INT NOT NULL DEFAULT 0,
                target_item_count INT NOT NULL DEFAULT 0,
                natural_primary_ready_count INT NOT NULL DEFAULT 0,
                targeted_refresh_count INT NOT NULL DEFAULT 0,
                final_primary_ready_count INT NOT NULL DEFAULT 0,
                final_primary_missing_count INT NOT NULL DEFAULT 0,
                affected_collection_count INT NOT NULL DEFAULT 0,
                created_collection_count INT NOT NULL DEFAULT 0,
                updated_collection_count INT NOT NULL DEFAULT 0,
                collection_image_ready_count INT NOT NULL DEFAULT 0,
                deleted_collection_count INT NOT NULL DEFAULT 0,
                message VARCHAR(1024) NULL,
                started_at DATETIME NOT NULL,
                finished_at DATETIME NULL,
                PRIMARY KEY (id),
                KEY idx_adult_other_automation_started_at (started_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """)
    void createTableIfNotExists();

    @Select("""
            SELECT id, trigger_type, status, stage, event_count, target_item_count,
                   natural_primary_ready_count, targeted_refresh_count,
                   final_primary_ready_count, final_primary_missing_count,
                   affected_collection_count, created_collection_count,
                   updated_collection_count, collection_image_ready_count,
                   deleted_collection_count, message, started_at, finished_at
            FROM adult_other_automation_runs
            ORDER BY started_at DESC
            LIMIT #{limit}
            """)
    List<AdultOtherAutomationRun> selectRecent(@Param("limit") int limit);
}
