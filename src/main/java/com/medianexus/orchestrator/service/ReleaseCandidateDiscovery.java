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

    public List<Found> search(String query) {
        if (settings.usesPanSouForMagnetSearch()) return panSou(panSou.searchMagnets(new PanSouSearchCommand(query, false)), query);
        return prowlarr(prowlarr.search(query), query);
    }

    public List<Found> refresh(String query) {
        if (settings.usesPanSouForMagnetSearch()) return panSou(panSou.searchMagnets(new PanSouSearchCommand(query, true)), query);
        return prowlarr(prowlarr.refreshQueries(List.of(query)).get(query), query);
    }

    private List<Found> panSou(PanSouSearchResult result, String query) {
        return result.entries().stream().filter(entry -> PanSouMagnetIdentity.fromUrl(entry.url()).isPresent()).map(entry -> {
            var parsed = tags.parse(entry.note());
            var item = new ProwlarrReleaseItemResponse(entry.note(), entry.sizeBytes(), null, null, null,
                    entry.source(), entry.datetime(), null, entry.url(), parsed.resolutionTags(), parsed.dynamicRangeTags(),
                    parsed.seasonTags(), "PanSou；来源报告体积/画质/字幕为声明；做种与可下载性未知", query, List.of(),
                    ReleaseFileEvidence.unverified("排队等待文件表补证"));
            return new Found(PanSouMagnetIdentity.fromUrl(entry.url()).orElseThrow().btih(), item, null, entry.sizeConflict());
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

    public record Found(String identity, ProwlarrReleaseItemResponse item, ProwlarrRelease release, boolean sizeConflict) {}
}
