package com.medianexus.orchestrator.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medianexus.orchestrator.model.MovieMagnetIngestTaskLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MovieMagnetIngestTaskLogMapper extends BaseMapper<MovieMagnetIngestTaskLog> {

    @Update("""
            CREATE TABLE IF NOT EXISTS movie_magnet_ingest_task_logs (
                id BIGINT NOT NULL AUTO_INCREMENT,
                task_id VARCHAR(36) NOT NULL,
                level VARCHAR(16) NOT NULL,
                stage VARCHAR(64) NOT NULL,
                message VARCHAR(1024) NOT NULL,
                detail TEXT NULL,
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                KEY idx_movie_magnet_task_logs_task_id (task_id, id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """)
    void createTableIfNotExists();
}
