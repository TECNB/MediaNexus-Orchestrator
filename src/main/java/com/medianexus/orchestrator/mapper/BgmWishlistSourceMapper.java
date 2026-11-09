package com.medianexus.orchestrator.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 按来源保存 Jackson 快照；LONGTEXT 避免完整分页与身份记录超过配置表 TEXT 限制。 */
@Mapper
public interface BgmWishlistSourceMapper {
    @Update("""
            CREATE TABLE IF NOT EXISTS bgm_wishlist_sources (
                id VARCHAR(36) NOT NULL PRIMARY KEY,
                state_json LONGTEXT NOT NULL,
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """)
    void createTableIfNotExists();

    @Select("SELECT state_json FROM bgm_wishlist_sources ORDER BY created_at, id")
    List<String> listStates();

    @Select("""
            SELECT state_json FROM bgm_wishlist_sources
            WHERE JSON_EXTRACT(state_json, '$.source.ownerUserId') = #{ownerId}
            ORDER BY created_at, id
            """)
    List<String> listOwnedStates(@Param("ownerId") long ownerId);

    @Select("""
            SELECT DISTINCT COALESCE(JSON_UNQUOTE(JSON_EXTRACT(state_json, '$.source.provider')), 'BGM')
            FROM bgm_wishlist_sources WHERE JSON_EXTRACT(state_json, '$.source.ownerUserId') = #{ownerId}
            """)
    List<String> listOwnedProviders(@Param("ownerId") long ownerId);

    @Select("SELECT state_json FROM bgm_wishlist_sources WHERE id = #{id}")
    String selectState(@Param("id") String id);

    @Select("SELECT state_json FROM bgm_wishlist_sources WHERE id = #{id} FOR UPDATE")
    String selectStateForUpdate(@Param("id") String id);

    @Insert("INSERT INTO bgm_wishlist_sources (id, state_json) VALUES (#{id}, #{json})")
    void insertState(@Param("id") String id, @Param("json") String json);

    @Update("UPDATE bgm_wishlist_sources SET state_json = #{json} WHERE id = #{id}")
    void updateState(@Param("id") String id, @Param("json") String json);
}
