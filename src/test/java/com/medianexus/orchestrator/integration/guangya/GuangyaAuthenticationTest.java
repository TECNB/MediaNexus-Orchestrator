package com.medianexus.orchestrator.integration.guangya;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.GuangyaProperties;
import com.medianexus.orchestrator.service.GuangyaCredentials;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;

class GuangyaAuthenticationTest {
    private static final String HASH = "43519d14444904f6015e1a0d92018068fb0a49fa";
    private static final String MAGNET = "magnet:?xt=urn:btih:" + HASH;
    private final ObjectMapper json = new ObjectMapper();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();
    private final AtomicReference<GuangyaCredentials.Snapshot> stored = new AtomicReference<>(
            new GuangyaCredentials.Snapshot(1, "old-access", "old-refresh", "stable-device"));
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger peak = new AtomicInteger();
    private final GuangyaCredentials credentials = mock(GuangyaCredentials.class);
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private HttpServer server;
    private GuangyaClient client;
    private volatile boolean rejectFresh;
    private volatile boolean businessExpired;
    private volatile boolean rejectRefresh;
    private volatile boolean failPersistence;
    private volatile long delayMillis;
    private volatile CountDownLatch oldRequests;

    @BeforeEach void setup() throws Exception {
        when(credentials.snapshot()).thenAnswer(i -> stored.get());
        doAnswer(i -> {
            if (failPersistence) throw new IllegalStateException("simulated database outage");
            assertThat(stored.compareAndSet(i.getArgument(0), i.getArgument(1))).isTrue();
            return null;
        }).when(credentials).rotate(any(), any());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(workers);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(exchange.getRequestMethod() + " " + path);
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestBodies.add(body);
            int status = 200; String reply;
            if (path.equals("/v1/auth/token")) {
                assertThat(json.readTree(body)).isEqualTo(json.readTree("{\"client_id\":\"aMe-8VSlkrbQXpUR\",\"grant_type\":\"refresh_token\",\"refresh_token\":\"old-refresh\"}"));
                assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
                assertThat(exchange.getRequestHeaders().getFirst("Did")).isNull();
                if (rejectRefresh) { status = 401; reply = "{}"; }
                else reply = "{\"access_token\":\"fresh-access\",\"refresh_token\":\"fresh-refresh\"}";
            } else {
                assertThat(path).isEqualTo("/cloudcollection/v1/resolve_res");
                assertThat(exchange.getRequestHeaders().getFirst("Did")).isEqualTo("stable-device");
                assertThat(exchange.getRequestHeaders().getFirst("Dt")).isEqualTo("4");
                boolean old = "Bearer old-access".equals(exchange.getRequestHeaders().getFirst("Authorization"));
                int running = active.incrementAndGet(); peak.accumulateAndGet(running, Math::max);
                try {
                    if (old && oldRequests != null) { oldRequests.countDown(); oldRequests.await(2, TimeUnit.SECONDS); }
                    if (delayMillis > 0) Thread.sleep(delayMillis);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { active.decrementAndGet(); }
                if (old || rejectFresh) {
                    status = businessExpired ? 200 : 401;
                    reply = businessExpired ? "{\"code\":117}" : "{}";
                } else {
                    String hash = json.readTree(body).path("url").asText().substring("magnet:?xt=urn:btih:".length());
                    reply = "{\"msg\":\"success\",\"data\":{\"resType\":1,\"btResInfo\":{\"infoHash\":\"" + hash + "\",\"fileName\":\"E01.mkv\",\"fileSize\":1}}}";
                }
            }
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            try { exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); }
            finally { exchange.close(); }
        });
        server.start();
        GuangyaProperties p = new GuangyaProperties();
        p.setApiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort()); p.setAuthBaseUrl(p.getApiBaseUrl());
        p.setMaxResponseBytes(100000); p.setMaxNodes(10000); p.setMaxDepth(32);
        p.setCacheMaxEntries(100); p.setCacheTtl(Duration.ofHours(1));
        client = new GuangyaClient(p, credentials, json);
    }
    @AfterEach void stop() { server.stop(0); workers.shutdownNow(); }

    @Test void freshRoundCanRecoverAfterTemporaryRefreshFailure() {
        rejectRefresh = true;
        assertThatThrownBy(() -> client.resolve(MAGNET, ResolveBudget.start())).isInstanceOf(GuangyaException.class);
        rejectRefresh = false;
        assertThat(client.resolve(MAGNET, ResolveBudget.start()).files()).hasSize(1);
        assertThat(requests.stream().filter(path -> path.endsWith("/v1/auth/token")).count()).isEqualTo(2);
    }
    @Test void refreshesOnceRotatesPairAndCountsResolveRetry() {
        ResolveBudget budget = ResolveBudget.start();
        assertThat(client.resolve(MAGNET, budget).files()).hasSize(1);
        assertThat(stored.get().version()).isEqualTo(2);
        assertThat(stored.get().refreshToken()).isEqualTo("fresh-refresh");
        assertThat(stored.get().deviceId()).isEqualTo("stable-device");
        assertThat(budget.resolveRequests()).isEqualTo(2);
        assertThat(budget.authenticationRefreshUsed()).isTrue();
        assertThat(requests).containsExactly("POST /cloudcollection/v1/resolve_res", "POST /v1/auth/token", "POST /cloudcollection/v1/resolve_res");
    }
    @Test void recognizesVerifiedBusinessExpiryCodeAndDoesNotLoopAfterRetry() {
        businessExpired = true; rejectFresh = true;
        ResolveBudget budget = ResolveBudget.start();
        assertThatThrownBy(() -> client.resolve(MAGNET, budget)).isInstanceOfSatisfying(GuangyaException.class,
                e -> assertThat(e.reason()).isEqualTo(GuangyaException.Reason.AUTHENTICATION));
        assertThat(requests).hasSize(3);
        assertThat(budget.resolveRequests()).isEqualTo(2);
        assertThat(client.cached(MAGNET)).isEmpty();
    }
    @Test void simultaneousExpirySharesOneRefreshAndAtMostTwoResolveRequests() throws Exception {
        oldRequests = new CountDownLatch(2);
        ResolveBudget budget = ResolveBudget.start();
        var first = workers.submit(() -> client.resolve(MAGNET, budget));
        var second = workers.submit(() -> client.resolve("magnet:?xt=urn:btih:0000000000000000000000000000000000000001", budget));
        assertThat(first.get(5, TimeUnit.SECONDS).files()).hasSize(1);
        assertThat(second.get(5, TimeUnit.SECONDS).files()).hasSize(1);
        verify(credentials, times(1)).rotate(any(), any());
        assertThat(requests.stream().filter(s -> s.endsWith("/v1/auth/token")).count()).isEqualTo(1);
        assertThat(budget.resolveRequests()).isEqualTo(4);
        assertThat(peak.get()).isLessThanOrEqualTo(2);
    }
    @Test void persistenceFailureIsExplicitAndStopsNewHttpWithoutDiscardingCachedEvidence() {
        failPersistence = true;
        assertThatThrownBy(() -> client.resolve(MAGNET, ResolveBudget.start())).isInstanceOfSatisfying(GuangyaException.class,
                e -> assertThat(e.reason()).isEqualTo(GuangyaException.Reason.PERSISTENCE_FAILED));
        int sent = requests.size();
        assertThatThrownBy(() -> client.resolve(MAGNET, ResolveBudget.start())).isInstanceOf(GuangyaException.class);
        assertThat(requests).hasSize(sent);
        assertThat(stored.get().accessToken()).isEqualTo("old-access");
    }
    @Test void failedRefreshDoesNotRetryResolveOrExposeSecrets() {
        rejectRefresh = true;
        assertThatThrownBy(() -> client.resolve(MAGNET, ResolveBudget.start())).isInstanceOf(GuangyaException.class)
                .hasMessageNotContaining("old-refresh");
        assertThat(requests).containsExactly("POST /cloudcollection/v1/resolve_res", "POST /v1/auth/token");
    }
    @Test void twentiethResolveCannotBypassCapThroughAuthenticationRetry() {
        ResolveBudget budget = ResolveBudget.start();
        for (int i = 0; i < 19; i++) budget.countResolveRequest();
        assertThatThrownBy(() -> client.resolve(MAGNET, budget)).isInstanceOfSatisfying(GuangyaException.class,
                e -> assertThat(e.reason()).isEqualTo(GuangyaException.Reason.BUDGET_EXHAUSTED));
        assertThat(budget.resolveRequests()).isEqualTo(20);
        assertThat(requests).containsExactly("POST /cloudcollection/v1/resolve_res", "POST /v1/auth/token");
    }
    @Test void deadlineIncludesWaitingAndDoesNotRefreshAfterSlowResolve() {
        delayMillis = 500;
        Instant start = Instant.now();
        assertThatThrownBy(() -> client.resolve(MAGNET, new ResolveBudget(start.plusMillis(100), start.plusSeconds(30))))
                .isInstanceOf(GuangyaException.class);
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(2));
        assertThat(requests).containsExactly("POST /cloudcollection/v1/resolve_res");
    }
    @Test void differentRoundJoiningSharedRefreshAlsoConsumesItsRefreshAllowance() throws Exception {
        oldRequests = new CountDownLatch(2);
        ResolveBudget firstBudget = ResolveBudget.start(), secondBudget = ResolveBudget.start();
        var first = workers.submit(() -> client.resolve(MAGNET, firstBudget));
        var second = workers.submit(() -> client.resolve("magnet:?xt=urn:btih:0000000000000000000000000000000000000001", secondBudget));
        first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
        assertThat(firstBudget.authenticationRefreshUsed()).isTrue();
        assertThat(secondBudget.authenticationRefreshUsed()).isTrue();
    }
    @Test void sixParallelCandidatesStillUseOnlyTwoNetworkSlots() throws Exception {
        stored.set(new GuangyaCredentials.Snapshot(2, "fresh-access", "fresh-refresh", "stable-device"));
        delayMillis = 50;
        ResolveBudget budget = ResolveBudget.start();
        var tasks = new java.util.ArrayList<Future<TorrentFileTable>>();
        for (int i = 1; i <= 6; i++) {
            String magnet = "magnet:?xt=urn:btih:" + String.format("%040x", i);
            tasks.add(workers.submit(() -> client.resolve(magnet, budget)));
        }
        for (var task : tasks) assertThat(task.get(3, TimeUnit.SECONDS).files()).hasSize(1);
        assertThat(peak.get()).isEqualTo(2);
        assertThat(budget.resolveRequests()).isEqualTo(6);
        assertThat(requests).allMatch(s -> s.equals("POST /cloudcollection/v1/resolve_res"));
    }

    @Test void slowRequestHasEightSecondCapAndIsNeverRetried() {
        delayMillis = 10000;
        Instant start = Instant.now();
        assertThatThrownBy(() -> client.resolve(MAGNET, ResolveBudget.start())).isInstanceOf(GuangyaException.class);
        assertThat(Duration.between(start, Instant.now())).isBetween(Duration.ofSeconds(7), Duration.ofSeconds(10));
        assertThat(requests).containsExactly("POST /cloudcollection/v1/resolve_res");
    }

    @Test void authenticationStatusIsRecognizedEvenWhenGatewayReturnsNonJson() {
        server.createContext("/cloudcollection/v1/resolve_res", exchange -> {
            requests.add("POST /cloudcollection/v1/resolve_res");
            byte[] bytes = "login required".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        assertThatThrownBy(() -> client.resolve(MAGNET, ResolveBudget.start())).isInstanceOfSatisfying(GuangyaException.class,
                e -> assertThat(e.reason()).isEqualTo(GuangyaException.Reason.AUTHENTICATION));
        assertThat(requests).containsExactly("POST /cloudcollection/v1/resolve_res", "POST /v1/auth/token", "POST /cloudcollection/v1/resolve_res");
    }

    @Test void deadlineCoversSlowBodyAfterHeadersAndCancelsWithoutRetry() {
        server.createContext("/cloudcollection/v1/resolve_res", exchange -> {
            requests.add("POST /cloudcollection/v1/resolve_res");
            exchange.sendResponseHeaders(200, 100);
            exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
            try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        Instant start = Instant.now();
        assertThatThrownBy(() -> client.resolve(MAGNET, new ResolveBudget(start.plusMillis(150), start.plusSeconds(30))))
                .isInstanceOf(GuangyaException.class);
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofMillis(800));
        assertThat(requests).containsExactly("POST /cloudcollection/v1/resolve_res");
    }
}
