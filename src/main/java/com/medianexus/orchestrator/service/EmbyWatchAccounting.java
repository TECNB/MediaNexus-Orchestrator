package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.integration.emby.*;
import com.medianexus.orchestrator.mapper.*;
import com.medianexus.orchestrator.model.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 整轮采集、DB 排他及观看状态原子归属；查询不推进计时。 */
@Component
public class EmbyWatchAccounting {
    private static final ZoneId WATCH_ZONE=ZoneId.of("Asia/Shanghai");
    private final EmbyClient client;
    private final EmbyItemMetadataLookup metadata;
    private final EmbyActivePlaybackSessionMapper active;
    private final EmbyWatchSessionMapper history;
    private final EmbyWatchCollectionMapper collection;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final String writerRunId=UUID.randomUUID().toString();
    private boolean baselineRequired=true;
    public record Observation(Instant observedAt, List<EmbyPlaybackSession> sessions) {}

    @Autowired
    public EmbyWatchAccounting(EmbyClient client, EmbyItemMetadataLookup metadata, EmbyActivePlaybackSessionMapper active,
            EmbyWatchSessionMapper history, EmbyWatchCollectionMapper collection, PlatformTransactionManager transactions) {
        this(client,metadata,active,history,collection,transactions,Clock.systemUTC());
    }
    public EmbyWatchAccounting(EmbyClient client, EmbyItemMetadataLookup metadata, EmbyActivePlaybackSessionMapper active,
            EmbyWatchSessionMapper history, EmbyWatchCollectionMapper collection, PlatformTransactionManager transactions, Clock clock) {
        this.client=client; this.metadata=metadata; this.active=active; this.history=history; this.collection=collection;
        this.transaction=new TransactionTemplate(transactions); this.clock=clock;
    }
    /** null 表示锁竞争或旧时间戳，无新成功快照；事务提交失败不能发布 fresh。 */
    public Observation collectSnapshot() {
        try {
            var accepted=transaction.execute(status -> collectLocked());
            if (accepted!=null) baselineRequired=false;
            return accepted;
        } catch (RuntimeException failure) {
            for (Throwable cause=failure; cause!=null; cause=cause.getCause()) {
                if (cause instanceof java.sql.SQLException sql && sql.getErrorCode()==3572) return null;
            }
            baselineRequired=true;
            throw failure;
        }
    }
    private Observation collectLocked() {
        var checkpoint=collection.lockCollection();
        // 锁覆盖上游读取及事务写入，不能将两个独立取得的快照逆序入账。
        var sessions=List.copyOf(client.listPlaybackSessions());
        Instant observed=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var at=LocalDateTime.ofInstant(observed,WATCH_ZONE);
        Set<String> connections=new HashSet<>();
        for (var s:sessions) if (!connections.add(s.id())) throw new EmbyClientException("Duplicate playback connection");
        if (checkpoint!=null && checkpoint.lastObservedAt()!=null && !at.isAfter(checkpoint.lastObservedAt())) return null;
        baselineRequired=baselineRequired || checkpoint==null || !writerRunId.equals(checkpoint.writerRunId());
        Map<String,EmbyActivePlaybackSession> prior=new HashMap<>();
        for (var row:collection.activeForUpdate()) {
            if (row.getPlaybackInstanceId()==null) { handoffLegacy(row); continue; }
            if (prior.put(row.getEmbySessionId(),row)!=null) throw new IllegalStateException("活动播放连接重复");
            if (baselineRequired) EmbyWatchTimeline.close(row,row.getLastObservedAt());
        }
        List<EmbyPlaybackSession> visible=new ArrayList<>();
        for (var session:sessions) {
            var row=prior.remove(session.id());
            if (row!=null && (!row.getEmbyUserId().equals(session.userId()) || !row.getItemId().equals(session.itemId()))) {
                settle(row); row=null;
            }
            if (row==null) row=start(session,at);
            else observe(row,session,at);
            row.setAccumulatedWatchSeconds(Math.toIntExact(EmbyWatchTimeline.seconds(EmbyWatchTimeline.evidenced(row))));
            if (row.getId()==null) active.insert(row); else collection.saveObserved(row);
            visible.add(visible(session,row.getWatchCategory()));
        }
        for (var row:prior.values()) settle(row);
        collection.checkpoint(writerRunId,at);
        return new Observation(observed,List.copyOf(visible));
    }
    private EmbyActivePlaybackSession start(EmbyPlaybackSession s, LocalDateTime at) {
        var row=new EmbyActivePlaybackSession();
        row.setPlaybackInstanceId(UUID.randomUUID().toString());
        row.setEmbySessionId(s.id()); row.setEmbyUserId(s.userId()); row.setEmbyUserName(s.userName());
        row.setItemId(s.itemId()); row.setItemType(s.type()); row.setItemName(s.title()); row.setSeriesName(s.seriesName());
        row.setSeasonNumber(s.seasonNumber()); row.setEpisodeNumber(s.episodeNumber());
        row.setRuntimeTicks(s.durationSeconds()==null ? null : Math.round(s.durationSeconds()*10_000_000));
        row.setStartPositionTicks(s.positionSeconds()==null ? null : Math.round(s.positionSeconds()*10_000_000));
        row.setDeviceName(s.deviceName()); row.setClientName(s.client()); row.setStartTime(at);
        row.setPlaybackIntervalsJson("[]"); row.setAccumulatedWatchSeconds(0); row.setObservationCount(0);
        row.setWatchCategory("unknown");
        // 仅新身份补充一次；未知/失败不会在每秒轮询反复请求媒体库。
        try {
            metadata.findItemMetadata(s.userId(),s.itemId()).ifPresent(m -> {
                row.setWatchCategory(m.watchCategory()==null ? "unknown" : m.watchCategory());
                row.setLibraryId(m.libraryId()); row.setLibraryName(m.libraryName()); row.setSeriesId(m.seriesId());
                if (m.itemName()!=null) row.setItemName(m.itemName());
                if (m.seriesName()!=null) row.setSeriesName(m.seriesName());
            });
        } catch (EmbyClientException ignored) { /* 已知播放保留，分类未知默认隐藏。 */ }
        observe(row,s,at);
        return row;
    }
    private void observe(EmbyActivePlaybackSession row, EmbyPlaybackSession s, LocalDateTime at) {
        String state=s.paused()==null ? "unknown" : s.paused() ? "paused" : "playing";
        if ("unknown".equals(state)) {
            if (row.getLastObservedAt()!=null) EmbyWatchTimeline.close(row,row.getLastObservedAt());
        } else if ("paused".equals(state)) EmbyWatchTimeline.close(row,at);
        else if (row.getPlaybackStartTime()==null) row.setPlaybackStartTime(at);
        row.setLastObservedAt(at); row.setObservedState(state); row.setObservationCount(row.getObservationCount()+1);
    }
    private void settle(EmbyActivePlaybackSession row) {
        EmbyWatchTimeline.close(row,row.getLastObservedAt());
        var spans=EmbyWatchTimeline.closed(row);
        var days=!baselineRequired && row.getObservationCount()==1 && "playing".equals(row.getObservedState())
                && EmbyWatchTimeline.seconds(spans)==0
                ? Map.of(row.getStartTime().toLocalDate(),1L) : EmbyWatchTimeline.days(spans);
        var settled=new EmbyWatchSession(); BeanUtils.copyProperties(row,settled);
        settled.setId(null); settled.setStopTime(row.getLastObservedAt()); settled.setStopTimeSource("inferred");
        settled.setAccountingBasis("calendar"); settled.setWatchDate(row.getStartTime().toLocalDate());
        settled.setWatchSeconds(Math.toIntExact(days.values().stream().mapToLong(Long::longValue).sum()));
        if (collection.settledInstance(row.getPlaybackInstanceId())==null) {
            history.insert(settled);
            days.forEach((day,seconds)->collection.insertDay(settled,day,seconds));
        }
        active.deleteById(row.getId());
    }
    /** 最小交接只保存旧累计与闭合段；开放段的时间跨度不授权补计。 */
    private void handoffLegacy(EmbyActivePlaybackSession row) {
        var spans=EmbyWatchTimeline.closed(row);
        long total=row.getAccumulatedWatchSeconds()==null ? 0 : row.getAccumulatedWatchSeconds();
        var cutoff=EmbyWatchTimeline.legacyCutoff(row,spans);
        var days=EmbyWatchTimeline.days(spans);
        if (row.getPlaybackIntervalsJson()!=null && EmbyWatchTimeline.seconds(spans)!=total) {
            throw new IllegalStateException("旧观看累计与闭合分段不一致，保留原活动记录");
        }
        var settled=new EmbyWatchSession(); BeanUtils.copyProperties(row,settled);
        settled.setId(null); settled.setStopTime(cutoff); settled.setStopTimeSource("migration");
        settled.setWatchSeconds(Math.toIntExact(total)); settled.setWatchDate(cutoff.toLocalDate());
        settled.setAccountingBasis(row.getPlaybackIntervalsJson()==null ? "legacy" : "calendar");
        history.insert(settled);
        if ("calendar".equals(settled.getAccountingBasis())) days.forEach((day,seconds)->collection.insertDay(settled,day,seconds));
        active.deleteById(row.getId());
    }
    private EmbyPlaybackSession visible(EmbyPlaybackSession s, String category) {
        boolean publicTitle="movie".equals(category) || "series".equals(category);
        return new EmbyPlaybackSession(s.id(),s.userId(),s.userName(),s.client(),s.deviceName(),s.paused(),s.itemId(),s.type(),
                publicTitle ? s.title() : "adult".equals(category) ? "私密观看" : "分类待确认", publicTitle ? s.seriesName() : null,
                s.seasonNumber(),s.episodeNumber(),s.episodeNumberEnd(),s.positionSeconds(),s.durationSeconds(),
                publicTitle ? s.posters() : List.of(), category);
    }
}
