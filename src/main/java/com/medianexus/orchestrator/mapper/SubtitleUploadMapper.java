package com.medianexus.orchestrator.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medianexus.orchestrator.model.SubtitleUpload;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SubtitleUploadMapper extends BaseMapper<SubtitleUpload> {

    @Update("""
            CREATE TABLE IF NOT EXISTS subtitle_uploads (
                id VARCHAR(36) NOT NULL,
                created_by_user_id BIGINT NULL,
                media_type VARCHAR(32) NOT NULL,
                status VARCHAR(32) NOT NULL,
                stage VARCHAR(64) NOT NULL,
                title VARCHAR(255) NOT NULL,
                original_title VARCHAR(255) NULL,
                year INT NULL,
                season_number INT NULL,
                target_path VARCHAR(1024) NOT NULL,
                selected_video_name VARCHAR(1024) NULL,
                source_file_name VARCHAR(1024) NOT NULL,
                source_size BIGINT NULL,
                source_sha256 VARCHAR(64) NULL,
                file_count INT NOT NULL DEFAULT 0,
                overwrite_enabled TINYINT(1) NOT NULL DEFAULT 0,
                file_manifest LONGTEXT NULL,
                error_message VARCHAR(1024) NULL,
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                finished_at DATETIME NULL,
                PRIMARY KEY (id),
                KEY idx_subtitle_uploads_owner_created_at (created_by_user_id, created_at),
                KEY idx_subtitle_uploads_status_created_at (status, created_at),
                KEY idx_subtitle_uploads_target_path (target_path(255))
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """)
    void createTableIfNotExists();

    @Select("""
            SELECT COUNT(*)
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'subtitle_uploads'
              AND COLUMN_NAME = 'season_number'
            """)
    Integer countSeasonNumberColumn();

    @Update("""
            ALTER TABLE subtitle_uploads
            ADD COLUMN season_number INT NULL AFTER year
            """)
    void addSeasonNumberColumn();

    @Select("""
            SELECT COUNT(*)
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'subtitle_uploads'
              AND COLUMN_NAME = 'year'
              AND IS_NULLABLE = 'NO'
            """)
    Integer countRequiredYearColumn();

    @Update("""
            ALTER TABLE subtitle_uploads
            MODIFY COLUMN year INT NULL
            """)
    void makeYearNullable();
}
