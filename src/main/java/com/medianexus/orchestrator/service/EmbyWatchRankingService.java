package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.config.EmbyProperties;
import com.medianexus.orchestrator.dto.emby.response.EmbyMediaWatchRankingItem;
import com.medianexus.orchestrator.dto.emby.response.EmbyUserWatchRankingItem;
import com.medianexus.orchestrator.dto.emby.response.EmbyWatchRankingResponse;
import com.medianexus.orchestrator.dto.emby.response.EmbyWatchRankingSummaryResponse;
import com.medianexus.orchestrator.dto.emby.response.EmbyWebhookRecentEventResponse;
import com.medianexus.orchestrator.dto.emby.response.EmbyWebhookStatusResponse;
import com.medianexus.orchestrator.mapper.EmbyActivePlaybackSessionMapper;
import com.medianexus.orchestrator.mapper.EmbyWatchSessionMapper;
import com.medianexus.orchestrator.mapper.projection.EmbyMediaWatchRankingRow;
import com.medianexus.orchestrator.mapper.projection.EmbyUserWatchRankingRow;
import com.medianexus.orchestrator.mapper.projection.EmbyWatchSummaryRow;
import com.medianexus.orchestrator.model.EmbyActivePlaybackSession;
import com.medianexus.orchestrator.model.EmbyWatchSession;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class EmbyWatchRankingService {

    private static final ZoneId WATCH_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int DEFAULT_LIMIT = 20;
    private static final int RECENT_EVENT_LIMIT = 8;
    private static final String PERIOD_DAY = "day";
    private static final String PERIOD_MONTH = "month";

    private final AuthService authService;
    private final EmbyProperties embyProperties;
    private final EmbyActivePlaybackSessionMapper activeSessionMapper;
    private final EmbyWatchSessionMapper watchSessionMapper;

    public EmbyWatchRankingService(
            AuthService authService,
            EmbyProperties embyProperties,
            EmbyActivePlaybackSessionMapper activeSessionMapper,
            EmbyWatchSessionMapper watchSessionMapper
    ) {
        this.authService = authService;
        this.embyProperties = embyProperties;
        this.activeSessionMapper = activeSessionMapper;
        this.watchSessionMapper = watchSessionMapper;
    }

    public EmbyWatchRankingResponse getRankings(String period, String date, String month, Integer limit) {
        authService.requireAdminUser();
        WatchRankingPeriod rankingPeriod = normalizePeriod(period, date, month);
        int normalizedLimit = normalizeLimit(limit);

        EmbyWatchSummaryRow summaryRow = watchSessionMapper.selectSummary(
                rankingPeriod.startDate(),
                rankingPeriod.endDate()
        );
        List<EmbyUserWatchRankingItem> users = toUserRankingItems(
                watchSessionMapper.selectUserRankings(
                        rankingPeriod.startDate(),
                        rankingPeriod.endDate(),
                        normalizedLimit
                )
        );
        List<EmbyMediaWatchRankingItem> movies = toMediaRankingItems(
                watchSessionMapper.selectMovieRankings(
                        rankingPeriod.startDate(),
                        rankingPeriod.endDate(),
                        normalizedLimit
                )
        );
        List<EmbyMediaWatchRankingItem> series = toMediaRankingItems(
                watchSessionMapper.selectSeriesRankings(
                        rankingPeriod.startDate(),
                        rankingPeriod.endDate(),
                        normalizedLimit
                )
        );

        return new EmbyWatchRankingResponse(
                rankingPeriod.period(),
                rankingPeriod.date(),
                rankingPeriod.month(),
                WATCH_ZONE.getId(),
                LocalDateTime.now(WATCH_ZONE),
                toSummaryResponse(summaryRow),
                users,
                movies,
                series,
                buildWebhookStatus()
        );
    }

    private WatchRankingPeriod normalizePeriod(String period, String date, String month) {
        String normalizedPeriod = StringUtils.hasText(period)
                ? period.trim().toLowerCase(Locale.ROOT)
                : PERIOD_DAY;
        if (PERIOD_DAY.equals(normalizedPeriod)) {
            LocalDate watchDate = normalizeDate(date);
            return new WatchRankingPeriod(
                    PERIOD_DAY,
                    watchDate,
                    YearMonth.from(watchDate).toString(),
                    watchDate,
                    watchDate.plusDays(1)
            );
        }
        if (PERIOD_MONTH.equals(normalizedPeriod)) {
            YearMonth watchMonth = normalizeMonth(month);
            LocalDate startDate = watchMonth.atDay(1);
            return new WatchRankingPeriod(
                    PERIOD_MONTH,
                    startDate,
                    watchMonth.toString(),
                    startDate,
                    watchMonth.plusMonths(1).atDay(1)
            );
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "统计周期无效，请使用 day 或 month");
    }

    private LocalDate normalizeDate(String date) {
        if (!StringUtils.hasText(date)) {
            return LocalDate.now(WATCH_ZONE);
        }
        try {
            return LocalDate.parse(date.trim());
        } catch (DateTimeParseException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "日期格式无效，请使用 yyyy-MM-dd");
        }
    }

    private YearMonth normalizeMonth(String month) {
        if (!StringUtils.hasText(month)) {
            return YearMonth.now(WATCH_ZONE);
        }
        try {
            return YearMonth.parse(month.trim());
        } catch (DateTimeParseException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "月份格式无效，请使用 yyyy-MM");
        }
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return DEFAULT_LIMIT;
        }
        return limit;
    }

    private EmbyWatchRankingSummaryResponse toSummaryResponse(EmbyWatchSummaryRow row) {
        if (row == null) {
            return new EmbyWatchRankingSummaryResponse(0, 0, 0, null);
        }
        return new EmbyWatchRankingSummaryResponse(
                safeLong(row.getActiveUserCount()),
                safeLong(row.getTotalWatchSeconds()),
                safeLong(row.getTotalPlayCount()),
                row.getLastWatchedAt()
        );
    }

    private List<EmbyUserWatchRankingItem> toUserRankingItems(List<EmbyUserWatchRankingRow> rows) {
        return IntStream.range(0, rows.size())
                .mapToObj(index -> {
                    EmbyUserWatchRankingRow row = rows.get(index);
                    return new EmbyUserWatchRankingItem(
                            index + 1,
                            row.getEmbyUserId(),
                            displayText(row.getUserName(), row.getEmbyUserId()),
                            safeLong(row.getWatchSeconds()),
                            safeLong(row.getPlayCount()),
                            row.getLastWatchedAt(),
                            row.getLastItemName()
                    );
                })
                .toList();
    }

    private List<EmbyMediaWatchRankingItem> toMediaRankingItems(List<EmbyMediaWatchRankingRow> rows) {
        return IntStream.range(0, rows.size())
                .mapToObj(index -> {
                    EmbyMediaWatchRankingRow row = rows.get(index);
                    return new EmbyMediaWatchRankingItem(
                            index + 1,
                            row.getMediaId(),
                            displayText(row.getTitle(), row.getMediaId()),
                            safeLong(row.getWatchSeconds()),
                            safeLong(row.getPlayCount()),
                            row.getLastPlayedAt()
                    );
                })
                .toList();
    }

    private EmbyWebhookStatusResponse buildWebhookStatus() {
        List<EmbyWebhookRecentEventResponse> recentEvents = Stream.concat(
                        activeSessionMapper.selectRecentActiveSessions(RECENT_EVENT_LIMIT).stream()
                                .map(this::toStartEvent),
                        watchSessionMapper.selectRecentWatchSessions(RECENT_EVENT_LIMIT).stream()
                                .map(this::toStopEvent)
                )
                .sorted(Comparator.comparing(EmbyWebhookRecentEventResponse::eventTime).reversed())
                .limit(RECENT_EVENT_LIMIT)
                .toList();

        return new EmbyWebhookStatusResponse(
                StringUtils.hasText(embyProperties.getWebhookSecret()),
                activeSessionMapper.countActiveSessions(),
                recentEvents
        );
    }

    private EmbyWebhookRecentEventResponse toStartEvent(EmbyActivePlaybackSession session) {
        return new EmbyWebhookRecentEventResponse(
                "playback.start",
                session.getStartTime(),
                displayText(session.getEmbyUserName(), session.getEmbyUserId()),
                displayItemName(session.getSeriesName(), session.getItemName(), session.getItemId()),
                null
        );
    }

    private EmbyWebhookRecentEventResponse toStopEvent(EmbyWatchSession session) {
        return new EmbyWebhookRecentEventResponse(
                "playback.stop",
                session.getStopTime(),
                displayText(session.getEmbyUserName(), session.getEmbyUserId()),
                displayItemName(session.getSeriesName(), session.getItemName(), session.getItemId()),
                session.getWatchSeconds()
        );
    }

    private String displayItemName(String seriesName, String itemName, String fallback) {
        if (StringUtils.hasText(seriesName)) {
            return seriesName;
        }
        return displayText(itemName, fallback);
    }

    private String displayText(String value, String fallback) {
        if (StringUtils.hasText(value)) {
            return value;
        }
        return StringUtils.hasText(fallback) ? fallback : "-";
    }

    private long safeLong(Long value) {
        return value == null ? 0 : value;
    }

    private record WatchRankingPeriod(
            String period,
            LocalDate date,
            String month,
            LocalDate startDate,
            LocalDate endDate
    ) {
    }
}
