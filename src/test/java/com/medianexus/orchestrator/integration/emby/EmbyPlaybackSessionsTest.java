package com.medianexus.orchestrator.integration.emby;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.EmbyProperties;
import com.medianexus.orchestrator.controller.AdminEmbyPlaybackController;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.service.AuthService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EmbyPlaybackSessionsTest {
    @Test
    void preservesIndependentPausedSessionsAndMatchesOnlyTheCurrentVersion() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger detailReads = new AtomicInteger();
        server.createContext("/Sessions", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            assertThat(exchange.getRequestHeaders().getFirst("X-Emby-Token")).isEqualTo("test-token");
            byte[] body = """
                    [
                      {"Id":"idle"},
                      {"Id":"music","NowPlayingItem":{"Id":"audio","Type":"Audio"}},
                      {"Id":"tv","UserId":"user","PlayState":{"IsPaused":true,"PositionTicks":0,"MediaSourceId":"version-b"},"NowPlayingItem":{"Id":"ep","Type":"Episode"}},
                      {"Id":"phone","UserId":"user","PlayState":{"IsPaused":false,"MediaSourceId":"missing"},"NowPlayingItem":{"Id":"ep","Type":"Episode","RunTimeTicks":990000000}},
                      {"Id":"movie","PlayState":{"IsPaused":false,"PositionTicks":10000000},"NowPlayingItem":{"Id":"film","Type":"Movie","RunTimeTicks":500000000}}
                    ]
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/Items", exchange -> {
            detailReads.incrementAndGet();
            byte[] body = """
                    {"Items":[{"Id":"ep","Name":"Episode","SeriesName":"Series","SeriesId":"series","SeasonId":"season","ParentIndexNumber":0,"IndexNumber":2,"IndexNumberEnd":3,"RunTimeTicks":990000000,"MediaSources":[{"Id":"version-a","RunTimeTicks":100000000},{"Id":"version-b","RunTimeTicks":200000000}]}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            EmbyProperties properties = new EmbyProperties();
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setApiKey("test-token");
            properties.setTimeout(Duration.ofSeconds(2));
            EmbyClient client = new EmbyClient(properties, new ObjectMapper());
            var sessions = client.listPlaybackSessions();
            assertThat(sessions).hasSize(3);
            assertThat(sessions.get(0).paused()).isTrue();
            assertThat(sessions.get(0).positionSeconds()).isZero();
            assertThat(sessions.get(0).durationSeconds()).isEqualTo(20.0);
            assertThat(sessions.get(0).seasonNumber()).isZero();
            assertThat(sessions.get(0).episodeNumberEnd()).isEqualTo(3);
            assertThat(sessions.get(0).posters()).extracting(EmbyPlaybackSession.Poster::itemId).containsExactly("series", "season", "ep");
            assertThat(sessions.get(1).durationSeconds()).isNull();
            assertThat(sessions.get(1).positionSeconds()).isNull();
            assertThat(sessions.get(2).durationSeconds()).isEqualTo(50.0);
            client.listPlaybackSessions();
            assertThat(detailReads).hasValue(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsNonAdminsBeforeReadingEmbyAndDoesNotReturnEmptyOnFailure() {
        AuthService auth = mock(AuthService.class);
        EmbyClient client = mock(EmbyClient.class);
        AdminEmbyPlaybackController controller = new AdminEmbyPlaybackController(client, auth);
        when(auth.requireAdminUser()).thenThrow(new BusinessException(ErrorCode.FORBIDDEN));
        assertThatThrownBy(controller::getPlaybackSessions).isInstanceOf(BusinessException.class);
        verifyNoInteractions(client);
        reset(auth);
        when(client.listPlaybackSessions()).thenThrow(new EmbyClientException("unavailable"));
        assertThatThrownBy(controller::getPlaybackSessions).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getHttpStatus().value()).isEqualTo(502));
        doReturn(java.util.List.of()).when(client).listPlaybackSessions();
        assertThat(controller.getPlaybackSessions().data().sessions()).isEmpty();
    }
}
