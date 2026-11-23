package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.integration.openlist.OpenListClient;
import com.medianexus.orchestrator.integration.openlist.OpenListOfflineTaskInfo;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class OfflineIngestSchedulerTest {
    @Test
    void blockedFirstSubmissionDoesNotBlockSecondDistinctDirectory() throws Exception {
        OpenListClient client = mappedClient();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        try (OfflineIngestScheduler scheduler = new OfflineIngestScheduler(client)) {
            scheduler.enqueue("/one", "hash-one", Duration.ofSeconds(5), Duration.ofMillis(10), new DownloadStub() {
                @Override public String submit() {
                    firstEntered.countDown();
                    await(releaseFirst);
                    return "one";
                }
            });
            assertThat(firstEntered.await(2, TimeUnit.SECONDS)).isTrue();
            scheduler.enqueue("/two", "hash-two", Duration.ofSeconds(5), Duration.ofMillis(10), new DownloadStub() {
                @Override public String submit() { secondEntered.countDown(); return "two"; }
            });
            assertThat(secondEntered.await(2, TimeUnit.SECONDS)).isTrue();
            releaseFirst.countDown();
        } finally { releaseFirst.countDown(); }
    }

    @Test
    void blockedOrganizationReleasesDownloadSlotButKeepsDirectoryAndHashClaims() throws Exception {
        OpenListClient client = mappedClient();
        when(client.offlineTaskInfo("first")).thenReturn(snapshot("first", 2));
        when(client.offlineTaskInfo("second")).thenReturn(snapshot("second", 1));
        when(client.offlineTaskInfo("third")).thenReturn(snapshot("third", 1));
        CountDownLatch organizing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch second = new CountDownLatch(1);
        CountDownLatch third = new CountDownLatch(1);
        CountDownLatch sameDirectory = new CountDownLatch(1);
        CountDownLatch sameHash = new CountDownLatch(1);
        CountDownLatch fourth = new CountDownLatch(1);
        try (OfflineIngestScheduler scheduler = new OfflineIngestScheduler(client)) {
            scheduler.enqueue("/first", "a", Duration.ofSeconds(5), Duration.ofMillis(5), new DownloadStub() {
                public String submit() { return "first"; }
                public void organize(String id) { organizing.countDown(); await(release); }
            });
            assertThat(organizing.await(2, TimeUnit.SECONDS)).isTrue();
            scheduler.enqueue("/first/season", "b", Duration.ofSeconds(5), Duration.ofMillis(5), submission("blocked", sameDirectory));
            scheduler.enqueue("/alias", "a", Duration.ofSeconds(5), Duration.ofMillis(5), submission("blocked", sameHash));
            scheduler.enqueue("/second", "c", Duration.ofSeconds(5), Duration.ofMillis(5), submission("second", second));
            scheduler.enqueue("/third", "d", Duration.ofSeconds(5), Duration.ofMillis(5), submission("third", third));
            scheduler.enqueue("/fourth", "e", Duration.ofSeconds(5), Duration.ofMillis(5), submission("fourth", fourth));
            assertThat(second.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(third.await(2, TimeUnit.SECONDS)).isTrue();
            verify(client, timeout(2000).atLeast(2)).offlineTaskInfo("second");
            verify(client, timeout(2000).atLeast(2)).offlineTaskInfo("third");
            assertThat(sameDirectory.getCount()).isEqualTo(1);
            assertThat(sameHash.getCount()).isEqualTo(1);
            assertThat(fourth.getCount()).isEqualTo(1);
            release.countDown();
        } finally { release.countDown(); }
    }

    @Test
    void accountRootAllowsTwoDownloadsButOrganizationsRemainSerial() throws Exception {
        var client = mappedClient();
        var submitted = new CountDownLatch(2);
        var organizing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var active = new java.util.concurrent.atomic.AtomicInteger();
        var maximum = new java.util.concurrent.atomic.AtomicInteger();
        var completed = new java.util.concurrent.atomic.AtomicInteger();
        when(client.offlineTaskInfo(anyString())).thenReturn(snapshot("finished", 2));
        try (var scheduler = new OfflineIngestScheduler(client)) {
            java.util.function.Consumer<String> organize = ignored -> {
                maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                organizing.countDown(); await(release);
                completed.incrementAndGet(); active.decrementAndGet();
            };
            var first = scheduler.enqueue("/one", "one-hash", Duration.ofSeconds(5), Duration.ofMillis(5), new DownloadStub() {
                public String submit() { submitted.countDown(); await(submitted); return "one"; }
                public void organize(String id) { organize.accept(id); }
            });
            var second = scheduler.enqueue("/two", "two-hash", Duration.ofSeconds(5), Duration.ofMillis(5), new DownloadStub() {
                public String submit() { submitted.countDown(); await(submitted); return "two"; }
                public void organize(String id) { organize.accept(id); }
            });
            assertThat(submitted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(organizing.await(2, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(50);
            assertThat(active.get()).isEqualTo(1);
            release.countDown(); first.get(2, TimeUnit.SECONDS); second.get(2, TimeUnit.SECONDS);
            assertThat(maximum.get()).isEqualTo(1); assertThat(completed.get()).isEqualTo(2);
        } finally { release.countDown(); }
    }

    @Test
    void periodicQueriesNeverOverlapAndOnlyConfirmedCompletionOrganizes() throws Exception {
        OpenListClient client = mappedClient();
        java.util.concurrent.atomic.AtomicInteger active = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger maximum = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger queries = new java.util.concurrent.atomic.AtomicInteger();
        when(client.offlineTaskInfo("upstream")).thenAnswer(call -> {
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            Thread.sleep(20);
            int count = queries.incrementAndGet();
            active.decrementAndGet();
            return snapshot("upstream", count < 4 ? 5 : 2);
        });
        CountDownLatch organized = new CountDownLatch(1);
        try (OfflineIngestScheduler scheduler = new OfflineIngestScheduler(client)) {
            scheduler.enqueue("/one", "a", Duration.ofSeconds(5), Duration.ofMillis(2), new DownloadStub() {
                public void organize(String id) { assertThat(queries.get()).isEqualTo(4); organized.countDown(); }
            }).get(2, TimeUnit.SECONDS);
            assertThat(organized.getCount()).isZero();
            assertThat(maximum.get()).isEqualTo(1);
            verify(client, never()).retryOfflineTask(anyString());
        }
    }

    @Test
    void unknownSubmissionAndUnconfirmedCancellationNeverReplayOrFreeClaims() throws Exception {
        OpenListClient client = mappedClient();
        when(client.offlineTaskInfo("canceling")).thenReturn(snapshot("canceling", 3));
        CountDownLatch unknown = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        CountDownLatch third = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        try (OfflineIngestScheduler scheduler = new OfflineIngestScheduler(client)) {
            scheduler.enqueue("/unknown", "unknown", Duration.ofMillis(30), Duration.ofMillis(5), new DownloadStub() {
                public String submit() { attempts.incrementAndGet(); throw new IllegalStateException("response lost"); }
                public void failed(RuntimeException failure) { unknown.countDown(); }
            });
            scheduler.enqueue("/canceling", "canceling", Duration.ofMillis(30), Duration.ofMillis(5), new DownloadStub() {
                public String submit() { return "canceling"; }
                public void organize(String id) { throw new AssertionError("unconfirmed download must not organize"); }
                public void failed(RuntimeException failure) { cancelled.countDown(); }
            });
            assertThat(unknown.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(cancelled.await(2, TimeUnit.SECONDS)).isTrue();
            scheduler.enqueue("/third", "third", Duration.ofSeconds(5), Duration.ofMillis(5), submission("third", third));
            assertThat(third.await(100, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(attempts.get()).isEqualTo(1);
            verify(client, times(1)).cancelOfflineTask("canceling");
            verify(client, never()).deleteOfflineTask(anyString());
            verify(client, never()).remove(anyString(), any());
        }
    }

    @Test
    void preparationFailureDoesNotStallOtherJobsAndStartupUnknownFailsClosed() throws Exception {
        OpenListClient client = mappedClient();
        CountDownLatch second = new CountDownLatch(1);
        try (OfflineIngestScheduler scheduler = new OfflineIngestScheduler(client)) {
            scheduler.enqueue("/first", "a", Duration.ofSeconds(5), Duration.ofMillis(5), new DownloadStub() {
                public void prepare() { throw new IllegalStateException("bad directory"); }
            });
            scheduler.enqueue("/second", "b", Duration.ofSeconds(5), Duration.ofMillis(5), submission("second", second));
            assertThat(second.await(2, TimeUnit.SECONDS)).isTrue();
        }
        when(client.unfinishedOfflineTaskCount()).thenReturn(1);
        CountDownLatch submitted = new CountDownLatch(1);
        try (OfflineIngestScheduler scheduler = new OfflineIngestScheduler(client)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> scheduler.enqueue("/third", "c",
                    Duration.ofSeconds(5), Duration.ofMillis(5), submission("third", submitted)).get(2, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(com.medianexus.orchestrator.integration.openlist.OpenListClientException.class);
            assertThat(submitted.getCount()).isEqualTo(1);
        }
    }

    @Test
    void mountAliasesAndUnresolvedMappingsCannotBypassLifecycleExclusion() throws Exception {
        OpenListClient client = mappedClient();
        when(client.offlineDirectoryForPath("/mount-a/show")).thenReturn(new OpenListClient.OfflineDirectory("same", "root", "/show"));
        when(client.offlineDirectoryForPath("/mount-b/show")).thenReturn(new OpenListClient.OfflineDirectory("same", "root", "/show"));
        when(client.offlineDirectoryForPath("/unknown")).thenReturn(new OpenListClient.OfflineDirectory("unknown", "", "/"));
        when(client.offlineDirectoryForPath("/different-root")).thenReturn(new OpenListClient.OfflineDirectory("same", "another-root", "/other"));
        when(client.offlineDirectoryForPath("/blank-root")).thenReturn(new OpenListClient.OfflineDirectory("same", "", "/other"));
        when(client.offlineTaskInfo("first")).thenReturn(snapshot("first", 1));
        CountDownLatch first = new CountDownLatch(1);
        CountDownLatch alias = new CountDownLatch(1);
        CountDownLatch unknown = new CountDownLatch(1);
        CountDownLatch differentRoot = new CountDownLatch(1);
        CountDownLatch blankRoot = new CountDownLatch(1);
        try (OfflineIngestScheduler scheduler = new OfflineIngestScheduler(client)) {
            scheduler.enqueue("/mount-a/show", "a", Duration.ofSeconds(5), Duration.ofMillis(5), submission("first", first));
            assertThat(first.await(2, TimeUnit.SECONDS)).isTrue();
            scheduler.enqueue("/mount-b/show", "b", Duration.ofSeconds(5), Duration.ofMillis(5), submission("alias", alias));
            scheduler.enqueue("/unknown", "c", Duration.ofSeconds(5), Duration.ofMillis(5), submission("unknown", unknown));
            scheduler.enqueue("/different-root", "d", Duration.ofSeconds(5), Duration.ofMillis(5), submission("different", differentRoot));
            scheduler.enqueue("/blank-root", "e", Duration.ofSeconds(5), Duration.ofMillis(5), submission("blank", blankRoot));
            verify(client, timeout(2000).atLeast(2)).offlineTaskInfo("first");
            assertThat(alias.getCount()).isEqualTo(1);
            assertThat(unknown.getCount()).isEqualTo(1);
            assertThat(differentRoot.getCount()).isEqualTo(1);
            assertThat(blankRoot.getCount()).isEqualTo(1);
        }
    }

    @Test
    void batchKeepsDirectoryUntilAllItemsFinishAndQueueRejectsOverflow() throws Exception {
        OpenListClient client = mappedClient();
        when(client.offlineTaskInfo("one")).thenReturn(snapshot("one", 2));
        when(client.offlineTaskInfo("two")).thenReturn(snapshot("two", 1));
        CountDownLatch one = new CountDownLatch(1);
        CountDownLatch two = new CountDownLatch(1);
        CountDownLatch outside = new CountDownLatch(1);
        try (OfflineIngestScheduler scheduler = new OfflineIngestScheduler(client)) {
            var batch = scheduler.enqueueBatch(java.util.List.of(
                    new OfflineIngestScheduler.DownloadRequest("/batch", "a", Duration.ofSeconds(5), Duration.ofMillis(5), submission("one", one)),
                    new OfflineIngestScheduler.DownloadRequest("/batch", "b", Duration.ofSeconds(5), Duration.ofMillis(5), submission("two", two))));
            assertThat(one.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(two.await(2, TimeUnit.SECONDS)).isTrue();
            batch.get(0).get(2, TimeUnit.SECONDS);
            scheduler.enqueue("/batch", "outside", Duration.ofSeconds(5), Duration.ofMillis(5), submission("outside", outside));
            verify(client, timeout(2000).atLeast(2)).offlineTaskInfo("two");
            assertThat(outside.getCount()).isEqualTo(1);
        }
        CountDownLatch blocked = new CountDownLatch(1);
        try (OfflineIngestScheduler scheduler = new OfflineIngestScheduler(client)) {
            for (int i = 0; i < 128; i++) {
                scheduler.enqueue("/same", "same", Duration.ofSeconds(5), Duration.ofMillis(5), new DownloadStub() {
                    public String submit() { await(blocked); return "blocked"; }
                });
            }
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> scheduler.enqueue("/overflow", "overflow",
                    Duration.ofSeconds(5), Duration.ofMillis(5), new DownloadStub()))
                    .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
            blocked.countDown();
        } finally { blocked.countDown(); }
    }

    @Test
    void confirmedCancellationReleasesCapacityAndOrganizationFailureDoesNotStallQueue() throws Exception {
        OpenListClient client = mappedClient();
        java.util.concurrent.atomic.AtomicBoolean cancellationRequested = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call -> { cancellationRequested.set(true); return null; }).when(client).cancelOfflineTask("upstream");
        when(client.offlineTaskInfo("upstream")).thenAnswer(call -> snapshot("upstream", cancellationRequested.get() ? 4 : 1));
        try (OfflineIngestScheduler scheduler = new OfflineIngestScheduler(client)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> scheduler.enqueue("/same", "a",
                    Duration.ofMillis(20), Duration.ofMillis(5), new DownloadStub()).get(2, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(com.medianexus.orchestrator.integration.openlist.OpenListClientException.class);
            when(client.offlineTaskInfo("next")).thenReturn(snapshot("next", 2));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> scheduler.enqueue("/same", "b",
                    Duration.ofSeconds(2), Duration.ofMillis(5), new DownloadStub() {
                        public String submit() { return "next"; }
                        public void organize(String id) { throw new IllegalStateException("organizer failed"); }
                    }).get(2, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
            scheduler.enqueue("/same", "c", Duration.ofSeconds(2), Duration.ofMillis(5), new DownloadStub() {
                public String submit() { return "next"; }
            }).get(2, TimeUnit.SECONDS);
            verify(client, never()).remove(anyString(), any());
        }
    }

    @Test void cancellationReturningTerminalIncompleteNeverOrganizesAndReleasesClaims() throws Exception {
        OpenListClient client = mappedClient();
        var cancelRequested = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call -> { cancelRequested.set(true); return null; }).when(client).cancelOfflineTask("upstream");
        when(client.offlineTaskInfo("upstream")).thenAnswer(call -> new OpenListOfflineTaskInfo(
                "upstream", cancelRequested.get() ? 2 : 1, "Saving", 88, null, null));
        try (var scheduler = new OfflineIngestScheduler(client)) {
            assertThatThrownBy(() -> scheduler.enqueue("/same", "first", Duration.ofMillis(25), Duration.ofMillis(5),
                    new DownloadStub() { public void organize(String id) { throw new AssertionError("partial canceled task must not organize"); } })
                    .get(2, TimeUnit.SECONDS)).hasCauseInstanceOf(com.medianexus.orchestrator.integration.openlist.OpenListClientException.class);
            when(client.offlineTaskInfo("next")).thenReturn(snapshot("next", 2));
            scheduler.enqueue("/same", "second", Duration.ofSeconds(2), Duration.ofMillis(5),
                    new DownloadStub() { public String submit() { return "next"; } }).get(2, TimeUnit.SECONDS);
            verify(client, times(1)).cancelOfflineTask("upstream");
            verify(client, never()).retryOfflineTask(anyString());
        }
    }

    private static OpenListClient mappedClient() {
        OpenListClient client = mock(OpenListClient.class);
        when(client.offlineDirectoryForPath(anyString())).thenAnswer(call ->
                new OpenListClient.OfflineDirectory("account", "/", call.getArgument(0)));
        return client;
    }

    private static DownloadStub submission(String id, CountDownLatch submitted) {
        return new DownloadStub() {
            public String submit() { submitted.countDown(); return id; }
        };
    }

    private static OpenListOfflineTaskInfo snapshot(String id, int state) {
        return new OpenListOfflineTaskInfo(id, state, "status", 10, 100L, null);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("latch timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static class DownloadStub implements OfflineIngestScheduler.Download {
        public void prepare() { }
        public String submit() { return "upstream"; }
        public void submitted(String id) { }
        public void progress(OpenListOfflineTaskInfo snapshot) { }
        public void organize(String id) { }
        public void failed(RuntimeException failure) { }
        public void stage(String message) { }
    }
}
