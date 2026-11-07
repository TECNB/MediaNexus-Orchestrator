package com.medianexus.orchestrator.mapper;

import org.apache.ibatis.annotations.*;

/** 通知受理和结果独立于业务状态，主键防止同一事件重复发送。 */
@Mapper
public interface IngestExceptionNoticeMapper {
    @Update("""
        CREATE TABLE IF NOT EXISTS ingest_exception_notices (
            event_key VARCHAR(160) PRIMARY KEY, status VARCHAR(16) NOT NULL,
            updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """)
    void createTable();
    @Insert("INSERT IGNORE INTO ingest_exception_notices(event_key,status) VALUES(#{key},'PENDING')")
    int claim(String key);
    @Update("UPDATE ingest_exception_notices SET status=#{status} WHERE event_key=#{key}")
    void update(@Param("key") String key, @Param("status") String status);
    @Select("SELECT status FROM ingest_exception_notices WHERE event_key=#{key}")
    String status(String key);
}
