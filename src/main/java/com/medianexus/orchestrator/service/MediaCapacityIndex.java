package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.integration.clouddrive.CloudDrive2MediaStorage;
import com.medianexus.orchestrator.integration.clouddrive.CloudDrive2MediaStorage.Entry;
import com.medianexus.orchestrator.integration.emby.EmbyLibrary;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Keeps Adult directory size scans off the list request and verifies whole-work deletion boundaries. */
@Component
public class MediaCapacityIndex {
    public record Capacity(Long bytes, String status) {
        private static final Capacity PENDING = new Capacity(null, "pending");
        private static final Capacity UNKNOWN = new Capacity(null, "unknown");

        public static Capacity measured(long bytes) {
            return bytes >= 0 ? new Capacity(bytes, "complete") : UNKNOWN;
        }
    }

    private static final Set<String> SIDECARS = Set.of(
            "srt", "ass", "ssa", "vtt", "smi", "sup", "sub", "idx", "nfo", "jpg", "jpeg", "png", "webp");

    private final CloudDrive2MediaStorage storage;
    private final LocalStrmDeletion strm;
    // ponytail: bounded in-memory cache; use a shared index if this service runs on multiple instances.
    private final Map<ScanKey, CachedCapacity> readings = new LinkedHashMap<>(16, 0.75f, true);
    private final Set<ScanKey> queued = new HashSet<>();
    private final ThreadPoolExecutor scanner = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(12_000), task -> new Thread(task, "media-capacity"));

    public MediaCapacityIndex(CloudDrive2MediaStorage storage, LocalStrmDeletion strm) {
        this.storage = storage;
        this.strm = strm;
    }

    Capacity entry(Entry entry) {
        if (!entry.directory()) return Capacity.measured(entry.bytes());
        return read(new ScanKey(entry.path(), entry.id()), () ->
                Capacity.measured(sum(storage.cachedFolderFiles(entry.path()).values())));
    }

    synchronized void invalidate(List<String> cloudPaths) {
        readings.keySet().removeIf(key -> {
            Path cached = Path.of(key.path());
            return cloudPaths.stream().map(Path::of)
                    .anyMatch(path -> path.startsWith(cached) || cached.startsWith(path));
        });
        // A running scan may have observed the old tree before a deletion completed.
        revision++;
    }

    private long revision;

    private synchronized Capacity read(ScanKey key, Supplier<Capacity> measure) {
        CachedCapacity cached = readings.get(key);
        if (cached != null && cached.expires().isAfter(Instant.now())) return cached.capacity();
        if (queued.add(key)) {
            try {
                scanner.execute(() -> {
                    long scanRevision;
                    synchronized (this) {
                        scanRevision = revision;
                    }
                    Capacity capacity;
                    try {
                        capacity = measure.get();
                    } catch (RuntimeException exception) {
                        capacity = Capacity.UNKNOWN;
                    }
                    synchronized (this) {
                        queued.remove(key);
                        if (scanRevision == revision) {
                            Duration ttl = capacity.bytes() == null ? Duration.ofMinutes(2) : Duration.ofMinutes(30);
                            readings.put(key, new CachedCapacity(capacity, Instant.now().plus(ttl)));
                            if (readings.size() > 20_000) readings.remove(readings.keySet().iterator().next());
                        }
                    }
                });
            } catch (RejectedExecutionException full) {
                queued.remove(key);
                return Capacity.UNKNOWN;
            }
        }
        return Capacity.PENDING;
    }

    /**
     * Whole-work deletes may include sidecars only after verifying the live tree and checking
     * that another STRM does not reference it. Shared or unverified folders fall back to exact files.
     */
    String confirmedWorkFolder(AdminMediaLibraryScope scope, EmbyLibrary library, String itemPath,
            List<String> sources, List<String> cleanupPaths) {
        if (itemPath == null || sources.isEmpty()) return null;
        try {
            Function<String, String> mapping = storage.sourcePathMapping();
            List<String> cloudPaths = sources.stream().map(mapping).distinct().toList();
            Path folder = workFolder(scope, library, Path.of(itemPath), cloudPaths);
            if (folder == null || !coversWork(storage.liveFolderFiles(folder.toString()).keySet(), cloudPaths)) {
                return null;
            }
            List<Path> cleanup = cleanupPaths.stream().map(Path::of).toList();
            List<String> links = strm.allStrmFiles();
            if (links.isEmpty()) return null;
            for (String link : links) {
                if (cleanup.stream().anyMatch(Path.of(link)::startsWith)) continue;
                String mapped = mapping.apply(strm.resolveMediaSourcePath(link));
                if (mapped != null && Path.of(mapped).startsWith(folder)) return null;
            }
            Path source = Path.of(sources.get(0));
            int levels = Path.of(cloudPaths.get(0)).getNameCount() - folder.getNameCount();
            for (int i = 0; i < levels; i++) source = source.getParent();
            return source.toString();
        } catch (RuntimeException unsafeFolder) {
            return null;
        }
    }

    private Path workFolder(AdminMediaLibraryScope scope, EmbyLibrary library, Path path, List<String> cloudPaths) {
        if (cloudPaths.isEmpty() || cloudPaths.contains(null)) return null;
        Path localFolder = scope.episodic() ? path : path.getParent();
        if (localFolder == null || localFolder.getFileName() == null || library.locations().stream()
                .map(Path::of).noneMatch(root -> !localFolder.equals(root) && localFolder.startsWith(root))) {
            return null;
        }
        String name = localFolder.getFileName().toString();
        List<Path> roots = storage.workRoots(scope.requestValue()).stream().map(Path::of).toList();
        for (Path folder = Path.of(cloudPaths.get(0)).getParent(); folder != null; folder = folder.getParent()) {
            Path candidate = folder;
            if (candidate.getFileName() != null && candidate.getFileName().toString().equals(name)
                    && roots.stream().anyMatch(root -> !candidate.equals(root) && candidate.startsWith(root))
                    && cloudPaths.stream().map(Path::of).allMatch(file -> !file.equals(candidate) && file.startsWith(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    private boolean coversWork(Set<String> files, List<String> sources) {
        return files.containsAll(sources) && files.stream().allMatch(file -> {
            int dot = file.lastIndexOf('.');
            return sources.contains(file) || (dot >= 0 && SIDECARS.contains(file.substring(dot + 1).toLowerCase(Locale.ROOT)));
        });
    }

    private long sum(Iterable<Long> sizes) {
        long bytes = 0;
        for (long size : sizes) {
            if (size < 0) return -1;
            bytes = Math.addExact(bytes, size);
        }
        return bytes;
    }

    @PreDestroy
    void close() {
        scanner.shutdownNow();
    }

    private record ScanKey(String path, String identity) { }

    private record CachedCapacity(Capacity capacity, Instant expires) { }
}
