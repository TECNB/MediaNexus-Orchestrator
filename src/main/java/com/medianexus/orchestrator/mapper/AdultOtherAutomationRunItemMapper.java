package com.medianexus.orchestrator.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medianexus.orchestrator.model.AdultOtherAutomationRunItem;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AdultOtherAutomationRunItemMapper extends BaseMapper<AdultOtherAutomationRunItem> {

    @Update("""
            CREATE TABLE IF NOT EXISTS adult_other_automation_run_items (
                id VARCHAR(36) NOT NULL,
                run_id VARCHAR(36) NOT NULL,
                emby_item_id VARCHAR(64) NOT NULL,
                item_name VARCHAR(512) NULL,
                item_path VARCHAR(2048) NULL,
                collection_name VARCHAR(512) NULL,
                primary_before TINYINT(1) NOT NULL DEFAULT 0,
                refresh_requested TINYINT(1) NOT NULL DEFAULT 0,
                primary_after TINYINT(1) NOT NULL DEFAULT 0,
                status VARCHAR(32) NOT NULL,
                message VARCHAR(1024) NULL,
                PRIMARY KEY (id),
                UNIQUE KEY uk_adult_other_automation_run_item (run_id, emby_item_id),
                KEY idx_adult_other_automation_item_run (run_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """)
    void createTableIfNotExists();

    @Insert("""
            INSERT INTO adult_other_automation_run_items (
                id, run_id, emby_item_id, item_name, item_path, collection_name,
                primary_before, refresh_requested, primary_after, status, message
            ) VALUES (
                #{id}, #{runId}, #{embyItemId}, #{itemName}, #{itemPath}, #{collectionName},
                #{primaryBefore}, #{refreshRequested}, #{primaryAfter}, #{status}, #{message}
            )
            ON DUPLICATE KEY UPDATE
                item_name = VALUES(item_name),
                item_path = VALUES(item_path),
                collection_name = VALUES(collection_name),
                primary_before = VALUES(primary_before),
                refresh_requested = VALUES(refresh_requested),
                primary_after = VALUES(primary_after),
                status = VALUES(status),
                message = VALUES(message)
            """)
    void upsert(AdultOtherAutomationRunItem item);

    @Update("""
            UPDATE adult_other_automation_run_items
            SET refresh_requested = #{refreshRequested},
                primary_after = #{primaryAfter},
                status = #{status},
                message = #{message}
            WHERE run_id = #{runId} AND emby_item_id = #{itemId}
            """)
    void updateResult(
            @Param("runId") String runId,
            @Param("itemId") String itemId,
            @Param("refreshRequested") boolean refreshRequested,
            @Param("primaryAfter") boolean primaryAfter,
            @Param("status") String status,
            @Param("message") String message
    );

    @Select("""
            <script>
            SELECT id, run_id, emby_item_id, item_name, item_path, collection_name,
                   primary_before, refresh_requested, primary_after, status, message
            FROM adult_other_automation_run_items
            WHERE run_id IN
            <foreach collection="runIds" item="runId" open="(" separator="," close=")">
                #{runId}
            </foreach>
            ORDER BY item_name, emby_item_id
            </script>
            """)
    List<AdultOtherAutomationRunItem> selectByRunIds(@Param("runIds") List<String> runIds);
}
