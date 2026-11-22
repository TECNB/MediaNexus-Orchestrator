package com.medianexus.orchestrator.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** Durable source snapshots and exact-file completion claims; no media-library scan is persisted here. */
@Mapper
public interface JavMetadataQueueMapper {
    record SourceRow(String id, String taskId, String magnetHash, String code, String detailUrl,
                     boolean chineseSubtitles, boolean cracked, String snapshotJson,
                     byte[] primaryImage, byte[] thumbImage, byte[] backdropImage,
                     String state, int attempts, LocalDateTime createdAt) { }
    record TargetRow(String id, String sourceId, String strmPath, String state, int attempts,
                     String embyItemId, LocalDateTime deadline) { }

    @Update("""
        CREATE TABLE IF NOT EXISTS jav_metadata_sources (
          id VARCHAR(64) PRIMARY KEY, task_id VARCHAR(36) NOT NULL, magnet_hash VARCHAR(128) NOT NULL,
          code VARCHAR(64) NOT NULL, detail_url VARCHAR(1024), chinese_subtitles BOOLEAN NOT NULL,
          cracked BOOLEAN NOT NULL, snapshot_json LONGTEXT, primary_image MEDIUMBLOB,
          thumb_image MEDIUMBLOB, backdrop_image MEDIUMBLOB, state VARCHAR(32) NOT NULL,
          attempts INT NOT NULL DEFAULT 0, error_message VARCHAR(256),
          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, next_attempt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
          KEY idx_jav_metadata_source_due (state,next_attempt)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """)
    void createSources();

    @Update("""
        CREATE TABLE IF NOT EXISTS jav_metadata_targets (
          id VARCHAR(64) PRIMARY KEY, source_id VARCHAR(64) NOT NULL, strm_path TEXT NOT NULL,
          state VARCHAR(32) NOT NULL, attempts INT NOT NULL DEFAULT 0, emby_item_id VARCHAR(64),
          error_message VARCHAR(256), deadline DATETIME NOT NULL, next_attempt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
          verified_at DATETIME, KEY idx_jav_metadata_target_due (state,next_attempt)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """)
    void createTargets();

    @Insert("""
        INSERT IGNORE INTO jav_metadata_sources
          (id,task_id,magnet_hash,code,detail_url,chinese_subtitles,cracked,state)
        VALUES (#{id},#{taskId},#{hash},#{code},#{detailUrl},#{subtitles},#{cracked},'NEW')
        """)
    int insertSource(String id, String taskId, String hash, String code, String detailUrl, boolean subtitles, boolean cracked);
    @Select("SELECT id,task_id,magnet_hash,code,detail_url,chinese_subtitles,cracked,snapshot_json,primary_image,thumb_image,backdrop_image,state,attempts,created_at FROM jav_metadata_sources WHERE id=#{id}")
    SourceRow source(String id);
    @Select("SELECT id,task_id,magnet_hash,code,detail_url,chinese_subtitles,cracked,snapshot_json,primary_image,thumb_image,backdrop_image,state,attempts,created_at FROM jav_metadata_sources WHERE state='NEW' AND next_attempt<=NOW() ORDER BY next_attempt,id LIMIT 2")
    List<SourceRow> dueSources();

    @Update("""
        UPDATE jav_metadata_sources SET snapshot_json=#{json},primary_image=#{primary},thumb_image=#{thumb},
          backdrop_image=#{backdrop},state='READY',error_message=NULL WHERE id=#{id}
        """)
    void ready(String id, String json, byte[] primary, byte[] thumb, byte[] backdrop);

    @Update("""
        UPDATE jav_metadata_sources SET snapshot_json=#{json},primary_image=#{primary},thumb_image=#{thumb},
          backdrop_image=#{backdrop} WHERE id=#{id}
        """)
    void saveProgress(String id, String json, byte[] primary, byte[] thumb, byte[] backdrop);

    @Update("""
        UPDATE jav_metadata_sources SET attempts=attempts+1,state=#{state},error_message=#{error},
          next_attempt=#{next} WHERE id=#{id}
        """)
    void sourceFailed(String id, String state, String error, LocalDateTime next);

    @Select("""
        SELECT detail_url FROM javdb_automation_run_items WHERE run_id=#{runId}
          AND LOWER(selected_infohash)=LOWER(#{hash}) LIMIT 1
        """)
    String automationDetail(String runId, String hash, String code);

    @Insert("""
        INSERT IGNORE INTO jav_metadata_targets (id,source_id,strm_path,state,deadline)
        VALUES (#{id},#{sourceId},#{path},'WAITING_EMBY',#{deadline})
        """)
    int insertTarget(String id, String sourceId, String path, LocalDateTime deadline);

    @Select("""
        SELECT id,source_id,strm_path,state,attempts,emby_item_id,deadline FROM jav_metadata_targets WHERE state IN ('WAITING_EMBY','METADATA_WRITTEN','VERIFYING')
          AND next_attempt<=NOW() ORDER BY next_attempt,id LIMIT 8
        """)
    List<TargetRow> dueTargets();

    @Update("""
        UPDATE jav_metadata_targets SET state=#{state},attempts=#{attempts},emby_item_id=#{itemId},
          error_message=#{error},next_attempt=#{next},verified_at=#{verifiedAt} WHERE id=#{id}
        """)
    void targetState(String id, String state, int attempts, String itemId, String error,
                     LocalDateTime next, LocalDateTime verifiedAt);
}
