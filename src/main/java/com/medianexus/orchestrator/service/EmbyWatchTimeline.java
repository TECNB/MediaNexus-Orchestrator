package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.model.EmbyActivePlaybackSession;
import java.time.*;
import java.util.*;

/** 采集与只读明细共享同一分段/取整规则；不按播放位置或浏览器时间外推。 */
final class EmbyWatchTimeline {
    record Span(LocalDateTime start, LocalDateTime end) {}
    private static final ObjectMapper JSON = new ObjectMapper();
    private EmbyWatchTimeline() {}

    static List<Span> closed(EmbyActivePlaybackSession row) {
        List<Span> spans = new ArrayList<>();
        if (row.getPlaybackIntervalsJson()==null) return spans;
        try {
            var nodes = JSON.readTree(row.getPlaybackIntervalsJson());
            if (!nodes.isArray()) throw new IllegalStateException("观看分段格式无效");
            for (var node : nodes) {
                var start = LocalDateTime.parse(node.path("start").asText());
                var end = LocalDateTime.parse(node.path("end").asText());
                if (end.isBefore(start)) throw new IllegalStateException("观看分段顺序无效");
                spans.add(new Span(start, end));
            }
            return spans;
        } catch (JsonProcessingException e) { throw new IllegalStateException("观看分段不可用",e); }
    }
    static LocalDateTime legacyCutoff(EmbyActivePlaybackSession row, List<Span> spans) {
        var cutoff=row.getStartTime();
        if (row.getPlaybackStartTime()!=null && row.getPlaybackStartTime().isAfter(cutoff)) cutoff=row.getPlaybackStartTime();
        if (row.getLastVerifiedAt()!=null && row.getLastVerifiedAt().isAfter(cutoff)) cutoff=row.getLastVerifiedAt();
        for (var span:spans) if (span.end().isAfter(cutoff)) cutoff=span.end();
        return cutoff;
    }
    static List<Span> evidenced(EmbyActivePlaybackSession row) {
        var spans = closed(row);
        if (row.getPlaybackStartTime()!=null && row.getLastObservedAt()!=null && row.getLastObservedAt().isAfter(row.getPlaybackStartTime())) {
            spans.add(new Span(row.getPlaybackStartTime(),row.getLastObservedAt()));
        }
        return spans;
    }
    static void close(EmbyActivePlaybackSession row, LocalDateTime at) {
        if (row.getPlaybackStartTime()==null) return;
        var spans = closed(row);
        if (at.isAfter(row.getPlaybackStartTime())) spans.add(new Span(row.getPlaybackStartTime(),at));
        var nodes = JSON.createArrayNode();
        for (var span : spans) nodes.addObject().put("start",span.start().toString()).put("end",span.end().toString());
        row.setPlaybackIntervalsJson(nodes.toString()); row.setPlaybackStartTime(null);
    }
    static long seconds(List<Span> spans) {
        Duration total = Duration.ZERO;
        for (var span : spans) total=total.plus(Duration.between(span.start(),span.end()));
        return total.getSeconds();
    }
    /** 小数余量按时间顺序携带；正时长当天即保留贡献行，允许分配到 0 个整数秒。 */
    static Map<LocalDate,Long> days(List<Span> spans) {
        Map<LocalDate,Long> days = new TreeMap<>();
        Duration elapsed = Duration.ZERO;
        for (var span : spans) {
            var cursor=span.start();
            while (cursor.isBefore(span.end())) {
                var midnight=cursor.toLocalDate().plusDays(1).atStartOfDay();
                var end=span.end().isBefore(midnight) ? span.end() : midnight;
                long before=elapsed.getSeconds();
                elapsed=elapsed.plus(Duration.between(cursor,end));
                days.merge(cursor.toLocalDate(),elapsed.getSeconds()-before,Long::sum);
                cursor=end;
            }
        }
        return days;
    }
}
