package com.medianexus.orchestrator.mapper;

import java.util.List;
import org.apache.ibatis.annotations.*;

/** 公共榜单独立存储；主键和 InnoDB 行锁负责跨实例认领与短事务更新。 */
@Mapper
public interface DoubanChartMapper {
    @Update("""
        CREATE TABLE IF NOT EXISTS douban_chart_records (
            id VARCHAR(120) NOT NULL PRIMARY KEY,
            kind VARCHAR(20) NOT NULL,
            chart_id VARCHAR(32) NULL,
            payload LONGTEXT NOT NULL,
            created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
            KEY idx_douban_chart_records (kind, chart_id, created_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
        """)
    void createTableIfNotExists();

    @Insert("INSERT IGNORE INTO douban_chart_records(id,kind,chart_id,payload) VALUES(#{id},#{kind},#{chart},#{payload})")
    int insert(@Param("id") String id, @Param("kind") String kind, @Param("chart") String chart, @Param("payload") String payload);

    @Select("SELECT payload FROM douban_chart_records WHERE id=#{id}")
    String select(String id);

    @Select("SELECT payload FROM douban_chart_records WHERE id=#{id} FOR UPDATE")
    String lock(String id);

    @Update("UPDATE douban_chart_records SET payload=#{payload} WHERE id=#{id}")
    void update(@Param("id") String id, @Param("payload") String payload);
    @Delete("DELETE FROM douban_chart_records WHERE id=#{id}")
    void delete(String id);


    @Select("""
        SELECT payload FROM douban_chart_records WHERE kind='OPERATION' AND chart_id=#{chart}
        ORDER BY created_at DESC,id DESC LIMIT #{size} OFFSET #{offset}
        """)
    List<String> operations(@Param("chart") String chart, @Param("offset") long offset, @Param("size") int size);

    @Select("SELECT COUNT(*) FROM douban_chart_records WHERE kind='OPERATION' AND chart_id=#{chart}")
    long operationCount(String chart);

    @Select("""
        SELECT COUNT(*) FROM douban_chart_records WHERE kind='OPERATION' AND chart_id=#{chart}
        AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.operation.triggerType'))='SCHEDULED'
        AND SUBSTRING(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.operation.startedAt')),1,19)>=#{start}
        AND SUBSTRING(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.operation.startedAt')),1,19)<#{end}
        """)
    long scheduledOperationCount(@Param("chart") String chart, @Param("start") String start, @Param("end") String end);

    @Select("""
        SELECT payload FROM douban_chart_records WHERE kind='OPERATION'
        AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.operation.status')) IN ('QUEUED','RUNNING','STOPPING')
        ORDER BY created_at,id
        """)
    List<String> openOperations();
}
