package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.EmbyProperties;
import com.medianexus.orchestrator.integration.emby.EmbyItemMetadata;
import com.medianexus.orchestrator.integration.emby.EmbyItemMetadataLookup;
import com.medianexus.orchestrator.mapper.EmbyActivePlaybackSessionMapper;
import com.medianexus.orchestrator.mapper.EmbyWatchSessionMapper;
import com.medianexus.orchestrator.model.EmbyActivePlaybackSession;
import com.medianexus.orchestrator.model.EmbyWatchSession;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EmbyPlaybackWebhookServiceTest {

    private final EmbyProperties properties = new EmbyProperties();
    private final EmbyItemMetadataLookup itemMetadataLookup = mock(EmbyItemMetadataLookup.class);
    private final EmbyActivePlaybackSessionMapper activeSessionMapper = mock(EmbyActivePlaybackSessionMapper.class);
    private final EmbyWatchSessionMapper watchSessionMapper = mock(EmbyWatchSessionMapper.class);
    private final EmbyPlaybackWebhookService service = new EmbyPlaybackWebhookService(
            properties,
            new ObjectMapper(),
            itemMetadataLookup,
            activeSessionMapper,
            watchSessionMapper
    );

    @BeforeEach
    void setUp() {
        properties.setWebhookSecret("secret");
        when(itemMetadataLookup.findItemMetadata(any(), any())).thenReturn(Optional.empty());
        when(activeSessionMapper.selectActiveSessionsForContextForUpdate(any(), any())).thenReturn(List.of());
    }

    @Test
    void splitsCrossMidnightPlaybackAfterPauseWithoutCountingPauseOrDuplicateEvents() {
        java.util.concurrent.atomic.AtomicReference<EmbyActivePlaybackSession> active = new java.util.concurrent.atomic.AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> { active.set(invocation.getArgument(0)); return null; })
                .when(activeSessionMapper).upsertActiveSession(any());
        when(activeSessionMapper.selectActiveSessionForUpdate(any(), any())).thenAnswer(invocation -> active.get());
        org.mockito.Mockito.doAnswer(invocation -> { active.set(null); return null; })
                .when(activeSessionMapper).deleteActiveSession(any(), any());

        service.receivePlaybackEvent("secret", event("playback.start", "2026-10-31T23:40:00+08:00"));
        service.receivePlaybackEvent("secret", event("playback.pause", "2026-10-31T23:50:00+08:00"));
        service.receivePlaybackEvent("secret", event("playback.start", "2026-10-31T23:40:00+08:00"));
        service.receivePlaybackEvent("secret", event("playback.pause", "2026-10-31T23:55:00+08:00"));
        service.receivePlaybackEvent("secret", event("playback.unpause", "2026-11-01T00:10:00+08:00"));
        service.receivePlaybackEvent("secret", event("playback.unpause", "2026-11-01T00:10:00+08:00"));
        service.receivePlaybackEvent("secret", event("playback.pause", "2026-10-31T23:50:00+08:00"));
        service.receivePlaybackEvent("secret", event("playback.stop", "2026-11-01T00:20:00+08:00"));
        service.receivePlaybackEvent("secret", event("playback.stop", "2026-11-01T00:20:00+08:00"));

        ArgumentCaptor<EmbyWatchSession> settled = ArgumentCaptor.forClass(EmbyWatchSession.class);
        verify(watchSessionMapper).insertWatchSessionIfAbsent(settled.capture());
        assertThat(settled.getValue().getWatchSeconds()).isEqualTo(1200);
        assertThat(settled.getValue().getAccountingBasis()).isEqualTo("calendar");
        assertThat(settled.getValue().getStopTimeSource()).isEqualTo("event");
        verify(watchSessionMapper).insertDailyContribution(any(), eq(java.time.LocalDate.parse("2026-10-31")), eq(600));
        verify(watchSessionMapper).insertDailyContribution(any(), eq(java.time.LocalDate.parse("2026-11-01")), eq(600));
    }

    @Test
    void splitsContinuousCrossMidnightPlaybackAndCountsShortDailyFragmentsOfAnEffectiveSession() {
        trackActiveSession();
        service.receivePlaybackEvent("secret", event("playback.start", "2026-10-31T23:59:58+08:00"));
        service.receivePlaybackEvent("secret", event("playback.stop", "2026-11-01T00:00:08+08:00"));
        verify(watchSessionMapper).insertDailyContribution(any(), eq(java.time.LocalDate.parse("2026-10-31")), eq(2));
        verify(watchSessionMapper).insertDailyContribution(any(), eq(java.time.LocalDate.parse("2026-11-01")), eq(8));
    }

    @Test
    void attributesFortyMinutePlaybackToTwentyMinutesPerDay() {
        trackActiveSession();
        service.receivePlaybackEvent("secret", event("playback.start", "2026-10-31T23:40:00+08:00"));
        service.receivePlaybackEvent("secret", event("playback.stop", "2026-11-01T00:20:00+08:00"));
        verify(watchSessionMapper).insertDailyContribution(any(), eq(java.time.LocalDate.parse("2026-10-31")), eq(1200));
        verify(watchSessionMapper).insertDailyContribution(any(), eq(java.time.LocalDate.parse("2026-11-01")), eq(1200));
    }

    @Test
    void ignoresAWholeSessionShorterThanTenSeconds() {
        trackActiveSession();
        service.receivePlaybackEvent("secret", event("playback.start", "2026-10-31T23:59:58+08:00"));
        service.receivePlaybackEvent("secret", event("playback.stop", "2026-11-01T00:00:07+08:00"));
        verify(watchSessionMapper, org.mockito.Mockito.never()).insertWatchSessionIfAbsent(any());
        verify(watchSessionMapper, org.mockito.Mockito.never()).insertDailyContribution(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void keepsLegacyActiveSessionOnStopDayWithoutInventingItsMissingIntervals() {
        var tracked = trackActiveSession();
        service.receivePlaybackEvent("secret", event("playback.start", "2026-10-31T23:40:00+08:00"));
        tracked.get().setPlaybackIntervalsJson(null);
        service.receivePlaybackEvent("secret", event("playback.stop", "2026-11-01T00:20:00+08:00"));
        ArgumentCaptor<EmbyWatchSession> settled = ArgumentCaptor.forClass(EmbyWatchSession.class);
        verify(watchSessionMapper).insertWatchSessionIfAbsent(settled.capture());
        assertThat(settled.getValue().getAccountingBasis()).isEqualTo("legacy");
        assertThat(settled.getValue().getWatchSeconds()).isEqualTo(2400);
        assertThat(settled.getValue().getWatchDate()).isEqualTo(java.time.LocalDate.parse("2026-11-01"));
        verify(watchSessionMapper, org.mockito.Mockito.never()).insertDailyContribution(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void settlesSupersededPlaybackThroughTheSameDailyContributionPath() {
        var tracked = trackActiveSession();
        service.receivePlaybackEvent("secret", event("playback.start", "2026-10-31T23:40:00+08:00"));
        var first = tracked.get();
        when(activeSessionMapper.selectActiveSessionsForContextForUpdate(any(), any())).thenReturn(List.of(first));
        service.receivePlaybackEvent("secret", event("playback.start", "2026-11-01T00:20:00+08:00").replace("item-1", "item-2"));
        ArgumentCaptor<EmbyWatchSession> settled = ArgumentCaptor.forClass(EmbyWatchSession.class);
        verify(watchSessionMapper).insertWatchSessionIfAbsent(settled.capture());
        assertThat(settled.getValue().getStopTimeSource()).isEqualTo("superseded");
        verify(watchSessionMapper).insertDailyContribution(any(), eq(java.time.LocalDate.parse("2026-10-31")), eq(1200));
        verify(watchSessionMapper).insertDailyContribution(any(), eq(java.time.LocalDate.parse("2026-11-01")), eq(1200));
    }

    private java.util.concurrent.atomic.AtomicReference<EmbyActivePlaybackSession> trackActiveSession() {
        var active = new java.util.concurrent.atomic.AtomicReference<EmbyActivePlaybackSession>();
        org.mockito.Mockito.doAnswer(invocation -> { active.set(invocation.getArgument(0)); return null; })
                .when(activeSessionMapper).upsertActiveSession(any());
        when(activeSessionMapper.selectActiveSessionForUpdate(any(), any())).thenAnswer(invocation -> active.get());
        org.mockito.Mockito.doAnswer(invocation -> { active.set(null); return null; })
                .when(activeSessionMapper).deleteActiveSession(any(), any());
        return active;
    }

    private String event(String name, String date) {
        return "{\"Event\":\"" + name + "\",\"Date\":\"" + date
                + "\",\"Session\":{\"Id\":\"session-1\",\"UserId\":\"user-1\"},"
                + "\"Item\":{\"Id\":\"item-1\",\"Type\":\"Movie\",\"Name\":\"Movie title\"}}";
    }

    @Test
    void recordsEpisodePositionFromNativeWebhookItemFields() {
        service.receivePlaybackEvent("secret", """
                {
                  "Event": "playback.start",
                  "Date": "2026-07-06T10:00:00+08:00",
                  "Session": {
                    "Id": "session-1",
                    "UserId": "user-1"
                  },
                  "Item": {
                    "Id": "item-1",
                    "Type": "Episode",
                    "Name": "Episode title",
                    "SeriesName": "Series title",
                    "ParentIndexNumber": 2,
                    "IndexNumber": 7
                  }
                }
                """);

        ArgumentCaptor<EmbyActivePlaybackSession> captor =
                ArgumentCaptor.forClass(EmbyActivePlaybackSession.class);
        verify(activeSessionMapper).upsertActiveSession(captor.capture());

        EmbyActivePlaybackSession session = captor.getValue();
        assertThat(session.getItemType()).isEqualTo("Episode");
        assertThat(session.getSeasonNumber()).isEqualTo(2);
        assertThat(session.getEpisodeNumber()).isEqualTo(7);
    }

    @Test
    void recordsEpisodePositionFromEmbyItemLookupWhenWebhookOmitsIt() {
        when(itemMetadataLookup.findItemMetadata(eq("user-1"), eq("item-1")))
                .thenReturn(Optional.of(new EmbyItemMetadata(
                        "item-1",
                        "Episode",
                        "Looked up episode",
                        "series-1",
                        "Looked up series",
                        3,
                        1,
                        "18000000000"
                )));

        service.receivePlaybackEvent("secret", """
                {
                  "Event": "playback.start",
                  "Date": "2026-07-06T10:00:00+08:00",
                  "Session": {
                    "Id": "session-1",
                    "UserId": "user-1"
                  },
                  "Item": {
                    "Id": "item-1",
                    "Type": "Episode",
                    "Name": "Webhook episode",
                    "SeriesName": "Webhook series"
                  }
                }
                """);

        ArgumentCaptor<EmbyActivePlaybackSession> captor =
                ArgumentCaptor.forClass(EmbyActivePlaybackSession.class);
        verify(activeSessionMapper).upsertActiveSession(captor.capture());

        EmbyActivePlaybackSession session = captor.getValue();
        assertThat(session.getItemName()).isEqualTo("Looked up episode");
        assertThat(session.getSeriesId()).isEqualTo("series-1");
        assertThat(session.getSeriesName()).isEqualTo("Looked up series");
        assertThat(session.getSeasonNumber()).isEqualTo(3);
        assertThat(session.getEpisodeNumber()).isEqualTo(1);
        assertThat(session.getRuntimeTicks()).isEqualTo(18000000000L);
    }

    @Test
    void looksUpMovieClassificationAndKeepsUnavailableClassificationUnknown() {
        service.receivePlaybackEvent("secret", """
                {
                  "Event": "playback.start",
                  "Date": "2026-07-06T10:00:00+08:00",
                  "Session": {
                    "Id": "session-1",
                    "UserId": "user-1"
                  },
                  "Item": {
                    "Id": "movie-1",
                    "Type": "Movie",
                    "Name": "Movie title"
                  }
                }
                """);

        verify(itemMetadataLookup).findItemMetadata("user-1", "movie-1");
        ArgumentCaptor<EmbyActivePlaybackSession> session = ArgumentCaptor.forClass(EmbyActivePlaybackSession.class);
        verify(activeSessionMapper).upsertActiveSession(session.capture());
        assertThat(session.getValue().getWatchCategory()).isEqualTo("unknown");
    }

    @Test
    void settlesStopWithEpisodePositionFromTrackedStartWhenStopPayloadOmitsIt() {
        EmbyActivePlaybackSession activeSession = new EmbyActivePlaybackSession();
        activeSession.setEmbySessionId("session-1");
        activeSession.setEmbyUserId("user-1");
        activeSession.setItemId("item-1");
        activeSession.setItemType("Episode");
        activeSession.setItemName("Episode title");
        activeSession.setSeriesName("Series title");
        activeSession.setSeasonNumber(1);
        activeSession.setEpisodeNumber(3);
        activeSession.setStartTime(LocalDateTime.parse("2026-07-06T10:00:00"));
        activeSession.setStartPositionTicks(0L);
        when(activeSessionMapper.selectActiveSessionForUpdate(eq("session-1"), eq("item-1")))
                .thenReturn(activeSession);

        service.receivePlaybackEvent("secret", """
                {
                  "Event": "playback.stop",
                  "Date": "2026-07-06T10:02:00+08:00",
                  "SessionId": "session-1",
                  "UserId": "user-1",
                  "ItemId": "item-1",
                  "ItemType": "Episode",
                  "PositionTicks": "1200000000"
                }
                """);

        ArgumentCaptor<EmbyWatchSession> captor = ArgumentCaptor.forClass(EmbyWatchSession.class);
        verify(watchSessionMapper).insertWatchSessionIfAbsent(captor.capture());

        EmbyWatchSession session = captor.getValue();
        assertThat(session.getSeasonNumber()).isEqualTo(1);
        assertThat(session.getEpisodeNumber()).isEqualTo(3);
        assertThat(session.getWatchSeconds()).isEqualTo(120);
    }

    @Test
    void settlesStopWithEpisodePositionFromLookupWhenTrackedStartMissesIt() {
        EmbyActivePlaybackSession activeSession = new EmbyActivePlaybackSession();
        activeSession.setEmbySessionId("session-1");
        activeSession.setEmbyUserId("user-1");
        activeSession.setItemId("item-1");
        activeSession.setItemType("Episode");
        activeSession.setItemName("Episode title");
        activeSession.setSeriesName("Series title");
        activeSession.setStartTime(LocalDateTime.parse("2026-07-06T10:00:00"));
        activeSession.setStartPositionTicks(0L);
        when(activeSessionMapper.selectActiveSessionForUpdate(eq("session-1"), eq("item-1")))
                .thenReturn(activeSession);
        when(itemMetadataLookup.findItemMetadata(eq("user-1"), eq("item-1")))
                .thenReturn(Optional.of(new EmbyItemMetadata(
                        "item-1",
                        "Episode",
                        "Looked up episode",
                        "series-1",
                        "Looked up series",
                        2,
                        9,
                        null
                )));

        service.receivePlaybackEvent("secret", """
                {
                  "Event": "playback.stop",
                  "Date": "2026-07-06T10:02:00+08:00",
                  "SessionId": "session-1",
                  "UserId": "user-1",
                  "ItemId": "item-1",
                  "ItemType": "Episode",
                  "PositionTicks": "1200000000"
                }
                """);

        ArgumentCaptor<EmbyWatchSession> captor = ArgumentCaptor.forClass(EmbyWatchSession.class);
        verify(watchSessionMapper).insertWatchSessionIfAbsent(captor.capture());

        EmbyWatchSession session = captor.getValue();
        assertThat(session.getItemName()).isEqualTo("Looked up episode");
        assertThat(session.getSeriesId()).isEqualTo("series-1");
        assertThat(session.getSeriesName()).isEqualTo("Looked up series");
        assertThat(session.getSeasonNumber()).isEqualTo(2);
        assertThat(session.getEpisodeNumber()).isEqualTo(9);
    }

    @Test
    void excludesPausedIntervalsAndDeletesActiveSessionAfterStop() {
        EmbyActivePlaybackSession activeSession = new EmbyActivePlaybackSession();
        activeSession.setEmbySessionId("session-1");
        activeSession.setEmbyUserId("user-1");
        activeSession.setItemId("item-1");
        activeSession.setItemType("Movie");
        activeSession.setItemName("Movie title");
        activeSession.setStartTime(LocalDateTime.parse("2026-07-06T10:00:00"));
        activeSession.setPlaybackStartTime(activeSession.getStartTime());
        activeSession.setAccumulatedWatchSeconds(0);
        when(activeSessionMapper.selectActiveSessionForUpdate(eq("session-1"), eq("item-1")))
                .thenReturn(activeSession);

        service.receivePlaybackEvent("secret", """
                {"Event":"playback.pause","Date":"2026-07-06T10:01:00+08:00","SessionId":"session-1","UserId":"user-1","ItemId":"item-1","ItemType":"Movie"}
                """);
        service.receivePlaybackEvent("secret", """
                {"Event":"playback.unpause","Date":"2026-07-06T10:10:00+08:00","SessionId":"session-1","UserId":"user-1","ItemId":"item-1","ItemType":"Movie"}
                """);
        service.receivePlaybackEvent("secret", """
                {"Event":"playback.stop","Date":"2026-07-06T10:11:00+08:00","SessionId":"session-1","UserId":"user-1","ItemId":"item-1","ItemType":"Movie"}
                """);

        ArgumentCaptor<EmbyWatchSession> captor = ArgumentCaptor.forClass(EmbyWatchSession.class);
        verify(watchSessionMapper).insertWatchSessionIfAbsent(captor.capture());
        assertThat(captor.getValue().getWatchSeconds()).isEqualTo(120);
        verify(activeSessionMapper).deleteActiveSession("session-1", "item-1");
    }
}
