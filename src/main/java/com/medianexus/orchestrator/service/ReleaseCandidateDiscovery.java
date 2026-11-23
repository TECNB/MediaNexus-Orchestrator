package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.dto.resources.response.*;
import com.medianexus.orchestrator.integration.pansou.*;
import com.medianexus.orchestrator.integration.prowlarr.ProwlarrRelease;
import java.util.List;
import org.springframework.stereotype.Component;

/** 搜索来源适配：保留发布引用和来源声明，文件表内容结论交给共同比较。 */
@Component
public class ReleaseCandidateDiscovery {
    private final PanSouClient panSou;
    private final ProwlarrReleaseSearchCache prowlarr;
    private final SettingsService settings;
    private final ReleaseTitleTagParser tags;
    private final ReleaseFileTables tables;

    public ReleaseCandidateDiscovery(PanSouClient panSou, ProwlarrReleaseSearchCache prowlarr,
            SettingsService settings, ReleaseTitleTagParser tags, ReleaseFileTables tables) {
        this.panSou = panSou;
        this.prowlarr = prowlarr;
        this.settings = settings;
        this.tags = tags;
        this.tables = tables;
    }

    public String moviePrimaryQuery(String title, Integer year) {
        return settings.usesPanSouForMagnetSearch() ? title : title + " " + year;
    }

    public List<Found> search(String query) {
        if (settings.usesPanSouForMagnetSearch()) return timedSearch("PanSou aggregate", query,
                () -> panSou(panSou.searchMagnets(new PanSouSearchCommand(query, false)), query));
        return timedSearch("Prowlarr aggregate/cache", query, () -> prowlarr(prowlarr.search(query), query));
    }

    public List<Found> refresh(String query) {
        if (settings.usesPanSouForMagnetSearch()) return timedSearch("PanSou aggregate refresh", query,
                () -> panSou(panSou.searchMagnets(new PanSouSearchCommand(query, true)), query));
        return timedSearch("Prowlarr aggregate refresh", query, () -> prowlarr(prowlarr.refreshQueries(List.of(query)).get(query), query));
    }

    private List<Found> panSou(PanSouSearchResult result, String query) {
        return result.entries().stream().filter(entry -> PanSouMagnetIdentity.fromUrl(entry.url()).isPresent()).map(entry -> {
            tables.rememberSourceTitles(entry.url(), entry.sourceTitles());
            var parsed = tags.parse(entry.note());
            var item = new ProwlarrReleaseItemResponse(entry.note(), entry.sizeBytes(), null, null, null,
                    entry.source(), entry.datetime(), null, entry.url(), parsed.resolutionTags(), parsed.dynamicRangeTags(),
                    parsed.seasonTags(), "PanSou；来源报告体积/画质/字幕为声明；做种与可下载性未知", query, List.of(),
                    ReleaseFileEvidence.unverified("排队等待文件表补证"));
            return new Found(PanSouMagnetIdentity.fromUrl(entry.url()).orElseThrow().btih(), item, null, entry.sizeConflict(), entry.sourceTitles());
        }).toList();
    }

    private List<Found> prowlarr(List<ProwlarrRelease> releases, String query) {
        return releases.stream().map(release -> {
            tables.remember(release);
            var parsed = tags.parse(release.title());
            var item = new ProwlarrReleaseItemResponse(release.title(), release.size(), release.seeders(), release.leechers(),
                    release.grabs(), release.indexer(), release.publishDate(), release.indexerId(), release.downloadRef(),
                    parsed.resolutionTags(), parsed.dynamicRangeTags(), parsed.seasonTags(),
                    "Prowlarr；容量/做种为索引器报告，目标内容等待文件表核验", query, List.of(),
                    ReleaseFileEvidence.unverified("排队等待文件表补证"));
            String identity = ReleaseFileTables.magnet(release).flatMap(PanSouMagnetIdentity::fromUrl)
                    .map(PanSouMagnetIdentity::btih).orElse("release:" + release.indexerId() + ":" + release.downloadRef());
            return new Found(identity, item, release, false);
        }).toList();
    }

    public record Found(String identity, ProwlarrReleaseItemResponse item, ProwlarrRelease release, boolean sizeConflict,
                        List<String> sourceTitles) {
        public Found { sourceTitles = List.copyOf(sourceTitles); }
        public Found(String identity, ProwlarrReleaseItemResponse item, ProwlarrRelease release, boolean sizeConflict) {
            this(identity, item, release, sizeConflict, List.of());
        }
    }

    public List<Found> supplement(String query) {
        try {
            return settings.usesPanSouForMagnetSearch() ? timedSearch("Prowlarr aggregate/cache", query, () -> prowlarr(prowlarr.search(query), query))
                    : timedSearch("PanSou aggregate", query, () -> panSou(panSou.searchMagnets(new PanSouSearchCommand(query, false)), query));
        } catch (com.medianexus.orchestrator.integration.prowlarr.ProwlarrClientException failure) {
            if (failure.getReason() == com.medianexus.orchestrator.integration.prowlarr.ProwlarrClientException.Reason.CONFIGURATION) return List.of();
            throw failure;
        } catch (PanSouClientException failure) {
            if (failure.getReason() == PanSouClientException.Reason.CONFIGURATION) return List.of();
            throw failure;
        }
    }

    public List<Found> refreshSupplement(String query) {
        try {
            return settings.usesPanSouForMagnetSearch() ? timedSearch("Prowlarr aggregate refresh", query,
                    () -> prowlarr(prowlarr.refreshQueries(List.of(query)).get(query), query))
                    : timedSearch("PanSou aggregate refresh", query, () -> panSou(panSou.searchMagnets(new PanSouSearchCommand(query, true)), query));
        } catch (com.medianexus.orchestrator.integration.prowlarr.ProwlarrClientException failure) {
            if (failure.getReason() == com.medianexus.orchestrator.integration.prowlarr.ProwlarrClientException.Reason.CONFIGURATION) return List.of();
            throw failure;
        } catch (PanSouClientException failure) {
            if (failure.getReason() == PanSouClientException.Reason.CONFIGURATION) return List.of();
            throw failure;
        }
    }
    private List<Found> timedSearch(String source, String query, java.util.function.Supplier<List<Found>> search) {
        try (var timing = IngestStageTiming.start("search_query", source, query)) {
            try {
                List<Found> found = search.get();
                timing.finish("SUCCEEDED", "raw=" + found.size() + ";sources=" + found.stream()
                        .map(entry -> entry.item().indexer()).distinct().toList() + ";cache/request counts UNKNOWN at aggregate boundary");
                return found;
            } catch (RuntimeException failure) {
                timing.finish("FAILED", failure.getClass().getSimpleName());
                throw failure;
            }
        }
    }
}
