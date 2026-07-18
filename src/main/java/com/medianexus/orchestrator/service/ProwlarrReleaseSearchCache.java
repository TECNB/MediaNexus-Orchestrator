package com.medianexus.orchestrator.service;

import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.medianexus.orchestrator.config.ProwlarrProperties;
import com.medianexus.orchestrator.integration.prowlarr.ProwlarrClient;
import com.medianexus.orchestrator.integration.prowlarr.ProwlarrRelease;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ProwlarrReleaseSearchCache {

    private static final Logger log = LoggerFactory.getLogger(ProwlarrReleaseSearchCache.class);
    private static final int SEARCH_ENTRY_BASE_WEIGHT = 64;
    private static final int RELEASE_BASE_WEIGHT = 192;
    private static final int STRING_BASE_WEIGHT = 40;

    private final ProwlarrClient prowlarrClient;
    private final Cache<String, List<ProwlarrRelease>> releasesByQuery;

    @Autowired
    public ProwlarrReleaseSearchCache(
            ProwlarrClient prowlarrClient,
            ProwlarrProperties properties
    ) {
        this(prowlarrClient, properties, Ticker.systemTicker());
    }

    ProwlarrReleaseSearchCache(
            ProwlarrClient prowlarrClient,
            ProwlarrProperties properties,
            Ticker ticker
    ) {
        this.prowlarrClient = prowlarrClient;
        this.releasesByQuery = CacheBuilder.<String, List<ProwlarrRelease>>newBuilder()
                .maximumWeight(properties.getSearchCacheMaxWeightBytes())
                .weigher(ProwlarrReleaseSearchCache::estimatedWeight)
                .expireAfterWrite(properties.getSearchCacheTtl())
                .ticker(ticker)
                .build();
    }

    public List<ProwlarrRelease> search(String query) {
        long startedAt = System.nanoTime();
        List<ProwlarrRelease> cachedReleases = releasesByQuery.getIfPresent(query);
        if (cachedReleases != null) {
            logSearch("HIT", query, startedAt, cachedReleases.size());
            return cachedReleases;
        }

        AtomicBoolean loadedByThisCall = new AtomicBoolean();
        try {
            List<ProwlarrRelease> releases = releasesByQuery.get(query, () -> {
                loadedByThisCall.set(true);
                return List.copyOf(prowlarrClient.search(query));
            });
            logSearch(loadedByThisCall.get() ? "MISS" : "HIT", query, startedAt, releases.size());
            return releases;
        } catch (ExecutionException exception) {
            throw propagate(exception.getCause());
        } catch (UncheckedExecutionException exception) {
            throw propagate(exception.getCause());
        }
    }

    public Map<String, List<ProwlarrRelease>> refreshQueries(List<String> queries) {
        List<String> distinctQueries = List.copyOf(new LinkedHashSet<>(queries));
        Map<String, CompletableFuture<List<ProwlarrRelease>>> futuresByQuery = new LinkedHashMap<>();
        for (String query : distinctQueries) {
            futuresByQuery.put(query, CompletableFuture.supplyAsync(() -> refreshQuery(query)));
        }

        try {
            CompletableFuture.allOf(futuresByQuery.values().toArray(CompletableFuture[]::new)).join();
        } catch (CompletionException exception) {
            throw propagate(exception.getCause());
        }

        Map<String, List<ProwlarrRelease>> refreshedReleases = new LinkedHashMap<>();
        futuresByQuery.forEach((query, future) -> refreshedReleases.put(query, future.join()));
        releasesByQuery.putAll(refreshedReleases);
        return Map.copyOf(refreshedReleases);
    }

    private List<ProwlarrRelease> refreshQuery(String query) {
        long startedAt = System.nanoTime();
        try {
            List<ProwlarrRelease> releases = List.copyOf(prowlarrClient.search(query));
            logSearch("REFRESH", query, startedAt, releases.size());
            return releases;
        } catch (RuntimeException exception) {
            log.warn(
                    "Prowlarr search cache event=REFRESH query={} elapsedMs={} failed",
                    logValue(query),
                    elapsedMillis(startedAt)
            );
            throw exception;
        }
    }

    private void logSearch(String event, String query, long startedAt, int resultCount) {
        log.info(
                "Prowlarr search cache event={} query={} elapsedMs={} resultCount={}",
                event,
                logValue(query),
                elapsedMillis(startedAt),
                resultCount
        );
    }

    private static int estimatedWeight(String query, List<ProwlarrRelease> releases) {
        long weight = SEARCH_ENTRY_BASE_WEIGHT + estimatedStringWeight(query);
        for (ProwlarrRelease release : releases) {
            weight += RELEASE_BASE_WEIGHT;
            weight += estimatedStringWeight(release.title());
            weight += estimatedStringWeight(release.indexer());
            weight += estimatedStringWeight(release.publishDate());
            weight += estimatedStringWeight(release.guid());
            weight += estimatedStringWeight(release.downloadRef());
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, weight));
    }

    private static long estimatedStringWeight(String value) {
        return value == null ? 0L : STRING_BASE_WEIGHT + (long) value.length() * Character.BYTES;
    }

    private static RuntimeException propagate(Throwable cause) {
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new IllegalStateException("Prowlarr search failed", cause);
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private static String logValue(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace('\r', ' ').replace('\n', ' ').trim();
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200);
    }
}
