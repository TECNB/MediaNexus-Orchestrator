package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.dto.emby.response.EmbyWatchDetails;
import com.medianexus.orchestrator.integration.emby.EmbyClient;
import com.medianexus.orchestrator.integration.emby.EmbyClientException;
import com.medianexus.orchestrator.integration.emby.EmbyPlaybackSession;
import com.medianexus.orchestrator.mapper.EmbyWatchDetailsMapper;
import com.medianexus.orchestrator.model.EmbyActivePlaybackSession;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 按同一结算范围读取完整观看动向；实时核验仅影响单独的估算记录。 */
@Service
@Transactional(readOnly = true)
public class EmbyWatchDetailsQuery {
    private static final ZoneId WATCH_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Comparator<EmbyWatchDetails.Session> RECENT_FIRST = Comparator
            .comparing(EmbyWatchDetails.Session::startTime).reversed()
            .thenComparing(EmbyWatchDetails.Session::id, Comparator.reverseOrder());
    private final AuthService authService;
    private final EmbyWatchDetailsMapper mapper;
    private final EmbyClient embyClient;
    private final ObjectMapper objectMapper;

    public EmbyWatchDetailsQuery(AuthService authService, EmbyWatchDetailsMapper mapper,
            EmbyClient embyClient, ObjectMapper objectMapper) {
        this.authService = authService;
        this.mapper = mapper;
        this.embyClient = embyClient;
        this.objectMapper = objectMapper;
    }

    public EmbyWatchDetails.Page<EmbyWatchDetails.User> users(String period, String date, String month, int page, int pageSize) {
        authService.requireAdminUser();
        WatchRange range = range(period, date, month);
        int offset = offset(page, pageSize);
        return new EmbyWatchDetails.Page<>(mapper.selectUsers(range.start(), range.end(), offset, pageSize),
                page, pageSize, mapper.countUsers(range.start(), range.end()));
    }

    public EmbyWatchDetails.Page<EmbyWatchDetails.Work> userWorks(String userId, String period, String date, String month, int page, int pageSize) {
        authService.requireAdminUser();
        WatchRange range = range(period, date, month);
        int offset = offset(page, pageSize);
        return new EmbyWatchDetails.Page<>(mapper.selectWorks(range.start(), range.end(), userId, null, "duration", offset, pageSize),
                page, pageSize, mapper.countWorks(range.start(), range.end(), userId, null));
    }

    public EmbyWatchDetails.Page<EmbyWatchDetails.Work> media(String category, String sort, String period, String date, String month, int page, int pageSize) {
        authService.requireAdminUser();
        requireCategory(category);
        if (!Set.of("duration", "viewers", "plays").contains(sort)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "作品排序无效");
        }
        WatchRange range = range(period, date, month);
        int offset = offset(page, pageSize);
        return new EmbyWatchDetails.Page<>(mapper.selectWorks(range.start(), range.end(), null, category, sort, offset, pageSize),
                page, pageSize, mapper.countWorks(range.start(), range.end(), null, category));
    }

    public EmbyWatchDetails.Page<EmbyWatchDetails.Viewer> viewers(String mediaId, String category, String period, String date, String month, int page, int pageSize) {
        authService.requireAdminUser();
        requireCategory(category);
        WatchRange range = range(period, date, month);
        int offset = offset(page, pageSize);
        return new EmbyWatchDetails.Page<>(mapper.selectViewers(range.start(), range.end(), mediaId, category, offset, pageSize),
                page, pageSize, mapper.countViewers(range.start(), range.end(), mediaId, category));
    }

    @Transactional
    public EmbyWatchDetails.Page<EmbyWatchDetails.Session> sessions(String userId, String mediaId, String category, String period, String date, String month, int page, int pageSize) {
        authService.requireAdminUser();
        if (category != null) requireCategory(category);
        WatchRange range = range(period, date, month);
        int offset = offset(page, pageSize);
        List<EmbyWatchDetails.Session> tracked = trackedSessions(range, userId).stream()
                .filter(session -> mediaId == null || mediaId.equals(session.mediaId()))
                .filter(session -> category == null || category.equals(session.category())).toList();
        long settledCount = mapper.countSessions(range.start(), range.end(), userId, mediaId, category);
        int settledOffset = Math.max(0, offset - tracked.size());
        List<EmbyWatchDetails.Session> combined = new ArrayList<>(tracked);
        combined.addAll(mapper.selectSessions(range.start(), range.end(), userId, mediaId, category, settledOffset, pageSize + tracked.size()));
        List<EmbyWatchDetails.Session> items = combined.stream().sorted(RECENT_FIRST)
                .skip(offset - settledOffset).limit(pageSize).toList();
        return new EmbyWatchDetails.Page<>(items, page, pageSize, settledCount + tracked.size());
    }

    @Transactional
    public EmbyWatchDetails.Page<EmbyWatchDetails.Session> activity(String period, String date, String month, int page, int pageSize) {
        authService.requireAdminUser();
        WatchRange range = range(period, date, month);
        int offset = offset(page, pageSize);
        List<EmbyWatchDetails.Session> tracked = trackedSessions(range, null);
        return new EmbyWatchDetails.Page<>(tracked.stream().sorted(RECENT_FIRST).skip(offset).limit(pageSize).toList(),
                page, pageSize, tracked.size());
    }

    private List<EmbyWatchDetails.Session> trackedSessions(WatchRange range, String userId) {
        List<EmbyActivePlaybackSession> tracked = mapper.selectTrackedSessions(range.end().atStartOfDay(), userId);
        if (tracked.isEmpty()) return List.of();
        List<EmbyPlaybackSession> live;
        try {
            live = embyClient.listPlaybackSessions();
        } catch (EmbyClientException exception) {
            live = List.of();
        }
        LocalDateTime now = LocalDateTime.now(WATCH_ZONE).withNano(0);
        List<EmbyWatchDetails.Session> sessions = new ArrayList<>();
        for (EmbyActivePlaybackSession active : tracked) {
            EmbyPlaybackSession current = live.stream().filter(session ->
                    active.getEmbySessionId().equals(session.id())
                    && active.getItemId().equals(session.itemId())
                    && active.getEmbyUserId().equals(session.userId())).findFirst().orElse(null);
            LocalDateTime lastObserved = active.getUpdatedAt() != null ? active.getUpdatedAt() : active.getStartTime();
            if (active.getLastVerifiedAt() != null && active.getLastVerifiedAt().isAfter(lastObserved)) {
                lastObserved = active.getLastVerifiedAt();
            }
            boolean verifiedPlaying = current != null && Boolean.FALSE.equals(current.paused());
            LocalDateTime observed = verifiedPlaying && now.isAfter(lastObserved) ? now : lastObserved;
            if (verifiedPlaying) mapper.recordVerifiedPlayback(active.getId(), observed);
            long[] seconds = trackedSeconds(active, range, observed);
            if (seconds[1] <= 0 && !(active.getStartTime().toLocalDate().compareTo(range.start()) >= 0
                    && active.getStartTime().toLocalDate().isBefore(range.end()))) continue;
            String category = active.getWatchCategory() == null ? "unknown" : active.getWatchCategory();
            String state = current == null || current.paused() == null ? "unverified" : current.paused() ? "paused" : "playing";
            String mediaId = "series".equals(category) && StringUtils.hasText(active.getSeriesId()) ? active.getSeriesId() : active.getItemId();
            String title = switch (category) {
                case "adult" -> "私密观看";
                case "movie" -> display(active.getItemName(), active.getItemId());
                case "series" -> display(active.getSeriesName(), display(active.getItemName(), active.getItemId()));
                default -> "分类待确认";
            };
            sessions.add(new EmbyWatchDetails.Session("active:" + active.getId(), mediaId, active.getItemId(), title, category,
                    active.getSeasonNumber(), active.getEpisodeNumber(), active.getStartTime(), null, seconds[0], seconds[1],
                    active.getPlaybackIntervalsJson() == null ? "legacy" : "calendar", null, active.getDeviceName(), active.getClientName(),
                    active.getEmbyUserId(), display(active.getEmbyUserName(), active.getEmbyUserId()), state, observed));
        }
        return sessions;
    }

    private long[] trackedSeconds(EmbyActivePlaybackSession active, WatchRange range, LocalDateTime observed) {
        long cap = active.getRuntimeTicks() != null && active.getRuntimeTicks() > 0 ? active.getRuntimeTicks() / 10_000_000 : 43_200;
        if (active.getPlaybackIntervalsJson() == null) {
            long total = Math.min(cap, (active.getAccumulatedWatchSeconds() == null ? 0 : active.getAccumulatedWatchSeconds())
                    + secondsBetween(active.getPlaybackStartTime(), observed));
            long contribution = !observed.toLocalDate().isBefore(range.start()) && observed.toLocalDate().isBefore(range.end()) ? total : 0;
            return new long[]{total, contribution};
        }
        List<PlaybackInterval> intervals = new ArrayList<>();
        try {
            for (JsonNode node : objectMapper.readTree(active.getPlaybackIntervalsJson())) {
                intervals.add(new PlaybackInterval(LocalDateTime.parse(node.path("start").asText()), LocalDateTime.parse(node.path("end").asText())));
            }
        } catch (JsonProcessingException | DateTimeParseException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "观看分段暂不可用，请稍后重试");
        }
        if (active.getPlaybackStartTime() != null) intervals.add(new PlaybackInterval(active.getPlaybackStartTime(), observed));
        long total = 0;
        long contribution = 0;
        for (PlaybackInterval interval : intervals) {
            long seconds = Math.min(cap - total, secondsBetween(interval.start(), interval.end()));
            if (seconds <= 0) continue;
            LocalDateTime effectiveEnd = interval.start().plusSeconds(seconds);
            LocalDateTime start = interval.start().isAfter(range.start().atStartOfDay()) ? interval.start() : range.start().atStartOfDay();
            LocalDateTime end = effectiveEnd.isBefore(range.end().atStartOfDay()) ? effectiveEnd : range.end().atStartOfDay();
            contribution += secondsBetween(start, end);
            total += seconds;
        }
        return new long[]{total, contribution};
    }

    private long secondsBetween(LocalDateTime start, LocalDateTime end) {
        return start == null || !end.isAfter(start) ? 0 : Duration.between(start, end).getSeconds();
    }

    private String display(String text, String fallback) { return StringUtils.hasText(text) ? text : fallback; }

    private int offset(int page, int pageSize) {
        if ((long) (page - 1) * pageSize > Integer.MAX_VALUE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分页参数无效，页码至少为1，每页1至100条");
        }
        return (page - 1) * pageSize;
    }

    private void requireCategory(String category) {
        if (category == null || !Set.of("movie", "series", "adult", "unknown").contains(category)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "作品分类无效");
        }
    }

    private WatchRange range(String period, String date, String month) {
        String requestedPeriod = StringUtils.hasText(period) ? period.trim().toLowerCase(Locale.ROOT) : "day";
        try {
            if ("day".equals(requestedPeriod)) {
                LocalDate day = StringUtils.hasText(date) ? LocalDate.parse(date.trim()) : LocalDate.now(WATCH_ZONE);
                return new WatchRange(day, day.plusDays(1));
            }
            if ("month".equals(requestedPeriod)) {
                YearMonth selected = StringUtils.hasText(month) ? YearMonth.parse(month.trim()) : YearMonth.now(WATCH_ZONE);
                return new WatchRange(selected.atDay(1), selected.plusMonths(1).atDay(1));
            }
        } catch (DateTimeParseException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "统计日期或月份格式无效");
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "统计周期无效，请使用 day 或 month");
    }

    private record WatchRange(LocalDate start, LocalDate end) {}
    private record PlaybackInterval(LocalDateTime start, LocalDateTime end) {}
}
