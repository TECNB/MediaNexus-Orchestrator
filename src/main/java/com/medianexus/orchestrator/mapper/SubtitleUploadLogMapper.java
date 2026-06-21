package com.medianexus.orchestrator.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medianexus.orchestrator.model.SubtitleUploadLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SubtitleUploadLogMapper extends BaseMapper<SubtitleUploadLog> {

    @Update("""
            CREATE TABLE IF NOT EXISTS subtitle_upload_logs (
                id BIGINT NOT NULL AUTO_INCREMENT,
                upload_id VARCHAR(36) NOT NULL,
                level VARCHAR(16) NOT NULL,
                stage VARCHAR(64) NOT NULL,
                message VARCHAR(1024) NOT NULL,
                detail TEXT NULL,
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                KEY idx_subtitle_upload_logs_upload_id (upload_id, id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """)
    void createTableIfNotExists();
}
