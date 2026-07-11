package com.medianexus.orchestrator.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medianexus.orchestrator.model.AdultOtherAutomationRunCollection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AdultOtherAutomationRunCollectionMapper extends BaseMapper<AdultOtherAutomationRunCollection> {

    @Update("""
            CREATE TABLE IF NOT EXISTS adult_other_automation_run_collections (
                id VARCHAR(36) NOT NULL,
                run_id VARCHAR(36) NOT NULL,
                emby_collection_id VARCHAR(64) NULL,
                collection_name VARCHAR(512) NOT NULL,
                action VARCHAR(32) NOT NULL,
                added_item_count INT NOT NULL DEFAULT 0,
                image_ready TINYINT(1) NOT NULL DEFAULT 0,
                status VARCHAR(32) NOT NULL,
                message VARCHAR(1024) NULL,
                PRIMARY KEY (id),
                UNIQUE KEY uk_adult_other_automation_run_collection (run_id, collection_name),
                KEY idx_adult_other_automation_collection_run (run_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """)
    void createTableIfNotExists();

    @Select("""
            <script>
            SELECT id, run_id, emby_collection_id, collection_name, action,
                   added_item_count, image_ready, status, message
            FROM adult_other_automation_run_collections
            WHERE run_id IN
            <foreach collection="runIds" item="runId" open="(" separator="," close=")">
                #{runId}
            </foreach>
            ORDER BY collection_name
            </script>
            """)
    List<AdultOtherAutomationRunCollection> selectByRunIds(@Param("runIds") List<String> runIds);
}
