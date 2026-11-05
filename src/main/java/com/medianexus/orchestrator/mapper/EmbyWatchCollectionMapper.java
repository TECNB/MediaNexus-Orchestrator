package com.medianexus.orchestrator.mapper;

import com.medianexus.orchestrator.model.EmbyActivePlaybackSession;
import com.medianexus.orchestrator.model.EmbyWatchSession;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface EmbyWatchCollectionMapper {
    record Checkpoint(String writerRunId, LocalDateTime lastObservedAt) {}
    @Select("SELECT writer_run_id, last_observed_at FROM emby_watch_collection_checkpoint WHERE id=1 FOR UPDATE NOWAIT")
    Checkpoint lockCollection();
    @Update("UPDATE emby_watch_collection_checkpoint SET writer_run_id=#{writer}, last_observed_at=#{at} WHERE id=1")
    void checkpoint(@Param("writer") String writer, @Param("at") LocalDateTime at);
    @Select("SELECT * FROM emby_active_playback_sessions ORDER BY id FOR UPDATE")
    List<EmbyActivePlaybackSession> activeForUpdate();
    @Update("""
            UPDATE emby_active_playback_sessions SET playback_start_time=#{r.playbackStartTime},
                playback_intervals_json=#{r.playbackIntervalsJson}, accumulated_watch_seconds=#{r.accumulatedWatchSeconds},
                last_observed_at=#{r.lastObservedAt}, observed_state=#{r.observedState}, observation_count=#{r.observationCount}
            WHERE id=#{r.id} AND playback_instance_id=#{r.playbackInstanceId}
            """)
    void saveObserved(@Param("r") EmbyActivePlaybackSession row);
    @Select("SELECT * FROM emby_watch_sessions WHERE playback_instance_id=#{instance} FOR UPDATE")
    EmbyWatchSession settledInstance(String instance);
    @Insert("""
            INSERT INTO emby_watch_daily_contributions(playback_instance_id,emby_session_id,item_id,start_time,watch_date,watch_seconds)
            VALUES(#{s.playbackInstanceId},#{s.embySessionId},#{s.itemId},#{s.startTime},#{date},#{seconds})
            """)
    void insertDay(@Param("s") EmbyWatchSession session, @Param("date") LocalDate date, @Param("seconds") long seconds);
}
