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
import com.medianexus.orchestrator.service.EmbyPlaybackSnapshotCollector;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import com.medianexus.orchestrator.service.EmbyWatchTestDatabase;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;

class EmbyPlaybackSessionsTest {
    private EmbyWatchTestDatabase database;
    @BeforeEach void database() throws Exception { database = new EmbyWatchTestDatabase(); }
    private EmbyPlaybackSnapshotCollector collector(EmbyClient client) {
        return database.collector(client, (user,id) -> java.util.Optional.of(new EmbyItemMetadata(id,
                "ep".equals(id) ? "Episode" : "Movie", null, null, null, null, null, null,
                "ep".equals(id) ? "series" : "video".equals(id) ? "unknown" : "movie", null, null)));
    }

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
                      {"Id":"movie","UserId":"user","PlayState":{"IsPaused":false,"PositionTicks":10000000},"NowPlayingItem":{"Id":"film","Type":"Movie","RunTimeTicks":500000000}}
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
            var collector = collector(client);
            var controller = new AdminEmbyPlaybackController(collector, mock(AuthService.class));
            collector.collectSnapshot();
            var sessions = controller.getPlaybackSessions().data().sessions();
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
            collector.collectSnapshot();
            assertThat(detailReads).hasValue(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsNonAdminsBeforeReadingEmbyAndDoesNotReturnEmptyOnFailure() {
        AuthService auth = mock(AuthService.class);
        EmbyClient client = mock(EmbyClient.class);
        var collector = collector(client);
        AdminEmbyPlaybackController controller = new AdminEmbyPlaybackController(collector, auth);
        when(auth.requireAdminUser()).thenThrow(new BusinessException(ErrorCode.FORBIDDEN));
        assertThatThrownBy(controller::getPlaybackSessions).isInstanceOf(BusinessException.class);
        verifyNoInteractions(client);
        reset(auth);
        assertThatThrownBy(controller::getPlaybackSessions).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getHttpStatus().value()).isEqualTo(502));
        verifyNoInteractions(client);
        when(client.listPlaybackSessions()).thenThrow(new EmbyClientException("unavailable"));
        collector.collectSnapshot();
        assertThatThrownBy(controller::getPlaybackSessions).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getHttpStatus().value()).isEqualTo(502));
        doReturn(java.util.List.of()).when(client).listPlaybackSessions();
        collector.collectSnapshot();
        assertThat(controller.getPlaybackSessions().data().sessions()).isEmpty();
    }

    @Test
    void sharesSuccessfulSnapshotsAndRetainsThemAcrossHttpAndParsingFailures() throws Exception {
        var body = new AtomicReference<>("[]");
        var status = new AtomicInteger(503);
        var reads = new AtomicInteger();
        var metadataReads = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/Sessions", exchange -> {
            reads.incrementAndGet();
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/Items", exchange -> {
            metadataReads.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            var collector = collector(client(server));
            var firstPage = new AdminEmbyPlaybackController(collector, mock(AuthService.class));
            var secondPage = new AdminEmbyPlaybackController(collector, mock(AuthService.class));
            collector.collectSnapshot();
            assertThatThrownBy(firstPage::getPlaybackSessions).isInstanceOfSatisfying(BusinessException.class,
                    failure -> assertThat(failure.getHttpStatus().value()).isEqualTo(502));
            assertThat(collector.currentSnapshot().collectionStatus()).isEqualTo("unavailable");
            assertThat(collector.currentSnapshot().lastAttemptAt()).isNotNull();

            status.set(200);
            body.set("""
                    [{"Id":"session","UserId":"user","PlayState":{"IsPaused":true},
                      "NowPlayingItem":{"Id":"video","Type":"Video"}}]
                    """);
            collector.collectSnapshot();
            var successful = firstPage.getPlaybackSessions().data();
            assertThat(successful.collectionStatus()).isEqualTo("fresh");
            assertThat(successful.stale()).isFalse();
            assertThat(successful.collectionMessage()).isNull();
            assertThat(successful.collectedAt()).isAfterOrEqualTo(successful.lastAttemptAt());
            assertThat(successful.sessions()).singleElement().satisfies(session -> {
                assertThat(session.itemId()).isEqualTo("video");
                assertThat(session.paused()).isTrue();
                assertThat(session.title()).isEqualTo("分类待确认");
                assertThat(session.durationSeconds()).isNull();
            });
            for (int pageRead = 0; pageRead < 5; pageRead++) {
                assertThat(firstPage.getPlaybackSessions().data()).isEqualTo(successful);
                assertThat(secondPage.getPlaybackSessions().data()).isEqualTo(successful);
            }
            assertThat(reads).hasValue(2);
            assertThat(metadataReads).hasValue(1);

            status.set(401);
            collector.collectSnapshot();
            var failed = secondPage.getPlaybackSessions().data();
            assertThat(failed.stale()).isTrue();
            assertThat(failed.collectionStatus()).isEqualTo("stale");
            assertThat(failed.collectedAt()).isEqualTo(successful.collectedAt());
            assertThat(failed.sessions()).isEqualTo(successful.sessions());
            assertThat(failed.lastAttemptAt()).isAfterOrEqualTo(successful.lastAttemptAt());
            assertThat(failed.collectionMessage()).isEqualTo("实时观看采集失败，正在显示上次成功快照");

            status.set(200);
            for (String malformed : java.util.List.of("{}", "null", "[null]", "[{}]",
                    "[{\"Id\":23}]", "[{\"Id\":\"session\",\"NowPlayingItem\":[]} ]",
                    "[{\"Id\":\"session\",\"UserId\":\"user\",\"NowPlayingItem\":{\"Id\":\"video\"}}]",
                    "[{\"Id\":\"session\",\"UserId\":\"user\",\"NowPlayingItem\":{\"Type\":\"Video\"}}]",
                    "[{\"Id\":\"session\",\"NowPlayingItem\":{\"Id\":\"video\",\"Type\":\"Video\"}}]")) {
                body.set(malformed);
                collector.collectSnapshot();
                var retained = firstPage.getPlaybackSessions().data();
                assertThat(retained.collectionStatus()).as(malformed).isEqualTo("stale");
                assertThat(retained.sessions()).as(malformed).isEqualTo(successful.sessions());
                assertThat(retained.collectedAt()).as(malformed).isEqualTo(successful.collectedAt());
            }

            body.set("[]");
            collector.collectSnapshot();
            var empty = firstPage.getPlaybackSessions().data();
            assertThat(empty.sessions()).isEmpty();
            assertThat(empty.collectionStatus()).isEqualTo("fresh");
            assertThat(empty.stale()).isFalse();
            assertThat(empty.collectionMessage()).isNull();
            status.set(503);
            collector.collectSnapshot();
            assertThat(firstPage.getPlaybackSessions().data().sessions()).isEmpty();
            assertThat(firstPage.getPlaybackSessions().data().collectionStatus()).isEqualTo("stale");
            assertThat(firstPage.getPlaybackSessions().data().collectedAt()).isEqualTo(empty.collectedAt());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void collectsWithoutReadersAndWaitsAfterSlowRequestsWithoutOverlapOrCatchup() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondRequest = new CountDownLatch(1);
        var reads = new AtomicInteger();
        var firstResponseAt = new AtomicLong();
        var secondRequestAt = new AtomicLong();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var httpExecutor = Executors.newCachedThreadPool();
        var concurrentTrigger = Executors.newSingleThreadExecutor();
        server.setExecutor(httpExecutor);
        server.createContext("/Sessions", exchange -> {
            int request = reads.incrementAndGet();
            try {
                if (request == 1) {
                    entered.countDown();
                    if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("test response not released");
                    firstResponseAt.set(System.nanoTime());
                } else if (request == 2) {
                    secondRequestAt.set(System.nanoTime());
                    secondRequest.countDown();
                }
                byte[] bytes = "[]".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("collection-test",
                    Map.of("medianexus.emby.playback-collection-delay", "50")));
            context.register(SchedulingOnly.class);
            context.registerBean(EmbyClient.class, () -> client(server));
            context.registerBean(EmbyPlaybackSnapshotCollector.class, () -> collector(context.getBean(EmbyClient.class)));
            context.refresh();
            var collector = context.getBean(EmbyPlaybackSnapshotCollector.class);
            assertThat(reads).hasValue(0);
            collector.applicationReady();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            concurrentTrigger.submit(collector::collectSnapshot).get(1, TimeUnit.SECONDS);
            var page = new AdminEmbyPlaybackController(collector, mock(AuthService.class));
            assertThatThrownBy(page::getPlaybackSessions).isInstanceOf(BusinessException.class);
            assertThat(reads).hasValue(1);
            // 慢请求跨过多个周期；下一次应从响应完成后重新等待，而不是补发。
            assertThat(secondRequest.await(150, TimeUnit.MILLISECONDS)).isFalse();
            release.countDown();
            assertThat(secondRequest.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(secondRequestAt.get() - firstResponseAt.get()).isGreaterThanOrEqualTo(50_000_000L);
            assertThat(page.getPlaybackSessions().data().collectionStatus()).isEqualTo("fresh");
        } finally {
            release.countDown();
            concurrentTrigger.shutdownNow();
            server.stop(0);
            httpExecutor.shutdownNow();
        }
    }

    private static EmbyClient client(HttpServer server) {
        EmbyProperties properties = new EmbyProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setApiKey("test-token");
        properties.setTimeout(Duration.ofSeconds(2));
        return new EmbyClient(properties, new ObjectMapper());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class SchedulingOnly {}
}
