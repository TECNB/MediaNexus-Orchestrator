package com.medianexus.orchestrator.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medianexus.orchestrator.model.QuarkIngestTaskFile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface QuarkIngestTaskFileMapper extends BaseMapper<QuarkIngestTaskFile> {

    @Update("""
            CREATE TABLE IF NOT EXISTS quark_ingest_task_files (
                id BIGINT NOT NULL AUTO_INCREMENT,
                child_task_id VARCHAR(36) NOT NULL,
                source_fid VARCHAR(128) NULL,
                source_name VARCHAR(1024) NOT NULL,
                target_name VARCHAR(1024) NULL,
                status VARCHAR(32) NOT NULL,
                failure_reason VARCHAR(1024) NULL,
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                KEY idx_quark_task_files_child (child_task_id, id),
                KEY idx_quark_task_files_status (status)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """)
    void createTableIfNotExists();
}
