package com.medianexus.orchestrator.mapper;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface OrganizationJournalMapper {
    @Update("""
        CREATE TABLE IF NOT EXISTS library_organization_journal (
          task_type VARCHAR(16) NOT NULL, task_id VARCHAR(64) NOT NULL,
          payload LONGTEXT NOT NULL, phase VARCHAR(16) NOT NULL, status VARCHAR(16) NOT NULL,
          version BIGINT NOT NULL DEFAULT 0, owner_token VARCHAR(80) NOT NULL,
          next_check BIGINT NOT NULL DEFAULT 0, attempt INT NOT NULL DEFAULT 0,
          last_error VARCHAR(256), PRIMARY KEY(task_type, task_id), INDEX recovery_due(status,next_check)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """)
    void createTable();

    record Row(String taskType, String taskId, String payload, String phase, String status,
               long version, String ownerToken, long nextCheck, int attempt, String lastError) {}

    @Insert("""
        INSERT INTO library_organization_journal(task_type,task_id,payload,phase,status,owner_token)
        VALUES(#{type},#{id},#{payload},'FILES','RUNNING',#{owner})
        """)
    int insert(@Param("type") String type, @Param("id") String id, @Param("payload") String payload, @Param("owner") String owner);

    @Select("""
        SELECT task_type AS taskType,task_id AS taskId,payload,phase,status,version,owner_token AS ownerToken,
               next_check AS nextCheck,attempt,last_error AS lastError
        FROM library_organization_journal WHERE task_type=#{type} AND task_id=#{id}
        """)
    Row get(@Param("type") String type, @Param("id") String id);

    @Select("""
        SELECT task_type AS taskType,task_id AS taskId,payload,phase,status,version,owner_token AS ownerToken,
               next_check AS nextCheck,attempt,last_error AS lastError
        FROM library_organization_journal WHERE status NOT IN ('COMPLETE','ISOLATED') AND next_check<=#{now}
        AND (task_type>#{afterType} OR (task_type=#{afterType} AND task_id>#{afterId}))
        ORDER BY task_type,task_id LIMIT 8
        """)
    List<Row> due(@Param("now") long now, @Param("afterType") String afterType, @Param("afterId") String afterId);

    @Update("""
        UPDATE library_organization_journal SET owner_token=#{owner},version=version+1
        WHERE task_type=#{row.taskType} AND task_id=#{row.taskId} AND version=#{row.version} AND owner_token=#{row.ownerToken}
        """)
    int claim(@Param("row") Row row, @Param("owner") String owner);

    @Update("""
        UPDATE library_organization_journal SET owner_token=CONCAT('RETIRED:',#{owner}),version=version+1
        WHERE owner_token=#{owner} AND status <> 'COMPLETE'
        """)
    int retireOwner(@Param("owner") String owner);

    @Select("""
        SELECT task_type AS taskType,task_id AS taskId,payload,phase,status,version,owner_token AS ownerToken,
               next_check AS nextCheck,attempt,last_error AS lastError
        FROM library_organization_journal WHERE status <> 'COMPLETE'
        """)
    List<Row> unfinished();

    @Update("""
        UPDATE library_organization_journal SET payload=#{payload},phase=#{phase},status=#{status},version=version+1,
               next_check=#{next},attempt=#{attempt},last_error=#{error}
        WHERE task_type=#{row.taskType} AND task_id=#{row.taskId} AND version=#{row.version} AND owner_token=#{row.ownerToken}
        """)
    int checkpoint(@Param("row") Row row, @Param("payload") String payload, @Param("phase") String phase,
                   @Param("status") String status, @Param("next") long next, @Param("attempt") int attempt,
                   @Param("error") String error);
}
