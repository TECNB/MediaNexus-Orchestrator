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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
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
        EmbyPlaybackWebhookRequest request = parsePlaybackRequest(body);
        if (request == null) {
            log.warn("Emby webhook ignored because payload is empty or invalid");
            return;
        }

        String event = normalizeEvent(request.event());
        if (!EVENT_PLAYBACK_START.equals(event) && !EVENT_PLAYBACK_STOP.equals(event)) {
            return;
        }

        String itemType = normalizeItemType(request.itemType());
        if (itemType == null) {
            return;
        }

        String embySessionId = requiredText(request.sessionId(), "sessionId", event);
        String itemId = requiredText(request.itemId(), "itemId", event);
        String embyUserId = requiredText(request.userId(), "userId", event);
        Long positionTicks = requiredTicks(request.positionTicks(), "positionTicks", event);
        if (embySessionId == null || itemId == null || embyUserId == null || positionTicks == null) {
            return;
        }

        LocalDateTime eventTime = parseEventTime(request.date());
        if (EVENT_PLAYBACK_START.equals(event)) {
            recordStart(request, itemType, embySessionId, embyUserId, itemId, positionTicks, eventTime);
            return;
        }

        settleStop(request, itemType, embySessionId, embyUserId, itemId, positionTicks, eventTime);
    }

    private EmbyPlaybackWebhookRequest parsePlaybackRequest(String body) {
        String payload = clean(body);
        if (!StringUtils.hasText(payload)) {
            return null;
        }

        try {
            JsonNode root = objectMapper.readTree(payload);
            return new EmbyPlaybackWebhookRequest(
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
            Long positionTicks,
            LocalDateTime eventTime
    ) {
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
        session.setStartPositionTicks(positionTicks);
        session.setStartTime(eventTime);
        session.setDeviceName(shortText(request.deviceName(), TEXT_LIMIT_NAME));
        session.setClientName(shortText(request.clientName(), TEXT_LIMIT_NAME));
        activeSessionMapper.upsertActiveSession(session);
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
            log.debug("Emby playback.stop ignored because active session is missing sessionId={} itemId={}",
                    embySessionId,
                    itemId
            );
            return;
        }

        Integer watchSeconds = calculateWatchSeconds(
                activeSession.getStartPositionTicks(),
                stopPositionTicks,
                firstPositiveTick(optionalTicks(request.runtimeTicks()), activeSession.getRuntimeTicks())
        );
        activeSessionMapper.deleteActiveSession(embySessionId, itemId);
        if (watchSeconds == null) {
            return;
        }

        EmbyWatchSession watchSession = new EmbyWatchSession();
        watchSession.setEmbySessionId(embySessionId);
        watchSession.setEmbyUserId(embyUserId);
        watchSession.setEmbyUserName(firstText(request.userName(), activeSession.getEmbyUserName(), TEXT_LIMIT_NAME));
        watchSession.setItemId(itemId);
        watchSession.setItemType(itemType);
        watchSession.setItemName(firstText(request.itemName(), activeSession.getItemName(), TEXT_LIMIT_TITLE));
        watchSession.setSeriesId(firstText(request.seriesId(), activeSession.getSeriesId(), TEXT_LIMIT_SHORT));
        watchSession.setSeriesName(firstText(request.seriesName(), activeSession.getSeriesName(), TEXT_LIMIT_TITLE));
        watchSession.setRuntimeTicks(firstPositiveTick(optionalTicks(request.runtimeTicks()), activeSession.getRuntimeTicks()));
        watchSession.setStartTime(activeSession.getStartTime());
        watchSession.setStopTime(stopTime);
        watchSession.setStartPositionTicks(activeSession.getStartPositionTicks());
        watchSession.setStopPositionTicks(stopPositionTicks);
        watchSession.setWatchSeconds(watchSeconds);
        watchSession.setWatchDate(stopTime.toLocalDate());
        watchSession.setDeviceName(firstText(request.deviceName(), activeSession.getDeviceName(), TEXT_LIMIT_NAME));
        watchSession.setClientName(firstText(request.clientName(), activeSession.getClientName(), TEXT_LIMIT_NAME));
        watchSessionMapper.insertWatchSessionIfAbsent(watchSession);
    }

    private Integer calculateWatchSeconds(Long startPositionTicks, long stopPositionTicks, Long runtimeTicks) {
        if (startPositionTicks == null) {
            return null;
        }
        long rawSeconds = (stopPositionTicks - startPositionTicks) / TICKS_PER_SECOND;
        if (rawSeconds < MIN_WATCH_SECONDS) {
            return null;
        }
        long maxSeconds = runtimeTicks != null && runtimeTicks > 0
                ? runtimeTicks / TICKS_PER_SECOND
                : FALLBACK_MAX_WATCH_SECONDS;
        long watchSeconds = Math.min(rawSeconds, maxSeconds);
        if (watchSeconds < MIN_WATCH_SECONDS) {
            return null;
        }
        return (int) Math.min(watchSeconds, Integer.MAX_VALUE);
    }

    private String requiredText(String value, String field, String event) {
        String cleaned = clean(value);
        if (!StringUtils.hasText(cleaned)) {
            log.warn("Emby {} ignored because {} is blank", event, field);
            return null;
        }
        return shortText(cleaned, TEXT_LIMIT_SHORT);
    }

    private Long requiredTicks(String value, String field, String event) {
        Long parsed = optionalTicks(value);
        if (parsed == null) {
            log.warn("Emby {} ignored because {} is invalid", event, field);
        }
        return parsed;
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

    private Long firstPositiveTick(Long first, Long second) {
        if (first != null && first > 0) {
            return first;
        }
        if (second != null && second > 0) {
            return second;
        }
        return null;
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
}
