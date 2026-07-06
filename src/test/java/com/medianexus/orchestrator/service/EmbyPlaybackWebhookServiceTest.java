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
    void doesNotLookupMovieMetadata() {
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

        verify(itemMetadataLookup, org.mockito.Mockito.never()).findItemMetadata(any(), any());
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
}
