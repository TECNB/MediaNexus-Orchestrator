package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.dto.emby.response.EmbyWatchDetails;
import com.medianexus.orchestrator.integration.emby.EmbyClient;
import com.medianexus.orchestrator.integration.emby.EmbyClientException;
import com.medianexus.orchestrator.integration.emby.EmbyPlaybackSession;
import com.medianexus.orchestrator.mapper.EmbyWatchDetailsMapper;
import com.medianexus.orchestrator.model.EmbyActivePlaybackSession;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class EmbyWatchDetailsQueryTest {
    private final AuthService auth = mock(AuthService.class);
    private final EmbyWatchDetailsMapper mapper = mock(EmbyWatchDetailsMapper.class);
    private final EmbyClient emby = mock(EmbyClient.class);
    private final EmbyWatchDetailsQuery query = new EmbyWatchDetailsQuery(auth, mapper, emby, new ObjectMapper());

    @Test
    void ordinaryUserCannotReadAnyWatchDetails() {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN, "仅管理员可查看")).when(auth).requireAdminUser();
        assertThatThrownBy(() -> query.users("day", "2026-10-28", null, 1, 20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> query.userWorks("user", "day", "2026-10-28", null, 1, 20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> query.sessions("user", null, null, "day", "2026-10-28", null, 1, 20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> query.media("adult", "duration", "day", "2026-10-28", null, 1, 20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> query.viewers("item", "adult", "day", "2026-10-28", null, 1, 20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> query.activity("day", "2026-10-28", null, 1, 20)).isInstanceOf(BusinessException.class);
    }

    @Test
    void failedLiveVerificationFreezesPrivateCrossDayEstimateAtLastKnownTime() {
        EmbyActivePlaybackSession active = tracked("adult");
        active.setStartTime(LocalDateTime.parse("2026-10-28T23:40:00"));
        active.setPlaybackIntervalsJson("[{\"start\":\"2026-10-28T23:40:00\",\"end\":\"2026-10-28T23:50:00\"}]");
        active.setPlaybackStartTime(LocalDateTime.parse("2026-10-29T00:10:00"));
        active.setUpdatedAt(LocalDateTime.parse("2026-10-29T00:20:00"));
        when(mapper.selectTrackedSessions(any(), isNull())).thenReturn(List.of(active));
        when(emby.listPlaybackSessions()).thenThrow(new EmbyClientException("unavailable"));

        EmbyWatchDetails.Session session = query.activity("day", "2026-10-29", null, 1, 20).items().get(0);
        assertThat(session.title()).isEqualTo("私密观看");
        assertThat(session.watchSeconds()).isEqualTo(1200);
        assertThat(session.rangeWatchSeconds()).isEqualTo(600);
        assertThat(session.state()).isEqualTo("unverified");
        assertThat(session.observedAt()).isEqualTo(LocalDateTime.parse("2026-10-29T00:20:00"));
        assertThat(session.stopTime()).isNull();
    }

    @Test
    void pausedPlaybackKeepsThirtyFiveMinutesAcrossOneHourSpanAndHidesUnknownTitle() {
        EmbyActivePlaybackSession active = tracked(null);
        active.setStartTime(LocalDateTime.parse("2026-10-28T20:00:00"));
        active.setPlaybackIntervalsJson("[{\"start\":\"2026-10-28T20:00:00\",\"end\":\"2026-10-28T20:20:00\"},{\"start\":\"2026-10-28T20:45:00\",\"end\":\"2026-10-28T21:00:00\"}]");
        active.setPlaybackStartTime(null);
        active.setUpdatedAt(LocalDateTime.parse("2026-10-28T21:00:00"));
        when(mapper.selectTrackedSessions(any(), isNull())).thenReturn(List.of(active));
        when(emby.listPlaybackSessions()).thenReturn(List.of(new EmbyPlaybackSession("session", "user", "name", "client", "device", true,
                "item", "Movie", "must not leak", null, null, null, null, 100.0, 7200.0, List.of())));

        EmbyWatchDetails.Session session = query.activity("day", "2026-10-28", null, 1, 20).items().get(0);
        assertThat(session.title()).isEqualTo("分类待确认");
        assertThat(session.rangeWatchSeconds()).isEqualTo(2100);
        assertThat(session.state()).isEqualTo("paused");
        assertThat(session.startTime()).isEqualTo(LocalDateTime.parse("2026-10-28T20:00:00"));
        assertThat(session.stopTime()).isNull();
    }

    @Test
    void liveVerificationFailureKeepsPreviouslyObservedEstimate() {
        EmbyActivePlaybackSession active = tracked("movie");
        LocalDateTime started = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).minusMinutes(30).withNano(0);
        active.setStartTime(started);
        active.setPlaybackStartTime(started);
        active.setPlaybackIntervalsJson("[]");
        active.setUpdatedAt(started);
        when(mapper.selectTrackedSessions(any(), isNull())).thenReturn(List.of(active));
        when(emby.listPlaybackSessions()).thenReturn(List.of(new EmbyPlaybackSession("session", "user", "name", "client", "device", false,
                "item", "Movie", "movie", null, null, null, null, 100.0, 7200.0, List.of())))
                .thenThrow(new EmbyClientException("unavailable"));

        doAnswer(invocation -> {
            active.setLastVerifiedAt(invocation.getArgument(1));
            return null;
        }).when(mapper).recordVerifiedPlayback(org.mockito.ArgumentMatchers.eq(active.getId()), any());
        String date = started.toLocalDate().toString();
        EmbyWatchDetails.Session verified = query.activity("day", date, null, 1, 20).items().get(0);
        EmbyWatchDetails.Session frozen = query.activity("day", date, null, 1, 20).items().get(0);
        assertThat(verified.watchSeconds()).isGreaterThanOrEqualTo(1800);
        assertThat(frozen.watchSeconds()).isEqualTo(verified.watchSeconds());
        assertThat(frozen.observedAt()).isEqualTo(verified.observedAt());
        assertThat(frozen.state()).isEqualTo("unverified");
        assertThat(frozen.stopTime()).isNull();
    }

    @Test
    void invalidDateAndSortAreRejectedInsteadOfAppearingEmpty() {
        assertThatThrownBy(() -> query.users("day", "2026-02-30", null, 1, 20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> query.media("movie", "invalid", "day", "2026-10-28", null, 1, 20)).isInstanceOf(BusinessException.class);
    }

    private EmbyActivePlaybackSession tracked(String category) {
        EmbyActivePlaybackSession active = new EmbyActivePlaybackSession();
        active.setId(1L);
        active.setEmbySessionId("session");
        active.setEmbyUserId("user");
        active.setEmbyUserName("name");
        active.setItemId("item");
        active.setItemName("must not leak");
        active.setWatchCategory(category);
        return active;
    }
}
