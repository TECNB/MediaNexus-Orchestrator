package com.medianexus.orchestrator.integration.guangya;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** 每轮共享：总截止时间含搜索，解析截止时间含认证/排队/读体；缓存不占请求或并发槽。 */
public final class ResolveBudget {
    private final Instant totalDeadline;
    private final Instant resolveDeadline;
    private final long deadlineNanos;
    private final Semaphore concurrent = new Semaphore(2, true);
    private int resolveRequests;
    private Object authenticationRefresh;

    public ResolveBudget(Instant totalDeadline, Instant resolveDeadline) {
        this.totalDeadline = Objects.requireNonNull(totalDeadline);
        this.resolveDeadline = Objects.requireNonNull(resolveDeadline);
        Instant deadline = totalDeadline.isBefore(resolveDeadline) ? totalDeadline : resolveDeadline;
        long remaining = Math.max(0, Math.min(Duration.ofSeconds(30).toNanos(), Duration.between(Instant.now(), deadline).toNanos()));
        deadlineNanos = System.nanoTime() + remaining;
    }
    public static ResolveBudget start() {
        Instant now = Instant.now();
        return new ResolveBudget(now.plusSeconds(45), now.plusSeconds(30));
    }
    public Instant totalDeadline() { return totalDeadline; }
    public Instant resolveDeadline() { return resolveDeadline; }
    public synchronized int resolveRequests() { return resolveRequests; }
    public synchronized boolean authenticationRefreshUsed() { return authenticationRefresh != null; }
    public Duration remaining() {
        long nanos = deadlineNanos - System.nanoTime();
        if (nanos <= 0) throw exhausted();
        return Duration.ofNanos(nanos);
    }
    synchronized void countResolveRequest() {
        remaining();
        if (resolveRequests >= 20) throw exhausted();
        resolveRequests++;
    }
    synchronized void joinAuthenticationRefresh(Object sharedRefresh) {
        remaining();
        if (authenticationRefresh != null && authenticationRefresh != sharedRefresh) throw new GuangyaException(GuangyaException.Reason.AUTHENTICATION, "本轮认证刷新已使用，请重新配置凭据或继续下一轮");
        authenticationRefresh = sharedRefresh;
    }
    void acquire() {
        try {
            if (!concurrent.tryAcquire(remaining().toNanos(), TimeUnit.NANOSECONDS)) throw exhausted();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GuangyaException(GuangyaException.Reason.UNAVAILABLE, "光鸭解析等待已中断");
        }
    }
    void release() { concurrent.release(); }
    Duration requestTimeout() { Duration remaining = remaining(); return remaining.compareTo(Duration.ofSeconds(8)) < 0 ? remaining : Duration.ofSeconds(8); }
    private GuangyaException exhausted() { return new GuangyaException(GuangyaException.Reason.BUDGET_EXHAUSTED, "本轮光鸭解析预算已用尽，尚未核验的候选可在下一轮继续"); }
}
