package com.medianexus.orchestrator.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medianexus.orchestrator.model.AdultOtherCollectionFolderRunSummary;
import com.medianexus.orchestrator.model.AdultOtherCollectionSyncRun;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AdultOtherCollectionSyncRunMapper extends BaseMapper<AdultOtherCollectionSyncRun> {

    @Update("""
            CREATE TABLE IF NOT EXISTS adult_other_collection_sync_runs (
                id VARCHAR(36) NOT NULL,
                created_by_user_id BIGINT NULL,
                mode VARCHAR(16) NOT NULL,
                status VARCHAR(32) NOT NULL,
                min_item_count INT NOT NULL,
                source_folder_path VARCHAR(1024) NULL,
                total_item_count INT NOT NULL DEFAULT 0,
                grouped_item_count INT NOT NULL DEFAULT 0,
                skipped_item_count INT NOT NULL DEFAULT 0,
                group_count INT NOT NULL DEFAULT 0,
                eligible_group_count INT NOT NULL DEFAULT 0,
                created_collection_count INT NOT NULL DEFAULT 0,
                updated_collection_count INT NOT NULL DEFAULT 0,
                unchanged_collection_count INT NOT NULL DEFAULT 0,
                deleted_collection_count INT NOT NULL DEFAULT 0,
                review_collection_count INT NOT NULL DEFAULT 0,
                item_add_count INT NOT NULL DEFAULT 0,
                observed_item_count INT NULL,
                observed_group_count INT NULL,
                error_message VARCHAR(1024) NULL,
                started_at DATETIME NOT NULL,
                finished_at DATETIME NULL,
                PRIMARY KEY (id),
                KEY idx_adult_other_collection_runs_started_at (started_at),
                KEY idx_adult_other_collection_runs_status_started_at (status, started_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """)
    void createTableIfNotExists();

    @Select("""
            SELECT id,
                   created_by_user_id,
                   mode,
                   status,
                   min_item_count,
                   source_folder_path,
                   total_item_count,
                   grouped_item_count,
                   skipped_item_count,
                   group_count,
                   eligible_group_count,
                   created_collection_count,
                   updated_collection_count,
                   unchanged_collection_count,
                   deleted_collection_count,
                   review_collection_count,
                   item_add_count,
                   observed_item_count,
                   observed_group_count,
                   error_message,
                   started_at,
                   finished_at
            FROM adult_other_collection_sync_runs
            ORDER BY started_at DESC
            LIMIT 1
            """)
    AdultOtherCollectionSyncRun selectLatest();

    @Select("""
            SELECT id,
                   created_by_user_id,
                   mode,
                   status,
                   min_item_count,
                   source_folder_path,
                   total_item_count,
                   grouped_item_count,
                   skipped_item_count,
                   group_count,
                   eligible_group_count,
                   created_collection_count,
                   updated_collection_count,
                   unchanged_collection_count,
                   deleted_collection_count,
                   review_collection_count,
                   item_add_count,
                   observed_item_count,
                   observed_group_count,
                   error_message,
                   started_at,
                   finished_at
            FROM adult_other_collection_sync_runs
            WHERE source_folder_path = #{sourceFolderPath}
              AND mode = 'APPLY'
              AND status = 'SUCCEEDED'
            ORDER BY COALESCE(finished_at, started_at) DESC,
                     started_at DESC
            LIMIT 1
            """)
    AdultOtherCollectionSyncRun selectLatestSuccessfulApplyBySourceFolderPath(
            @Param("sourceFolderPath") String sourceFolderPath
    );

    @Select("""
            SELECT source_folder_path AS sourceFolderPath,
                   MAX(CASE
                       WHEN mode = 'DRY_RUN' AND status = 'SUCCEEDED'
                       THEN COALESCE(finished_at, started_at)
                   END) AS latestPreviewAt,
                   MAX(CASE
                       WHEN mode = 'APPLY' AND status = 'SUCCEEDED'
                       THEN COALESCE(finished_at, started_at)
                   END) AS latestSyncAt,
                   (
                       SELECT latest_state.total_item_count
                       FROM adult_other_collection_sync_runs latest_state
                       WHERE latest_state.source_folder_path = adult_other_collection_sync_runs.source_folder_path
                         AND latest_state.mode = 'APPLY'
                         AND latest_state.status = 'SUCCEEDED'
                       ORDER BY COALESCE(latest_state.finished_at, latest_state.started_at) DESC,
                                latest_state.started_at DESC
                       LIMIT 1
                   ) AS lastSyncedItemCount,
                   (
                       SELECT latest_state.group_count
                       FROM adult_other_collection_sync_runs latest_state
                       WHERE latest_state.source_folder_path = adult_other_collection_sync_runs.source_folder_path
                         AND latest_state.mode = 'APPLY'
                         AND latest_state.status = 'SUCCEEDED'
                       ORDER BY COALESCE(latest_state.finished_at, latest_state.started_at) DESC,
                                latest_state.started_at DESC
                       LIMIT 1
                   ) AS lastSyncedGroupCount,
                   MAX(CASE
                       WHEN mode IN ('CLEANUP_DRY_RUN', 'CLEANUP_APPLY')
                         AND status = 'SUCCEEDED'
                         AND group_count = 0
                         AND deleted_collection_count = 0
                         AND review_collection_count = 0
                       THEN COALESCE(finished_at, started_at)
                   END) AS latestEmptyCleanupAt,
                   (
                       SELECT latest_cleanup.observed_item_count
                       FROM adult_other_collection_sync_runs latest_cleanup
                       WHERE latest_cleanup.source_folder_path = adult_other_collection_sync_runs.source_folder_path
                         AND latest_cleanup.mode IN ('CLEANUP_DRY_RUN', 'CLEANUP_APPLY')
                         AND latest_cleanup.status = 'SUCCEEDED'
                         AND latest_cleanup.group_count = 0
                         AND latest_cleanup.deleted_collection_count = 0
                         AND latest_cleanup.review_collection_count = 0
                       ORDER BY COALESCE(latest_cleanup.finished_at, latest_cleanup.started_at) DESC,
                                latest_cleanup.started_at DESC
                       LIMIT 1
                   ) AS cleanupObservedItemCount,
                   (
                       SELECT latest_cleanup.observed_group_count
                       FROM adult_other_collection_sync_runs latest_cleanup
                       WHERE latest_cleanup.source_folder_path = adult_other_collection_sync_runs.source_folder_path
                         AND latest_cleanup.mode IN ('CLEANUP_DRY_RUN', 'CLEANUP_APPLY')
                         AND latest_cleanup.status = 'SUCCEEDED'
                         AND latest_cleanup.group_count = 0
                         AND latest_cleanup.deleted_collection_count = 0
                         AND latest_cleanup.review_collection_count = 0
                       ORDER BY COALESCE(latest_cleanup.finished_at, latest_cleanup.started_at) DESC,
                                latest_cleanup.started_at DESC
                       LIMIT 1
                   ) AS cleanupObservedGroupCount
            FROM adult_other_collection_sync_runs
            WHERE source_folder_path IS NOT NULL
            GROUP BY source_folder_path
            """)
    List<AdultOtherCollectionFolderRunSummary> selectFolderRunSummaries();

    @Select("""
            SELECT COUNT(*)
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'adult_other_collection_sync_runs'
              AND COLUMN_NAME = 'source_folder_path'
            """)
    Integer countSourceFolderPathColumn();

    @Update("""
            ALTER TABLE adult_other_collection_sync_runs
            ADD COLUMN source_folder_path VARCHAR(1024) NULL AFTER min_item_count
            """)
    void addSourceFolderPathColumn();

    @Select("""
            SELECT COUNT(*)
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'adult_other_collection_sync_runs'
              AND COLUMN_NAME = 'deleted_collection_count'
            """)
    Integer countDeletedCollectionCountColumn();

    @Update("""
            ALTER TABLE adult_other_collection_sync_runs
            ADD COLUMN deleted_collection_count INT NOT NULL DEFAULT 0 AFTER unchanged_collection_count
            """)
    void addDeletedCollectionCountColumn();

    @Select("""
            SELECT COUNT(*)
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'adult_other_collection_sync_runs'
              AND COLUMN_NAME = 'review_collection_count'
            """)
    Integer countReviewCollectionCountColumn();

    @Update("""
            ALTER TABLE adult_other_collection_sync_runs
            ADD COLUMN review_collection_count INT NOT NULL DEFAULT 0 AFTER deleted_collection_count
            """)
    void addReviewCollectionCountColumn();

    @Select("""
            SELECT COUNT(*)
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'adult_other_collection_sync_runs'
              AND COLUMN_NAME = 'observed_item_count'
            """)
    Integer countObservedItemCountColumn();

    @Update("""
            ALTER TABLE adult_other_collection_sync_runs
            ADD COLUMN observed_item_count INT NULL AFTER item_add_count
            """)
    void addObservedItemCountColumn();

    @Select("""
            SELECT COUNT(*)
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'adult_other_collection_sync_runs'
              AND COLUMN_NAME = 'observed_group_count'
            """)
    Integer countObservedGroupCountColumn();

    @Update("""
            ALTER TABLE adult_other_collection_sync_runs
            ADD COLUMN observed_group_count INT NULL AFTER observed_item_count
            """)
    void addObservedGroupCountColumn();
}
