package com.medianexus.orchestrator.mapper;

import com.medianexus.orchestrator.model.JavdbPlaylistHistoryItem;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface JavdbPlaylistHistoryMapper {

    @Select("""
            SELECT i.code, i.status, i.adult_task_id AS adultTaskId,
                   i.selected_magnet AS selectedMagnet, i.candidates_json AS candidatesJson,
                   i.appearances_json AS appearancesJson, i.run_id AS runId,
                   r.config_snapshot AS configSnapshot
            FROM javdb_automation_run_items i
            JOIN javdb_automation_runs r ON r.id = i.run_id
            WHERE r.execution_mode = 'EXECUTE'
              AND i.status IN ('SUBMITTED', 'ALREADY_IN_EMBY', 'HISTORY_SUBMITTED', 'ADULT_IN_PROGRESS')
            ORDER BY i.created_at, i.id
            """)
    List<JavdbPlaylistHistoryItem> selectExecutedItems();

    @Select("""
            SELECT code, appearances_json AS appearancesJson
            FROM javdb_automation_run_items
            WHERE appearances_json IS NOT NULL
              AND appearances_json <> ''
            ORDER BY created_at, id
            """)
    List<JavdbPlaylistHistoryItem> selectRatedItems();
}
