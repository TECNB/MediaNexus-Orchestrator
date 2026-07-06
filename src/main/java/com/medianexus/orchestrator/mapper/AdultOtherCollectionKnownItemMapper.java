package com.medianexus.orchestrator.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medianexus.orchestrator.model.AdultOtherCollectionKnownItem;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AdultOtherCollectionKnownItemMapper extends BaseMapper<AdultOtherCollectionKnownItem> {

    @Update("""
            CREATE TABLE IF NOT EXISTS adult_other_collection_known_items (
                emby_item_id VARCHAR(128) NOT NULL,
                source_folder_path VARCHAR(1024) NOT NULL,
                collection_name VARCHAR(512) NOT NULL,
                item_name VARCHAR(512) NULL,
                item_path VARCHAR(1024) NULL,
                date_created VARCHAR(64) NULL,
                first_seen_at DATETIME NOT NULL,
                last_seen_at DATETIME NOT NULL,
                synced_at DATETIME NULL,
                deleted_at DATETIME NULL,
                PRIMARY KEY (emby_item_id),
                KEY idx_adult_other_known_items_source_synced (source_folder_path(191), synced_at),
                KEY idx_adult_other_known_items_source_collection (source_folder_path(191), collection_name(191)),
                KEY idx_adult_other_known_items_last_seen (last_seen_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """)
    void createTableIfNotExists();

    @Select("""
            SELECT emby_item_id
            FROM adult_other_collection_known_items
            WHERE source_folder_path = #{sourceFolderPath}
              AND deleted_at IS NULL
            """)
    List<String> selectKnownItemIds(@Param("sourceFolderPath") String sourceFolderPath);

    @Select("""
            SELECT COUNT(*)
            FROM adult_other_collection_known_items
            WHERE source_folder_path = #{sourceFolderPath}
              AND deleted_at IS NULL
            """)
    int countLiveItems(@Param("sourceFolderPath") String sourceFolderPath);

    @Select("""
            SELECT COUNT(DISTINCT collection_name)
            FROM adult_other_collection_known_items
            WHERE source_folder_path = #{sourceFolderPath}
              AND deleted_at IS NULL
              AND collection_name <> '__UNGROUPED__'
            """)
    int countLiveGroups(@Param("sourceFolderPath") String sourceFolderPath);

    @Select("""
            SELECT emby_item_id AS embyItemId,
                   source_folder_path AS sourceFolderPath,
                   collection_name AS collectionName,
                   item_name AS itemName,
                   item_path AS itemPath,
                   date_created AS dateCreated,
                   first_seen_at AS firstSeenAt,
                   last_seen_at AS lastSeenAt,
                   synced_at AS syncedAt,
                   deleted_at AS deletedAt
            FROM adult_other_collection_known_items
            WHERE source_folder_path = #{sourceFolderPath}
              AND synced_at IS NULL
              AND deleted_at IS NULL
            ORDER BY first_seen_at ASC, emby_item_id ASC
            """)
    List<AdultOtherCollectionKnownItem> selectUnsyncedBySourceFolderPath(
            @Param("sourceFolderPath") String sourceFolderPath
    );

    @Insert("""
            <script>
            INSERT INTO adult_other_collection_known_items (
                emby_item_id,
                source_folder_path,
                collection_name,
                item_name,
                item_path,
                date_created,
                first_seen_at,
                last_seen_at,
                synced_at,
                deleted_at
            ) VALUES
            <foreach collection="items" item="item" separator=",">
                (
                    #{item.embyItemId},
                    #{item.sourceFolderPath},
                    #{item.collectionName},
                    #{item.itemName},
                    #{item.itemPath},
                    #{item.dateCreated},
                    #{now},
                    #{now},
                    NULL,
                    NULL
                )
            </foreach>
            ON DUPLICATE KEY UPDATE
                source_folder_path = VALUES(source_folder_path),
                collection_name = VALUES(collection_name),
                item_name = VALUES(item_name),
                item_path = VALUES(item_path),
                date_created = VALUES(date_created),
                last_seen_at = VALUES(last_seen_at),
                deleted_at = NULL
            </script>
            """)
    void upsertDiscoveredItems(
            @Param("items") List<AdultOtherCollectionKnownItem> items,
            @Param("now") LocalDateTime now
    );

    @Update("""
            <script>
            UPDATE adult_other_collection_known_items
            SET synced_at = #{now}
            WHERE source_folder_path = #{sourceFolderPath}
              AND emby_item_id IN
              <foreach collection="itemIds" item="itemId" open="(" separator="," close=")">
                #{itemId}
              </foreach>
            </script>
            """)
    void markSynced(
            @Param("sourceFolderPath") String sourceFolderPath,
            @Param("itemIds") List<String> itemIds,
            @Param("now") LocalDateTime now
    );
}
