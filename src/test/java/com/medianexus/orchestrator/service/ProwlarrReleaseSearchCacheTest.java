package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.base.Ticker;
import com.medianexus.orchestrator.config.ProwlarrProperties;
import com.medianexus.orchestrator.integration.prowlarr.ProwlarrClient;
import com.medianexus.orchestrator.integration.prowlarr.ProwlarrClientException;
import com.medianexus.orchestrator.integration.prowlarr.ProwlarrRelease;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class ProwlarrReleaseSearchCacheTest {

    @Test
    void springConstructsCacheWithTheProductionDependencies() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ProwlarrClient.class, () -> mock(ProwlarrClient.class));
            context.registerBean(ProwlarrProperties.class, ProwlarrReleaseSearchCacheTest::properties);
            context.register(ProwlarrReleaseSearchCache.class);
            context.refresh();

            assertThat(context.getBean(ProwlarrReleaseSearchCache.class)).isNotNull();
        }
    }

    @Test
    void cachesSuccessfulEmptyResultsUntilWriteTtlExpires() {
        ProwlarrClient client = mock(ProwlarrClient.class);
        when(client.search("missing title")).thenReturn(List.of());
        MutableTicker ticker = new MutableTicker();
        ProwlarrReleaseSearchCache cache = new ProwlarrReleaseSearchCache(client, properties(), ticker);

        assertThat(cache.search("missing title")).isEmpty();
        ticker.advance(Duration.ofMinutes(29));
        assertThat(cache.search("missing title")).isEmpty();
        verify(client, times(1)).search("missing title");

        ticker.advance(Duration.ofMinutes(2));
        assertThat(cache.search("missing title")).isEmpty();
        verify(client, times(2)).search("missing title");
    }

    @Test
    void coalescesConcurrentMissesForTheSameQuery() throws Exception {
        ProwlarrClient client = mock(ProwlarrClient.class);
        CountDownLatch upstreamEntered = new CountDownLatch(1);
        CountDownLatch allowUpstreamResponse = new CountDownLatch(1);
        ProwlarrRelease release = release("Concurrent.Title.1080p");
        when(client.search("concurrent title")).thenAnswer(invocation -> {
            upstreamEntered.countDown();
            allowUpstreamResponse.await(1, TimeUnit.SECONDS);
            return List.of(release);
        });
        ProwlarrReleaseSearchCache cache =
                new ProwlarrReleaseSearchCache(client, properties(), new MutableTicker());
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<List<ProwlarrRelease>> first = executor.submit(() -> cache.search("concurrent title"));
            Future<List<ProwlarrRelease>> second = executor.submit(() -> cache.search("concurrent title"));
            assertThat(upstreamEntered.await(1, TimeUnit.SECONDS)).isTrue();
            allowUpstreamResponse.countDown();

            assertThat(first.get(1, TimeUnit.SECONDS)).containsExactly(release);
            assertThat(second.get(1, TimeUnit.SECONDS)).containsExactly(release);
            verify(client, times(1)).search("concurrent title");
        } finally {
            allowUpstreamResponse.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void doesNotRetainEntriesHeavierThanTheConfiguredBudget() {
        ProwlarrClient client = mock(ProwlarrClient.class);
        when(client.search("large result")).thenReturn(List.of(release("Large.Title.2160p")));
        ProwlarrProperties properties = properties();
        properties.setSearchCacheMaxWeightBytes(1);
        ProwlarrReleaseSearchCache cache =
                new ProwlarrReleaseSearchCache(client, properties, new MutableTicker());

        cache.search("large result");
        cache.search("large result");

        verify(client, times(2)).search("large result");
    }

    @Test
    void doesNotCacheSearchExceptions() {
        ProwlarrClient client = mock(ProwlarrClient.class);
        ProwlarrClientException failure = new ProwlarrClientException(
                ProwlarrClientException.Reason.UPSTREAM,
                "temporary failure"
        );
        when(client.search("unstable title")).thenThrow(failure).thenReturn(List.of());
        ProwlarrReleaseSearchCache cache =
                new ProwlarrReleaseSearchCache(client, properties(), new MutableTicker());

        assertThatThrownBy(() -> cache.search("unstable title")).isSameAs(failure);
        assertThat(cache.search("unstable title")).isEmpty();
        verify(client, times(2)).search("unstable title");
    }

    private static ProwlarrProperties properties() {
        ProwlarrProperties properties = new ProwlarrProperties();
        properties.setSearchCacheTtl(Duration.ofMinutes(30));
        properties.setSearchCacheMaxWeightBytes(32L * 1024 * 1024);
        return properties;
    }

    private static ProwlarrRelease release(String title) {
        return new ProwlarrRelease(
                title,
                10_000_000_000L,
                10,
                1,
                5,
                "Test Indexer",
                "2026-07-18T00:00:00Z",
                1,
                "guid-" + title,
                "download-ref-" + title
        );
    }

    private static class MutableTicker extends Ticker {
        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long read() {
            return nanos.get();
        }

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }
}
