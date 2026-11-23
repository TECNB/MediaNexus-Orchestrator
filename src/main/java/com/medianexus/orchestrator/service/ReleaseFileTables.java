package com.medianexus.orchestrator.service;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.medianexus.orchestrator.integration.guangya.*;
import com.medianexus.orchestrator.integration.pansou.PanSouMagnetIdentity;
import com.medianexus.orchestrator.integration.prowlarr.*;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** 文件表的共同入口；来源拒绝可回退，已知结构安全失败不可换源洗白。 */
@Component
public class ReleaseFileTables {
    private final GuangyaClient guangya;
    private final ProwlarrClient prowlarr;
    private final Cache<String, Evidence> tables = CacheBuilder.newBuilder().maximumSize(256)
            .expireAfterWrite(2, TimeUnit.HOURS).build();
    private final Cache<String, List<ProwlarrRelease>> releases = CacheBuilder.newBuilder().maximumSize(2048)
            .expireAfterWrite(2, TimeUnit.HOURS).build();
    private final Cache<String, String> unsafe = CacheBuilder.newBuilder().maximumSize(2048)
            .expireAfterWrite(2, TimeUnit.HOURS).build();
    private final Cache<String, String> resolvedMagnets = CacheBuilder.newBuilder().maximumSize(2048)
            .expireAfterWrite(2, TimeUnit.HOURS).build();
    private final Cache<String, List<String>> declaredTitles = CacheBuilder.newBuilder().maximumSize(2048)
            .expireAfterWrite(2, TimeUnit.HOURS).build();

    public synchronized void rememberSourceTitles(String magnet, List<String> titles) {
        String hash = identity(magnet);
        Set<String> declarations = new LinkedHashSet<>(declaredTitles.getIfPresent(hash) == null
                ? List.of() : declaredTitles.getIfPresent(hash));
        declarations.addAll(titles);
        if (declarations.size() > 64 || declarations.stream().anyMatch(title -> title.length() > 4096))
            throw invalid("磁力来源声明超出安全上限");
        declaredTitles.put(hash, List.copyOf(declarations));
    }


    public ReleaseFileTables(GuangyaClient guangya, ProwlarrClient prowlarr) {
        this.guangya = guangya;
        this.prowlarr = prowlarr;
    }

    public void remember(ProwlarrRelease release) {
        String magnet = magnet(release).orElse(null);
        if (magnet != null) rememberHash(identity(magnet), release);
        rememberHash(reference(release.indexerId(), release.downloadRef()), release);
    }

    private synchronized void rememberHash(String key, ProwlarrRelease release) {
        var existing = releases.getIfPresent(key);
        var entries = new ArrayList<>(existing == null ? List.<ProwlarrRelease>of() : existing);
        if (!entries.contains(release)) {
            if (entries.size() >= 64) throw invalid("同 hash 来源声明超出安全上限");
            entries.add(release);
        }
        releases.put(key, List.copyOf(entries));
    }

    public List<String> sourceMagnets(String magnet) {
        var sources = releases.getIfPresent(identity(magnet));
        if (sources == null) return List.of(magnet);
        return sources.stream().flatMap(release -> java.util.stream.Stream.of(release.downloadRef(), release.torrentRef(), release.guid()))
                .filter(Objects::nonNull).filter(value -> PanSouMagnetIdentity.fromUrl(value).isPresent()).distinct().toList();
    }

    public List<String> sourceTitles(String magnet) {
        var titles = new LinkedHashSet<String>();
        var declared = declaredTitles.getIfPresent(identity(magnet));
        if (declared != null) titles.addAll(declared);
        var sources = releases.getIfPresent(identity(magnet));
        if (sources != null) sources.forEach(release -> titles.add(release.title()));
        var originals = new ArrayList<>(sourceMagnets(magnet)); originals.add(magnet);
        for (String original : originals) {
            try {
                String query = java.net.URI.create(original).getRawSchemeSpecificPart();
                for (String parameter : query.substring(1).split("&")) {
                    String[] pair = parameter.split("=", 2);
                    if (pair.length == 2 && "dn".equalsIgnoreCase(pair[0]))
                        titles.add(java.net.URLDecoder.decode(pair[1], java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch (IllegalArgumentException failure) { throw invalid("磁力显示名称编码无效"); }
        }
        titles.removeIf(String::isBlank);
        return List.copyOf(titles);
    }

    public Optional<String> knownMagnet(Integer indexerId, String downloadRef) {
        if (PanSouMagnetIdentity.fromUrl(downloadRef).isPresent()) return Optional.of(downloadRef);
        String resolved = resolvedMagnets.getIfPresent(reference(indexerId, downloadRef));
        if (resolved != null) return Optional.of(resolved);
        var entries = releases.getIfPresent(reference(indexerId, downloadRef));
        return entries == null ? Optional.empty() : magnet(entries.get(0));
    }

    /** 冷缓存提交也保留刚抓取的文件表；不能只返回磁力而丢失已知内容证据。 */
    public String resolveSubmissionMagnet(Integer indexerId, String downloadRef, String title) {
        var known = knownMagnet(indexerId, downloadRef);
        if (known.isPresent()) return known.get();
        String ref = reference(indexerId, downloadRef);
        rejectUnsafe(ref);
        var remembered = releases.getIfPresent(ref);
        var release = remembered == null ? new ProwlarrRelease(title, null, null, null, null, null, null, indexerId, null, downloadRef)
                : remembered.get(0);
        remember(release);
        try {
            var result = original(release, ResolveBudget.start());
            bind(release, result);
            result.table().ifPresent(table -> retain(table.identity().btih(), new Evidence(table, "原始 torrent 文件表")));
            return result.magnet();
        } catch (GuangyaException failure) {
            if (failure.reason() == GuangyaException.Reason.INVALID_RESPONSE) unsafe.put(ref, failure.getMessage());
            throw failure;
        }
    }

    public Optional<Evidence> cached(String magnet) {
        String hash = identity(magnet);
        rejectUnsafe(hash);
        Evidence hit = tables.getIfPresent(hash);
        if (hit != null) return Optional.of(hit);
        return guangya.cached(magnet).map(table -> new Evidence(table, "光鸭文件表缓存"));
    }

    public Evidence resolve(String magnet, ResolveBudget budget) {
        String hash = identity(magnet);
        var hit = cached(magnet);
        if (hit.isPresent()) return hit.get();
        var entries = releases.getIfPresent(hash);
        return entries == null ? shared(hash, () -> fromGuangya(magnet, budget)) : resolve(entries.get(0), budget);
    }

    public Evidence resolve(ProwlarrRelease release, ResolveBudget budget) {
        remember(release);
        String magnet = magnet(release).orElse(null);
        String ref = reference(release.indexerId(), release.downloadRef());
        if (magnet == null) return shared(ref, () -> {
            ResolvedTorrent resolved = original(release, budget);
            bind(release, resolved);
            String hash = identity(resolved.magnet());
            if (resolved.table().isPresent()) return retain(hash, new Evidence(resolved.table().get(), "原始 torrent 文件表"));
            return shared(hash, () -> fromGuangya(resolved.magnet(), budget));
        });
        String hash = identity(magnet);
        return shared(hash, () -> {
            try {
                return fromGuangya(magnet, budget);
            } catch (GuangyaException failure) {
                if (failure.reason() == GuangyaException.Reason.INVALID_RESPONSE
                        || failure.reason() == GuangyaException.Reason.BUDGET_EXHAUSTED) throw failure;
                var entries = releases.getIfPresent(hash);
                String unavailable = "原始引用仅返回磁力，未取得文件表";
                for (var source : entries) {
                    try {
                        ResolvedTorrent resolved = original(source, budget);
                        if (!identity(resolved.magnet()).equals(hash)) throw invalid("原始 torrent hash 与搜索身份不一致");
                        bind(source, resolved);
                        if (resolved.table().isPresent()) return new Evidence(resolved.table().get(),
                                "原始 torrent 文件表；回退原因：" + failure.getMessage());
                    } catch (GuangyaException originalFailure) {
                        if (originalFailure.reason() == GuangyaException.Reason.INVALID_RESPONSE
                                || originalFailure.reason() == GuangyaException.Reason.BUDGET_EXHAUSTED) throw originalFailure;
                        unavailable = originalFailure.getMessage();
                    }
                }
                throw new GuangyaException(failure.reason(), failure.getMessage() + "；另一路：" + unavailable);
            }
        });
    }

    private void bind(ProwlarrRelease release, ResolvedTorrent resolved) {
        String hash = identity(resolved.magnet());
        rememberHash(hash, release);
        resolvedMagnets.put(reference(release.indexerId(), release.downloadRef()), resolved.magnet());
    }

    private ResolvedTorrent original(ProwlarrRelease release, ResolveBudget budget) {
        try (var timing = IngestStageTiming.start("evidence_request", "original torrent metadata", null)) {
            budget.acquireOriginalMetadata();
            var resolved = prowlarr.resolveTorrent(release, budget.remaining());
            timing.finish("SUCCEEDED", "originalMetadataRequests=1;tablePresent=" + resolved.table().isPresent());
            return resolved;
        } catch (ProwlarrClientException failure) {
            if (failure.getReason() == ProwlarrClientException.Reason.INVALID_RESPONSE) throw invalid("原始 torrent 文件表安全校验失败");
            throw new GuangyaException(GuangyaException.Reason.UNAVAILABLE, "原始 torrent 元数据暂不可取得");
        }
    }

    private Evidence fromGuangya(String magnet, ResolveBudget budget) {
        try (var timing = IngestStageTiming.start("evidence_request", "Guangya", null)) {
            var cached = guangya.cached(magnet);
            var table = cached.orElseGet(() -> guangya.resolve(magnet, budget));
            timing.finish("SUCCEEDED", "cacheHit=" + cached.isPresent() + ";resolveMethodCalls=" + (cached.isPresent() ? 0 : 1)
                    + ";httpRequestCount=UNKNOWN;authentication retry/shared request may differ");
            return new Evidence(table, cached.isPresent() ? "光鸭文件表缓存" : "光鸭文件表");
        }
    }

    private Evidence shared(String key, java.util.concurrent.Callable<Evidence> load) {
        rejectUnsafe(key);
        try (var timing = IngestStageTiming.start("evidence_cache", "shared file table cache", null)) {
            boolean[] loaded = {false};
            Evidence evidence = tables.get(key, () -> {
                loaded[0] = true;
                try { return load.call(); }
                catch (GuangyaException failure) {
                    if (failure.reason() == GuangyaException.Reason.INVALID_RESPONSE) unsafe.put(key, failure.getMessage());
                    throw failure;
                }
            });
            rejectUnsafe(evidence.table().identity().btih());
            timing.finish("SUCCEEDED", "cacheHitOrSharedLoad=" + !loaded[0] + ";source=" + evidence.source());
            return evidence;
        } catch (ExecutionException | com.google.common.util.concurrent.UncheckedExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException cause) throw cause;
            throw new GuangyaException(GuangyaException.Reason.UNAVAILABLE, "文件表暂不可取得");
        }
    }

    private Evidence retain(String hash, Evidence evidence) {
        rejectUnsafe(hash);
        tables.put(hash, evidence);
        return evidence;
    }
    private void rejectUnsafe(String key) {
        String reason = unsafe.getIfPresent(key);
        if (reason != null) throw invalid(reason);
    }

    static Optional<String> magnet(ProwlarrRelease release) {
        Optional<PanSouMagnetIdentity> known = PanSouMagnetIdentity.fromUrl("magnet:?xt=urn:btih:" + release.infoHash());
        String original = null;
        for (String reference : new String[] {release.downloadRef(), release.torrentRef(), release.guid()}) {
            if (reference == null || !reference.regionMatches(true, 0, "magnet:", 0, 7)) continue;
            var id = PanSouMagnetIdentity.fromUrl(reference).orElseThrow(() -> invalid("Prowlarr 磁力缺少唯一有效 BTIH"));
            if (known.isPresent() && !known.get().equals(id)) throw invalid("Prowlarr 搜索 hash 身份冲突");
            known = Optional.of(id);
            if (original == null) original = reference;
        }
        return original != null ? Optional.of(original) : known.map(id -> "magnet:?xt=urn:btih:" + id.btih());
    }
    private static String reference(Integer indexer, String ref) { return "release:" + indexer + ":" + ref; }
    private static String identity(String magnet) {
        return PanSouMagnetIdentity.fromUrl(magnet).orElseThrow(() -> invalid("磁力缺少唯一有效 BTIH")).btih();
    }
    private static GuangyaException invalid(String message) { return new GuangyaException(GuangyaException.Reason.INVALID_RESPONSE, message); }
    public record Evidence(TorrentFileTable table, String source) {}
}
