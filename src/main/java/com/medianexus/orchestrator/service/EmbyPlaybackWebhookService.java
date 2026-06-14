package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.config.EmbyProperties;
import com.medianexus.orchestrator.dto.emby.request.EmbyPlaybackWebhookRequest;
import com.medianexus.orchestrator.mapper.EmbyActivePlaybackSessionMapper;
import com.medianexus.orchestrator.mapper.EmbyWatchSessionMapper;
import com.medianexus.orchestrator.model.EmbyActivePlaybackSession;
import com.medianexus.orchestrator.model.EmbyWatchSession;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class EmbyPlaybackWebhookService {

    private static final Logger log = LoggerFactory.getLogger(EmbyPlaybackWebhookService.class);
    private static final ZoneId WATCH_ZONE = ZoneId.of("Asia/Shanghai");
    private static final long TICKS_PER_SECOND = 10_000_000L;
    private static final int MIN_WATCH_SECONDS = 10;
    private static final int FALLBACK_MAX_WATCH_SECONDS = 12 * 60 * 60;
    private static final int TEXT_LIMIT_SHORT = 128;
    private static final int TEXT_LIMIT_NAME = 255;
    private static final int TEXT_LIMIT_TITLE = 512;
    private static final String EVENT_PLAYBACK_START = "playback.start";
    private static final String EVENT_PLAYBACK_STOP = "playback.stop";
    private static final String ITEM_TYPE_MOVIE = "Movie";
    private static final String ITEM_TYPE_EPISODE = "Episode";

    private final EmbyProperties embyProperties;
    private final ObjectMapper objectMapper;
    private final EmbyActivePlaybackSessionMapper activeSessionMapper;
    private final EmbyWatchSessionMapper watchSessionMapper;

    public EmbyPlaybackWebhookService(
            EmbyProperties embyProperties,
            ObjectMapper objectMapper,
            EmbyActivePlaybackSessionMapper activeSessionMapper,
            EmbyWatchSessionMapper watchSessionMapper
    ) {
        this.embyProperties = embyProperties;
        this.objectMapper = objectMapper;
        this.activeSessionMapper = activeSessionMapper;
        this.watchSessionMapper = watchSessionMapper;
    }

    @Transactional
    public void receivePlaybackEvent(String secret, String body) {
        validateSecret(secret);
        ParsedPlaybackWebhookRequest parsedRequest = parsePlaybackRequest(body);
        if (parsedRequest == null) {
            log.warn("Emby webhook ignored because payload is empty or invalid");
            return;
        }

        EmbyPlaybackWebhookRequest request = parsedRequest.request();
        String event = normalizeEvent(request.event());
        if (!EVENT_PLAYBACK_START.equals(event) && !EVENT_PLAYBACK_STOP.equals(event)) {
            log.debug("Emby webhook ignored because event is unsupported event={} rootFields={}",
                    event,
                    parsedRequest.rootFields()
            );
            return;
        }

        String itemType = normalizeItemType(request.itemType());
        if (itemType == null) {
            log.info("Emby {} ignored because itemType is unsupported itemType={} itemName={} rootFields={}",
                    event,
                    displayLogText(request.itemType()),
                    displayLogText(request.itemName()),
                    parsedRequest.rootFields()
            );
            return;
        }

        String embySessionId = shortText(request.sessionId(), TEXT_LIMIT_SHORT);
        String itemId = shortText(request.itemId(), TEXT_LIMIT_SHORT);
        String embyUserId = shortText(request.userId(), TEXT_LIMIT_SHORT);
        Long positionTicks = optionalTicks(request.positionTicks());
        List<String> missingFields = missingRequiredFields(embySessionId, itemId, embyUserId);
        if (!missingFields.isEmpty()) {
            log.warn("Emby {} ignored because required fields are missing fields={} itemType={} itemName={} rootFields={}",
                    event,
                    missingFields,
                    itemType,
                    displayLogText(request.itemName()),
                    parsedRequest.rootFields()
            );
            return;
        }

        cleanupExpiredTrackedSessions();

        LocalDateTime eventTime = parseEventTime(request.date());
        log.info("Emby webhook accepted event={} itemType={} sessionId={} itemId={} userId={} userName={} itemName={} hasPositionTicks={} hasRuntimeTicks={} eventTime={}",
                event,
                itemType,
                embySessionId,
                itemId,
                embyUserId,
                displayLogText(request.userName()),
                displayLogText(request.itemName()),
                positionTicks != null,
                optionalTicks(request.runtimeTicks()) != null,
                eventTime
        );

        if (EVENT_PLAYBACK_START.equals(event)) {
            recordStart(request, itemType, embySessionId, embyUserId, itemId, positionTicks, eventTime);
            return;
        }

        settleStop(request, itemType, embySessionId, embyUserId, itemId, positionTicks, eventTime);
    }

    private ParsedPlaybackWebhookRequest parsePlaybackRequest(String body) {
        String payload = clean(body);
        if (!StringUtils.hasText(payload)) {
            return null;
        }

        try {
            JsonNode root = objectMapper.readTree(payload);
            return new ParsedPlaybackWebhookRequest(
                    new EmbyPlaybackWebhookRequest(
                            textAt(root, "event", "Event", "Title"),
                            textAt(root, "date", "Date", "TimeStamp", "Timestamp"),
                            textAt(root, "userId", "UserId", "UserID", "User.Id", "User.UserId", "Session.UserId"),
                            textAt(root, "userName", "UserName", "User.Name", "User.Username", "Session.UserName"),
                            textAt(root, "sessionId", "SessionId", "SessionID", "Session.Id", "PlaySessionId",
                                    "Session.PlaySessionId", "Session.PlayState.PlaySessionId"),
                            textAt(root, "itemId", "ItemId", "ItemID", "Item.Id", "Item.ItemId"),
                            textAt(root, "itemType", "ItemType", "Item.Type"),
                            textAt(root, "itemName", "ItemName", "Item.Name"),
                            textAt(root, "seriesId", "SeriesId", "SeriesID", "Item.SeriesId", "Item.SeriesID"),
                            textAt(root, "seriesName", "SeriesName", "Item.SeriesName", "Item.ShowName",
                                    "ItemNameGrandparent", "Item.GrandparentName"),
                            textAt(root, "runtimeTicks", "RuntimeTicks", "RunTimeTicks", "ItemRunTimeTicks",
                                    "Item.RuntimeTicks", "Item.RunTimeTicks"),
                            textAt(root, "positionTicks", "PositionTicks", "PlaybackPositionTicks",
                                    "SessionPlaybackPositionTicks", "Session.PositionTicks",
                                    "Session.PlaybackPositionTicks", "Session.PlayState.PositionTicks",
                                    "PlayState.PositionTicks"),
                            textAt(root, "deviceName", "DeviceName", "Session.DeviceName", "Device.Name"),
                            textAt(root, "clientName", "ClientName", "AppName", "Session.Client", "Session.AppName")
                    ),
                    describeRootFields(root)
            );
        } catch (JsonProcessingException exception) {
            log.warn("Emby webhook payload is not valid JSON");
            return null;
        }
    }

    private JsonNode firstNode(JsonNode root, String... paths) {
        for (String path : paths) {
            JsonNode node = nodeAt(root, path);
            if (node != null && !node.isNull()) {
                return node;
            }
        }
        return null;
    }

    private String textAt(JsonNode root, String... paths) {
        JsonNode node = firstNode(root, paths);
        if (node == null || node.isContainerNode()) {
            return null;
        }
        return node.asText();
    }

    private JsonNode nodeAt(JsonNode root, String path) {
        JsonNode current = root;
        for (String part : path.split("\\.")) {
            current = child(current, part);
            if (current == null || current.isNull()) {
                return null;
            }
        }
        return current;
    }

    private JsonNode child(JsonNode node, String fieldName) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode exact = node.get(fieldName);
        if (exact != null) {
            return exact;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (entry.getKey().equalsIgnoreCase(fieldName)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private void validateSecret(String secret) {
        String expectedSecret = clean(embyProperties.getWebhookSecret());
        if (!StringUtils.hasText(expectedSecret) || !expectedSecret.equals(secret)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Emby webhook secret 无效", HttpStatus.FORBIDDEN);
        }
    }

    private void recordStart(
            EmbyPlaybackWebhookRequest request,
            String itemType,
            String embySessionId,
            String embyUserId,
            String itemId,
            Long startPositionTicks,
            LocalDateTime eventTime
    ) {
        closeSupersededActiveSessions(request, embySessionId, embyUserId, itemId, eventTime);

        EmbyActivePlaybackSession session = new EmbyActivePlaybackSession();
        session.setEmbySessionId(embySessionId);
        session.setEmbyUserId(embyUserId);
        session.setEmbyUserName(shortText(request.userName(), TEXT_LIMIT_NAME));
        session.setItemId(itemId);
        session.setItemType(itemType);
        session.setItemName(shortText(request.itemName(), TEXT_LIMIT_TITLE));
        session.setSeriesId(shortText(request.seriesId(), TEXT_LIMIT_SHORT));
        session.setSeriesName(shortText(request.seriesName(), TEXT_LIMIT_TITLE));
        session.setRuntimeTicks(optionalTicks(request.runtimeTicks()));
        session.setStartPositionTicks(startPositionTicks);
        session.setStartTime(eventTime);
        session.setDeviceName(shortText(request.deviceName(), TEXT_LIMIT_NAME));
        session.setClientName(shortText(request.clientName(), TEXT_LIMIT_NAME));
        activeSessionMapper.upsertActiveSession(session);
        log.info("Emby playback.start recorded sessionId={} itemId={} itemType={} startTime={} hasStartPositionTicks={}",
                embySessionId,
                itemId,
                itemType,
                eventTime,
                startPositionTicks != null
        );
    }

    private void settleStop(
            EmbyPlaybackWebhookRequest request,
            String itemType,
            String embySessionId,
            String embyUserId,
            String itemId,
            Long stopPositionTicks,
            LocalDateTime stopTime
    ) {
        EmbyActivePlaybackSession activeSession = activeSessionMapper.selectActiveSessionForUpdate(embySessionId, itemId);
        if (activeSession == null) {
            log.info("Emby playback.stop ignored because tracked session is missing sessionId={} itemId={} userId={} itemName={}",
                    embySessionId,
                    itemId,
                    embyUserId,
                    displayLogText(request.itemName())
            );
            return;
        }

        Long runtimeTicks = firstPositiveTick(optionalTicks(request.runtimeTicks()), activeSession.getRuntimeTicks());
        WatchDurationResult durationResult = calculateWatchSeconds(
                activeSession.getStartPositionTicks(),
                stopPositionTicks,
                activeSession.getStartTime(),
                stopTime,
                runtimeTicks
        );
        if (durationResult.watchSeconds() == null) {
            log.info("Emby playback.stop ignored because watch duration is invalid sessionId={} itemId={} reason={} startTime={} stopTime={} hasStartPositionTicks={} hasStopPositionTicks={}",
                    embySessionId,
                    itemId,
                    durationResult.reason(),
                    activeSession.getStartTime(),
                    stopTime,
                    activeSession.getStartPositionTicks() != null,
                    stopPositionTicks != null
            );
            return;
        }

        EmbyWatchSession watchSession = buildWatchSession(
                activeSession,
                embyUserId,
                request.userName(),
                itemType,
                request.itemName(),
                request.seriesId(),
                request.seriesName(),
                runtimeTicks,
                stopPositionTicks,
                stopTime,
                durationResult.watchSeconds(),
                request.deviceName(),
                request.clientName()
        );
        WatchSessionWriteResult writeResult = upsertWatchSession(watchSession);
        boolean reachedRuntimeLimit = hasReachedRuntimeLimit(durationResult.watchSeconds(), runtimeTicks);
        if (reachedRuntimeLimit) {
            activeSessionMapper.deleteActiveSession(embySessionId, itemId);
        }
        log.info("Emby playback.stop settled sessionId={} itemId={} itemType={} userId={} watchSeconds={} durationSource={} stopTime={} writeResult={} trackedClosed={}",
                embySessionId,
                itemId,
                itemType,
                embyUserId,
                durationResult.watchSeconds(),
                durationResult.source(),
                stopTime,
                writeResult,
                reachedRuntimeLimit
        );
    }

    private void closeSupersededActiveSessions(
            EmbyPlaybackWebhookRequest request,
            String embySessionId,
            String embyUserId,
            String currentItemId,
            LocalDateTime stopTime
    ) {
        List<EmbyActivePlaybackSession> activeSessions =
                activeSessionMapper.selectActiveSessionsForContextForUpdate(embySessionId, embyUserId);
        for (EmbyActivePlaybackSession activeSession : activeSessions) {
            if (currentItemId.equals(activeSession.getItemId())
                    || hasConflictingPlaybackContext(request, activeSession)) {
                continue;
            }
            closeSupersededActiveSession(activeSession, stopTime);
        }
    }

    private void closeSupersededActiveSession(
            EmbyActivePlaybackSession activeSession,
            LocalDateTime stopTime
    ) {
        Long runtimeTicks = activeSession.getRuntimeTicks();
        WatchDurationResult durationResult = calculateWatchSeconds(
                activeSession.getStartPositionTicks(),
                null,
                activeSession.getStartTime(),
                stopTime,
                runtimeTicks
        );
        activeSessionMapper.deleteActiveSession(activeSession.getEmbySessionId(), activeSession.getItemId());
        if (durationResult.watchSeconds() == null) {
            log.info("Emby tracked session closed without settlement sessionId={} itemId={} reason={} startTime={} stopTime={}",
                    activeSession.getEmbySessionId(),
                    activeSession.getItemId(),
                    durationResult.reason(),
                    activeSession.getStartTime(),
                    stopTime
            );
            return;
        }

        EmbyWatchSession watchSession = buildWatchSession(
                activeSession,
                activeSession.getEmbyUserId(),
                activeSession.getEmbyUserName(),
                activeSession.getItemType(),
                activeSession.getItemName(),
                activeSession.getSeriesId(),
                activeSession.getSeriesName(),
                runtimeTicks,
                null,
                stopTime,
                durationResult.watchSeconds(),
                activeSession.getDeviceName(),
                activeSession.getClientName()
        );
        WatchSessionWriteResult writeResult = upsertWatchSession(watchSession);
        log.info("Emby tracked session closed by new playback.start sessionId={} itemId={} watchSeconds={} durationSource={} stopTime={} writeResult={}",
                activeSession.getEmbySessionId(),
                activeSession.getItemId(),
                durationResult.watchSeconds(),
                durationResult.source(),
                stopTime,
                writeResult
        );
    }

    private boolean hasConflictingPlaybackContext(
            EmbyPlaybackWebhookRequest request,
            EmbyActivePlaybackSession activeSession
    ) {
        return hasConflictingText(request.deviceName(), activeSession.getDeviceName())
                || hasConflictingText(request.clientName(), activeSession.getClientName());
    }

    private boolean hasConflictingText(String first, String second) {
        String cleanedFirst = clean(first);
        String cleanedSecond = clean(second);
        return StringUtils.hasText(cleanedFirst)
                && StringUtils.hasText(cleanedSecond)
                && !cleanedFirst.equals(cleanedSecond);
    }

    private EmbyWatchSession buildWatchSession(
            EmbyActivePlaybackSession activeSession,
            String embyUserId,
            String embyUserName,
            String itemType,
            String itemName,
            String seriesId,
            String seriesName,
            Long runtimeTicks,
            Long stopPositionTicks,
            LocalDateTime stopTime,
            Integer watchSeconds,
            String deviceName,
            String clientName
    ) {
        EmbyWatchSession watchSession = new EmbyWatchSession();
        watchSession.setEmbySessionId(activeSession.getEmbySessionId());
        watchSession.setEmbyUserId(embyUserId);
        watchSession.setEmbyUserName(firstText(embyUserName, activeSession.getEmbyUserName(), TEXT_LIMIT_NAME));
        watchSession.setItemId(activeSession.getItemId());
        watchSession.setItemType(firstText(itemType, activeSession.getItemType(), TEXT_LIMIT_SHORT));
        watchSession.setItemName(firstText(itemName, activeSession.getItemName(), TEXT_LIMIT_TITLE));
        watchSession.setSeriesId(firstText(seriesId, activeSession.getSeriesId(), TEXT_LIMIT_SHORT));
        watchSession.setSeriesName(firstText(seriesName, activeSession.getSeriesName(), TEXT_LIMIT_TITLE));
        watchSession.setRuntimeTicks(runtimeTicks);
        watchSession.setStartTime(activeSession.getStartTime());
        watchSession.setStopTime(stopTime);
        watchSession.setStartPositionTicks(activeSession.getStartPositionTicks());
        watchSession.setStopPositionTicks(stopPositionTicks);
        watchSession.setWatchSeconds(watchSeconds);
        watchSession.setWatchDate(stopTime.toLocalDate());
        watchSession.setDeviceName(firstText(deviceName, activeSession.getDeviceName(), TEXT_LIMIT_NAME));
        watchSession.setClientName(firstText(clientName, activeSession.getClientName(), TEXT_LIMIT_NAME));
        return watchSession;
    }

    private WatchSessionWriteResult upsertWatchSession(EmbyWatchSession watchSession) {
        EmbyWatchSession existingSession = watchSessionMapper.selectWatchSessionByStartForUpdate(
                watchSession.getEmbySessionId(),
                watchSession.getItemId(),
                watchSession.getStartTime()
        );
        if (existingSession == null) {
            watchSessionMapper.insertWatchSessionIfAbsent(watchSession);
            return WatchSessionWriteResult.INSERTED;
        }
        if (!watchSession.getStopTime().isAfter(existingSession.getStopTime())) {
            return WatchSessionWriteResult.UNCHANGED;
        }
        watchSession.setId(existingSession.getId());
        watchSessionMapper.updateWatchSessionById(watchSession);
        return WatchSessionWriteResult.UPDATED;
    }

    private boolean hasReachedRuntimeLimit(Integer watchSeconds, Long runtimeTicks) {
        if (watchSeconds == null || runtimeTicks == null || runtimeTicks <= 0) {
            return false;
        }
        long runtimeSeconds = runtimeTicks / TICKS_PER_SECOND;
        return runtimeSeconds > 0 && watchSeconds >= runtimeSeconds;
    }

    private void cleanupExpiredTrackedSessions() {
        LocalDateTime cutoff = LocalDateTime.now(WATCH_ZONE).minusHours(24);
        int deletedCount = activeSessionMapper.deleteSessionsUpdatedBefore(cutoff);
        if (deletedCount > 0) {
            log.info("Emby tracked playback sessions cleanup deletedCount={} cutoff={}",
                    deletedCount,
                    cutoff
            );
        }
    }

    private WatchDurationResult calculateWatchSeconds(
            Long startPositionTicks,
            Long stopPositionTicks,
            LocalDateTime startTime,
            LocalDateTime stopTime,
            Long runtimeTicks
    ) {
        Long rawSeconds = null;
        String source = null;

        if (startPositionTicks != null && stopPositionTicks != null) {
            rawSeconds = (stopPositionTicks - startPositionTicks) / TICKS_PER_SECOND;
            source = "position_ticks";
        }
        if ((rawSeconds == null || rawSeconds <= 0) && startTime != null && stopTime != null) {
            rawSeconds = Duration.between(startTime, stopTime).getSeconds();
            source = "event_time";
        }
        if (rawSeconds == null) {
            return WatchDurationResult.invalid("missing_position_ticks_and_event_time");
        }
        if (rawSeconds <= 0) {
            return WatchDurationResult.invalid("non_positive_duration");
        }
        if (rawSeconds < MIN_WATCH_SECONDS) {
            return WatchDurationResult.invalid("duration_less_than_10_seconds");
        }
        long maxSeconds = runtimeTicks != null && runtimeTicks > 0
                ? runtimeTicks / TICKS_PER_SECOND
                : FALLBACK_MAX_WATCH_SECONDS;
        long watchSeconds = Math.min(rawSeconds, maxSeconds);
        if (watchSeconds < MIN_WATCH_SECONDS) {
            return WatchDurationResult.invalid("clamped_duration_less_than_10_seconds");
        }
        return WatchDurationResult.valid((int) Math.min(watchSeconds, Integer.MAX_VALUE), source);
    }

    private Long optionalTicks(String value) {
        String cleaned = clean(value);
        if (!StringUtils.hasText(cleaned)) {
            return null;
        }
        try {
            return Long.parseLong(cleaned);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private List<String> missingRequiredFields(String embySessionId, String itemId, String embyUserId) {
        List<String> fields = new ArrayList<>();
        if (!StringUtils.hasText(embySessionId)) {
            fields.add("sessionId");
        }
        if (!StringUtils.hasText(itemId)) {
            fields.add("itemId");
        }
        if (!StringUtils.hasText(embyUserId)) {
            fields.add("userId");
        }
        return fields;
    }

    private Long firstPositiveTick(Long first, Long second) {
        if (first != null && first > 0) {
            return first;
        }
        if (second != null && second > 0) {
            return second;
        }
        return null;
    }

    private String describeRootFields(JsonNode root) {
        if (root == null || !root.isObject()) {
            return "-";
        }
        List<String> fields = new ArrayList<>();
        Iterator<String> fieldNames = root.fieldNames();
        while (fieldNames.hasNext() && fields.size() < 20) {
            fields.add(fieldNames.next());
        }
        return String.join(",", fields);
    }

    private String normalizeEvent(String event) {
        String cleaned = clean(event);
        return cleaned == null ? "" : cleaned.toLowerCase(Locale.ROOT);
    }

    private String normalizeItemType(String itemType) {
        String cleaned = clean(itemType);
        if (ITEM_TYPE_MOVIE.equalsIgnoreCase(cleaned)) {
            return ITEM_TYPE_MOVIE;
        }
        if (ITEM_TYPE_EPISODE.equalsIgnoreCase(cleaned)) {
            return ITEM_TYPE_EPISODE;
        }
        return null;
    }

    private LocalDateTime parseEventTime(String value) {
        String cleaned = clean(value);
        if (!StringUtils.hasText(cleaned)) {
            return LocalDateTime.now(WATCH_ZONE);
        }
        try {
            return OffsetDateTime.parse(cleaned)
                    .atZoneSameInstant(WATCH_ZONE)
                    .toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // Try the next ISO-8601 shape below.
        }
        try {
            return Instant.parse(cleaned)
                    .atZone(WATCH_ZONE)
                    .toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // Try a local date-time string below.
        }
        try {
            return LocalDateTime.parse(cleaned);
        } catch (DateTimeParseException exception) {
            log.warn("Emby webhook event time is invalid; falling back to now");
            return LocalDateTime.now(WATCH_ZONE);
        }
    }

    private String firstText(String first, String second, int limit) {
        String cleanedFirst = shortText(first, limit);
        if (StringUtils.hasText(cleanedFirst)) {
            return cleanedFirst;
        }
        return shortText(second, limit);
    }

    private String displayLogText(String value) {
        String shortened = shortText(value, 80);
        return shortened == null ? "-" : shortened;
    }

    private String shortText(String value, int limit) {
        String cleaned = clean(value);
        if (!StringUtils.hasText(cleaned)) {
            return null;
        }
        return cleaned.length() <= limit ? cleaned : cleaned.substring(0, limit);
    }

    private String clean(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.trim();
        if ("null".equalsIgnoreCase(cleaned)) {
            return null;
        }
        if (cleaned.length() >= 2
                && ((cleaned.startsWith("\"") && cleaned.endsWith("\""))
                || (cleaned.startsWith("'") && cleaned.endsWith("'")))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        return cleaned;
    }

    private record ParsedPlaybackWebhookRequest(
            EmbyPlaybackWebhookRequest request,
            String rootFields
    ) {
    }

    private record WatchDurationResult(
            Integer watchSeconds,
            String source,
            String reason
    ) {

        private static WatchDurationResult valid(int watchSeconds, String source) {
            return new WatchDurationResult(watchSeconds, source, null);
        }

        private static WatchDurationResult invalid(String reason) {
            return new WatchDurationResult(null, null, reason);
        }
    }

    private enum WatchSessionWriteResult {
        INSERTED,
        UPDATED,
        UNCHANGED
    }
}
