package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.integration.openlist.OpenListClient;
import com.medianexus.orchestrator.integration.openlist.OpenListClientException;
import com.medianexus.orchestrator.integration.openlist.OpenListOfflineTaskInfo;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Owns process-local capacity and claims from download preparation through organization. */
@Service
public class OfflineIngestScheduler implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(OfflineIngestScheduler.class);
    private static final int CAPACITY = 2;
    private static final int QUEUE_LIMIT = 128;
    private final OpenListClient client;
    private final ExecutorService submissions = Executors.newFixedThreadPool(2, threads("offline-submit"));
    private final ExecutorService queries = Executors.newFixedThreadPool(2, threads("offline-query"));
    private final ExecutorService organizations = Executors.newSingleThreadExecutor(threads("offline-organize"));
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(threads("offline-timer"));
    private final List<Job> waiting = new ArrayList<>();
    private final List<Job> claimed = new ArrayList<>();
    private final List<RecoveryClaim> organizationRecoveryClaims = new ArrayList<>();

    private record RecoveryClaim(String hash, OpenListClient.OfflineDirectory directory) {}
    private int activeDownloads;
    private boolean upstreamChecked;
    private boolean checking;
    private volatile boolean closed;
    private String restartBlock;

    /** Media-specific persistence and organization stay with the entrypoint that owns them. */
    public interface Download {
        void prepare();
        String submit();
        void submitted(String upstreamId);
        void progress(OpenListOfflineTaskInfo snapshot);
        void organize(String upstreamId);
        void failed(RuntimeException failure);
        void stage(String message);
        default void cancellationSettled(OpenListOfflineTaskInfo snapshot) {
            throw new IllegalStateException("该入口尚未支持持久化取消结清，继续保留认领");
        }
    }

    public OfflineIngestScheduler(OpenListClient client) {
        this.client = client;
    }

    public CompletableFuture<Void> enqueue(String path, String hash, Duration timeout,
                                            Duration interval, Download download) {
        return enqueueBatch(List.of(new DownloadRequest(path, hash, timeout, interval, download))).get(0);
    }

    public record DownloadRequest(String path, String hash, Duration timeout, Duration interval, Download download) { }

    /** A batch shares its final directory, but never its hash claim, with its own independent temp downloads. */
    public synchronized List<CompletableFuture<Void>> enqueueBatch(List<DownloadRequest> requests) {
        if (closed || waiting.size() + claimed.size() + requests.size() > QUEUE_LIMIT) {
            throw new RejectedExecutionException("离线调度队列已满或已关闭");
        }
        if (restartBlock != null) throw new RejectedExecutionException(restartBlock);
        Object directoryOwner = new Object();
        List<Job> jobs = requests.stream().map(request -> new Job(normalizePath(request.path()),
                request.hash() == null ? "" : request.hash().toLowerCase(Locale.ROOT),
                request.timeout(), request.interval(), request.download(), directoryOwner)).toList();
        for (Job job : jobs) {
            job.download.stage("等待共享下载名额（全局/保守账号桶各 2），并认领目录和 hash");
        }
        waiting.addAll(jobs);
        if (!upstreamChecked && !checking) {
            checking = true;
            submissions.execute(this::checkUpstream);
        }
        for (Job job : jobs) submissions.execute(() -> resolveDirectory(job));
        drain();
        return jobs.stream().map(job -> job.completion).toList();
    }

    public void finishBatchAfter(List<CompletableFuture<Void>> completions, Runnable finish) {
        CompletableFuture.allOf(completions.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, failure) -> organizations.execute(finish));
    }

    private void resolveDirectory(Job job) {
        try {
            job.directory = client.offlineDirectoryForPath(job.path);
            synchronized (this) {
                if (!waiting.contains(job)) return;
                job.directoryResolved = true;
                drain();
            }
        } catch (RuntimeException exception) {
            synchronized (this) { waiting.remove(job); }
            fail(job, exception);
            release(job);
        }
    }

    /** Restart does not recover a lost submission outcome, even if /undone happens to be empty. */
    public synchronized void blockUnresolvedRestart(String reason) {
        restartBlock = reason;
        log.warn("Offline submissions blocked: {}", reason);
    }

    public synchronized String submissionPauseReason() { return restartBlock; }

    /** Retains a stopped process's organization exclusions without claiming a download slot or replaying writes. */
    public void retainOrganizationRecovery(String path, String hash) {
        var directory = client.offlineDirectoryForPath(normalizePath(path));
        synchronized (this) {
            var claim = new RecoveryClaim(hash == null ? "" : hash.toLowerCase(Locale.ROOT), directory);
            if (!organizationRecoveryClaims.contains(claim)) organizationRecoveryClaims.add(claim);
        }
    }

    private void checkUpstream() {
        try {
            if (client.unfinishedOfflineTaskCount() != 0) {
                throw new OpenListClientException("OpenList 存在未接管离线/传输任务，需人工核实后重启调度");
            }
            synchronized (this) {
                upstreamChecked = true;
                checking = false;
                drain();
            }
        } catch (RuntimeException exception) {
            List<Job> rejected;
            synchronized (this) {
                checking = false;
                rejected = new ArrayList<>(waiting);
                waiting.clear();
            }
            rejected.forEach(job -> fail(job, exception));
        }
    }

    private synchronized void drain() {
        if (!upstreamChecked || restartBlock != null || closed) return;
        // ponytail: all accounts share the conservative bucket; mount/category IDs prove no account separation.
        for (int i = 0; i < waiting.size() && activeDownloads < CAPACITY;) {
            Job job = waiting.get(i);
            if (!job.directoryResolved || claimed.stream().anyMatch(other -> conflicts(job, other))
                    || organizationRecoveryClaims.stream().anyMatch(other ->
                    !job.hash.isBlank() && job.hash.equals(other.hash()) || directoriesConflict(job.directory, other.directory()))) {
                i++;
                continue;
            }
            waiting.remove(i);
            claimed.add(job);
            job.downloadSlot = true;
            activeDownloads++;
            submissions.execute(() -> submit(job));
        }
    }

    private boolean conflicts(Job left, Job right) {
        if (!left.hash.isBlank() && left.hash.equals(right.hash)) return true;
        if (left.directoryOwner == right.directoryOwner) return false;
        return directoriesConflict(left.directory, right.directory);
    }

    private boolean directoriesConflict(OpenListClient.OfflineDirectory a, OpenListClient.OfflineDirectory b) {
        if (a.account().equals("unknown") || b.account().equals("unknown")) return true;
        if (!a.account().equals(b.account())) return false;
        if (a.rootId().isBlank() || b.rootId().isBlank() || !a.rootId().equals(b.rootId())) return true;
        return a.relativePath().equals(b.relativePath())
                || a.relativePath().startsWith(b.relativePath().equals("/") ? "/" : b.relativePath() + "/")
                || b.relativePath().startsWith(a.relativePath().equals("/") ? "/" : a.relativePath() + "/");
    }

    private void submit(Job job) {
        try (var binding = IngestStageTiming.bind(job.timingContext)) {
            job.download.stage("已取得共享下载名额；排队耗时 ms=" + elapsed(job.enqueuedAt));
            job.download.prepare();
        } catch (RuntimeException exception) {
            fail(job, exception);
            release(job);
            return;
        }
        job.startedAt = System.nanoTime();
        try (var binding = IngestStageTiming.bind(job.timingContext)) {
            job.downloadTiming = IngestStageTiming.start("download", "OpenList submission-call-to-completion-observation; not pure transfer", null);
        }
        try (var binding = IngestStageTiming.bind(job.timingContext);
             var timing = IngestStageTiming.start("download_submit", "OpenList", null)) {
            job.upstreamId = job.download.submit();
            if (job.upstreamId == null || job.upstreamId.isBlank()) {
                throw new OpenListClientException("上游任务 ID 缺失");
            }
            timing.finish("ACCEPTED", "upstreamId=" + job.upstreamId + ";not actual transfer start");
            job.queueTiming.finish("ACCEPTED", "upstreamId=" + job.upstreamId + ";scheduler enqueue-to-upstream acceptance; task insertion event provides earlier boundary");
            // Persist before the first query; a failed persistence is an unknown lifecycle, never a retry.
            job.download.submitted(job.upstreamId);
        } catch (RuntimeException exception) {
            hold(job, "提交结果未知，保留下载额度及目录/hash 认领；禁止自动重发", exception);
            return;
        }
        scheduleQuery(job);
    }

    private synchronized void scheduleQuery(Job job) {
        if (!closed) {
            timer.schedule(() -> dispatchQuery(job), job.interval.toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    private synchronized void dispatchQuery(Job job) {
        if (!closed) queries.execute(() -> query(job));
    }

    private void query(Job job) {
        try (var binding = IngestStageTiming.bind(job.timingContext)) {
            OpenListOfflineTaskInfo snapshot;
            try (var timing = IngestStageTiming.start("download_poll", "OpenList", null)) {
                snapshot = client.offlineTaskInfo(job.upstreamId);
                timing.finish("OBSERVED", "upstreamId=" + job.upstreamId + ";state=" + snapshot.state()
                        + ";progress=" + snapshot.progress() + ";totalBytes=" + snapshot.totalBytes()
                        + ";pollIntervalMs=" + job.interval.toMillis() + ";actual transition time UNKNOWN");
            }
            if (!job.upstreamId.equals(snapshot.id())) throw new OpenListClientException("上游任务 ID 不匹配");
            Integer state = snapshot.state();
            if (Integer.valueOf(2).equals(state) && Integer.valueOf(100).equals(snapshot.progress())) {
                job.downloadCompletedAt = System.nanoTime();
                job.downloadTiming.finish("COMPLETE_OBSERVED", "upstreamId=" + job.upstreamId
                        + ";state=2;actual transfer start/end UNKNOWN; derive observation bounds from download_poll");
                job.download.stage("下载已确认完成，等待单路整理；下载耗时 ms=" + elapsed(job.startedAt));
                synchronized (this) {
                    activeDownloads--;
                    job.downloadSlot = false;
                    drain();
                }
                organizations.execute(() -> organize(job));
                return;
            }
            // tache: 3=canceling, 4=canceled, 5/6/8/9 can still retry; only 7 is terminal failure.
            if (Integer.valueOf(4).equals(state) || Integer.valueOf(7).equals(state)) {
                fail(job, new OpenListClientException("OpenList 离线任务终止: state=" + state + ", " + snapshot.error()));
                release(job);
                return;
            }
            job.download.progress(snapshot);
            if (System.nanoTime() - job.startedAt >= job.timeout.toNanos()) {
                cancelTimedOut(job);
                return;
            }
        } catch (RuntimeException exception) {
            if (System.nanoTime() - job.startedAt >= job.timeout.toNanos()) {
                cancelTimedOut(job);
                return;
            }
            log.warn("Offline query failed; retrying query only id={}", job.upstreamId, exception);
        }
        // Scheduled only after this query returns: no same-job overlap or sleeping query workers.
        scheduleQuery(job);
    }

    private void cancelTimedOut(Job job) {
        job.cancelRequested = true;
        try {
            client.cancelOfflineTask(job.upstreamId);
        } catch (RuntimeException exception) {
            hold(job, "离线下载超时，取消请求结果未知；保留源文件、下载额度及目录/hash 认领", exception);
            scheduleHeldRecheck(job);
            return;
        }
        try {
            OpenListOfflineTaskInfo snapshot = client.offlineTaskInfo(job.upstreamId);
            if (stoppedAfterCancellation(job, snapshot)) {
                fail(job, new OpenListClientException("离线下载超时，已确认上游停止；源文件保留"));
                release(job);
                return;
            }
        } catch (RuntimeException exception) {
            hold(job, "离线下载超时，取消未确认；保留源文件、下载额度及目录/hash 认领", exception);
            scheduleHeldRecheck(job);
            return;
        }
        hold(job, "离线下载超时，取消未确认；保留源文件、下载额度及目录/hash 认领", null);
        scheduleHeldRecheck(job);
    }

    private boolean stoppedAfterCancellation(Job job, OpenListOfflineTaskInfo snapshot) {
        return job.cancelRequested && job.upstreamId.equals(snapshot.id())
                && (Integer.valueOf(4).equals(snapshot.state()) || Integer.valueOf(7).equals(snapshot.state())
                || Integer.valueOf(2).equals(snapshot.state()) && snapshot.progress() != null && snapshot.progress() < 100);
    }

    private synchronized void scheduleHeldRecheck(Job job) {
        if (closed || job.recheckScheduled || !job.unresolved) return;
        job.recheckScheduled = true;
        timer.schedule(() -> {
            synchronized (OfflineIngestScheduler.this) { job.recheckScheduled = false; }
            if (!closed) queries.execute(() -> recheckHeld(job));
        }, Math.max(10, job.interval.toMillis()), TimeUnit.MILLISECONDS);
    }

    private void recheckHeld(Job job) {
        if (!job.unresolved || job.upstreamId == null) return;
        try {
            OpenListOfflineTaskInfo snapshot = client.offlineTaskInfo(job.upstreamId);
            if (stoppedAfterCancellation(job, snapshot)) {
                job.download.cancellationSettled(snapshot);
                synchronized (this) { job.unresolved = false; }
                release(job);
                return;
            }
        } catch (RuntimeException exception) {
            log.warn("Held offline task recheck failed; retaining claims id={}", job.upstreamId);
        }
        scheduleHeldRecheck(job);
    }
    private void organize(Job job) {
        long organizationStarted = System.nanoTime();
        try (var binding = IngestStageTiming.bind(job.timingContext)) {
            job.download.stage("开始单路整理；下载完成后等待 ms=" + elapsed(job.downloadCompletedAt));
            job.download.organize(job.upstreamId);
            job.completion.complete(null);
        } catch (RuntimeException exception) {
            fail(job, exception);
        } finally {
            log.info("Offline organization/refresh settled id={} organizationMs={} lifecycleMs={}",
                    job.upstreamId, elapsed(organizationStarted), elapsed(job.enqueuedAt));
            release(job);
        }
    }

    private void hold(Job job, String message, RuntimeException cause) {
        job.unresolved = true;
        fail(job, new OpenListClientException("[OFFLINE_UNRESOLVED] " + message, cause));
    }

    private void fail(Job job, RuntimeException exception) {
        job.queueTiming.finish(job.unresolved ? "UNKNOWN" : "FAILED", exception.getClass().getSimpleName());
        if (job.downloadTiming != null) job.downloadTiming.finish(job.unresolved ? "UNKNOWN" : "FAILED", exception.getClass().getSimpleName());
        try {
            job.download.failed(exception);
        } catch (RuntimeException persistenceFailure) {
            log.error("Offline failure persistence failed", persistenceFailure);
        } finally {
            job.completion.completeExceptionally(exception);
        }
    }

    private synchronized void release(Job job) {
        if (job.downloadSlot) {
            activeDownloads--;
            job.downloadSlot = false;
        }
        boolean batchWaiting = waiting.stream().anyMatch(other -> other.directoryOwner == job.directoryOwner);
        boolean batchUnfinished = claimed.stream().anyMatch(other -> other.directoryOwner == job.directoryOwner
                && (!other.completion.isDone() || other.unresolved));
        if (!batchWaiting && !batchUnfinished) claimed.removeIf(other -> other.directoryOwner == job.directoryOwner);
        drain();
    }

    private static long elapsed(long since) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - since);
    }

    private static String normalizePath(String path) {
        if (path == null || !path.startsWith("/") || path.contains("/../") || path.endsWith("/..")) {
            throw new IllegalArgumentException("调度目录必须是绝对且不含上级跳转的路径");
        }
        String normalized = path.replaceAll("/+", "/");
        return normalized.length() > 1 && normalized.endsWith("/")
                ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private static ThreadFactory threads(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override
    @PreDestroy
    public synchronized void close() {
        closed = true;
        timer.shutdownNow();
        submissions.shutdownNow();
        queries.shutdownNow();
        organizations.shutdownNow();
    }

    private static final class Job {
        final Object directoryOwner;
        final String path;
        final String hash;
        final Duration timeout;
        final Duration interval;
        final Download download;
        final IngestStageTiming.Context timingContext = IngestStageTiming.context();
        final IngestStageTiming.Span queueTiming = IngestStageTiming.start("download_queue", "shared offline scheduler", null);
        IngestStageTiming.Span downloadTiming;
        final CompletableFuture<Void> completion = new CompletableFuture<>();
        final long enqueuedAt = System.nanoTime();
        long startedAt;
        long downloadCompletedAt;
        String upstreamId;
        boolean downloadSlot;
        boolean unresolved;
        boolean cancelRequested;
        boolean recheckScheduled;
        boolean directoryResolved;
        OpenListClient.OfflineDirectory directory;
        Job(String path, String hash, Duration timeout, Duration interval, Download download, Object directoryOwner) {
            this.directoryOwner = directoryOwner;
            this.path = path;
            this.hash = hash;
            this.timeout = timeout;
            this.interval = interval;
            this.download = download;
        }
    }
}
