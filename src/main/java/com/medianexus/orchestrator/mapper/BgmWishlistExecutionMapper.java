package com.medianexus.orchestrator.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 预览凭证、每日唯一认领和运行明细共用执行存储，不修改来源状态表。 */
@Mapper
public interface BgmWishlistExecutionMapper {
    @Update("""
            CREATE TABLE IF NOT EXISTS bgm_wishlist_executions (
                id VARCHAR(96) NOT NULL PRIMARY KEY,
                kind VARCHAR(16) NOT NULL,
                source_id VARCHAR(36) NOT NULL,
                actor_id BIGINT NULL,
                consumed BOOLEAN NOT NULL DEFAULT FALSE,
                payload LONGTEXT NOT NULL,
                created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                KEY idx_bgm_execution_runs (kind, source_id, created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """)
    void createTableIfNotExists();

    @Insert("""
            INSERT INTO bgm_wishlist_executions (id, kind, source_id, actor_id, payload)
            VALUES (#{id}, #{kind}, #{sourceId}, #{actorId}, #{payload})
            """)
    void insert(@Param("id") String id, @Param("kind") String kind, @Param("sourceId") String sourceId,
                @Param("actorId") Long actorId, @Param("payload") String payload);

    @Select("SELECT payload FROM bgm_wishlist_executions WHERE id = #{id} AND kind = #{kind}")
    String select(@Param("id") String id, @Param("kind") String kind);

    @Update("""
            UPDATE bgm_wishlist_executions SET consumed = TRUE
            WHERE id = #{id} AND kind = 'PREVIEW' AND actor_id = #{actorId} AND consumed = FALSE
            """)
    int consumePreview(@Param("id") String id, @Param("actorId") Long actorId);

    @Update("UPDATE bgm_wishlist_executions SET payload = #{payload} WHERE id = #{id} AND kind = 'RUN'")
    void updateRun(@Param("id") String id, @Param("payload") String payload);

    @Select("""
            <script>
            SELECT payload FROM bgm_wishlist_executions WHERE kind = 'RUN'
            <if test="sourceId != null">AND source_id = #{sourceId}</if>
            ORDER BY created_at DESC, id DESC LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<String> listRuns(@Param("sourceId") String sourceId, @Param("offset") long offset, @Param("limit") int limit);

    @Select("""
            <script>
            SELECT COUNT(*) FROM bgm_wishlist_executions WHERE kind = 'RUN'
            <if test="sourceId != null">AND source_id = #{sourceId}</if>
            </script>
            """)
    long countRuns(@Param("sourceId") String sourceId);
}
