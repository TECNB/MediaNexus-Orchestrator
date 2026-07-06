package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.medianexus.orchestrator.config.EmbyProperties;
import com.medianexus.orchestrator.config.AuthProperties;
import com.medianexus.orchestrator.mapper.EmbyActivePlaybackSessionMapper;
import com.medianexus.orchestrator.mapper.EmbyWatchSessionMapper;
import com.medianexus.orchestrator.model.User;
import com.medianexus.orchestrator.model.EmbyActivePlaybackSession;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class EmbyWatchRankingServiceTest {

    private final AuthService authService = new TestAuthService();
    private final EmbyProperties properties = new EmbyProperties();
    private final EmbyActivePlaybackSessionMapper activeSessionMapper = mock(EmbyActivePlaybackSessionMapper.class);
    private final EmbyWatchSessionMapper watchSessionMapper = mock(EmbyWatchSessionMapper.class);
    private final EmbyWatchRankingService service = new EmbyWatchRankingService(
            authService,
            properties,
            activeSessionMapper,
            watchSessionMapper
    );

    @BeforeEach
    void setUp() {
        when(watchSessionMapper.selectUserRankings(any(), any(), anyInt())).thenReturn(List.of());
        when(watchSessionMapper.selectMovieRankings(any(), any(), anyInt())).thenReturn(List.of());
        when(watchSessionMapper.selectSeriesRankings(any(), any(), anyInt())).thenReturn(List.of());
        when(watchSessionMapper.selectRecentWatchSessions(anyInt())).thenReturn(List.of());
        when(activeSessionMapper.countActiveSessions()).thenReturn(1L);
    }

    @Test
    void rendersEpisodePositionInRecentEventsWhenKnown() {
        properties.setWebhookSecret("secret");
        EmbyActivePlaybackSession session = new EmbyActivePlaybackSession();
        session.setItemType("Episode");
        session.setItemId("item-1");
        session.setSeriesName("Series title");
        session.setItemName("Episode title");
        session.setSeasonNumber(2);
        session.setEpisodeNumber(7);
        session.setStartTime(LocalDateTime.parse("2026-07-06T10:00:00"));
        when(activeSessionMapper.selectRecentActiveSessions(anyInt())).thenReturn(List.of(session));

        var response = service.getRankings("day", "2026-07-06", null, 20);

        assertThat(response.webhookStatus().recentEvents())
                .singleElement()
                .satisfies(event -> assertThat(event.itemName()).isEqualTo("Series title S02E07"));
    }

    private static class TestAuthService extends AuthService {

        private TestAuthService() {
            super(null, new AuthProperties(), mock(PasswordEncoder.class));
        }

        @Override
        public User requireAdminUser() {
            User user = new User();
            user.setId(1L);
            user.setRole("ADMIN");
            return user;
        }
    }
}
