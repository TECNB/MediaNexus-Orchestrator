package com.medianexus.orchestrator.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medianexus.orchestrator.model.JavdbAutomationLedger;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface JavdbAutomationLedgerMapper extends BaseMapper<JavdbAutomationLedger> {

    @Update("""
            CREATE TABLE IF NOT EXISTS javdb_automation_ledger (
                id VARCHAR(36) NOT NULL,
                code VARCHAR(64) NOT NULL,
                selected_infohash VARCHAR(128) NOT NULL,
                selected_magnet LONGTEXT NOT NULL,
                adult_task_id VARCHAR(36) NOT NULL,
                run_id VARCHAR(36) NOT NULL,
                submitted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                UNIQUE KEY uk_javdb_automation_ledger_code (code),
                KEY idx_javdb_automation_ledger_task (adult_task_id),
                KEY idx_javdb_automation_ledger_run (run_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """)
    void createTableIfNotExists();
}
