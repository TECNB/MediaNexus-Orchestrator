package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.medianexus.orchestrator.integration.guangya.*;
import com.medianexus.orchestrator.integration.pansou.PanSouMagnetIdentity;
import com.medianexus.orchestrator.integration.prowlarr.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReleaseFileTablesTest {
    private static final String MAGNET = "magnet:?xt=urn:btih:5307e0ac501eabb9891893122c91dfeacc8cf7ec";

    @Test void rejectedGuangyaResourceFallsBackToOriginalTorrentAndSubmissionReusesTable() {
        var guangya = mock(GuangyaClient.class);
        var prowlarr = mock(ProwlarrClient.class);
        var tables = new ReleaseFileTables(guangya, prowlarr);
        var release = new ProwlarrRelease("Chernobyl S01 2019 1080p", 100L, 5, null, null,
                "Indexer", null, 1, "guid", MAGNET, "5307e0ac501eabb9891893122c91dfeacc8cf7ec", "opaque");
        var table = new TorrentFileTable(PanSouMagnetIdentity.fromUrl(MAGNET).orElseThrow(), "Chernobyl S01 1080p",
                List.of(new TorrentFileTable.Entry("Chernobyl S01E01 1080p.mkv", 100L)), 100L);
        when(guangya.resolve(eq(MAGNET), any())).thenThrow(new GuangyaException(GuangyaException.Reason.RESOURCE_REJECTED, "光鸭拒绝解析：文件违规（353）"));
        when(prowlarr.resolveTorrent(eq(release), any())).thenReturn(new ResolvedTorrent(MAGNET, Optional.of(table)));
        var evidence = tables.resolve(release, ResolveBudget.start());
        assertThat(evidence.table()).isEqualTo(table);
        assertThat(evidence.source()).contains("原始 torrent", "353");
        assertThat(tables.resolve(MAGNET, ResolveBudget.start())).isEqualTo(evidence);
        var safety = new ReleaseSubmissionSafety(tables,
                new ReleaseFileAssessment(new MovieSeriesFileRenameService(), new ReleaseTitleTagParser()));
        assertThat(safety.check(MAGNET, ReleaseTarget.of("Chernobyl", null, null, null, 1, "1080p"),
                ReleaseIngestMetadata.manual())).contains("RECOMMENDABLE", "原始 torrent", "353");
        verify(guangya, times(1)).resolve(anyString(), any());
        verify(prowlarr, times(1)).resolveTorrent(any(), any());
    }

    @Test void originalReferenceMagnetUsesGuangyaAndRemembersIdentity() {
        var guangya = mock(GuangyaClient.class); var prowlarr = mock(ProwlarrClient.class);
        var tables = new ReleaseFileTables(guangya, prowlarr);
        var release = new ProwlarrRelease("Chernobyl S01 1080p", 100L, 5, null, null,
                "Indexer", null, 1, "guid", "opaque");
        when(prowlarr.resolveTorrent(eq(release), any())).thenReturn(new ResolvedTorrent(MAGNET, Optional.empty()));
        when(guangya.resolve(eq(MAGNET), any())).thenReturn(table());
        assertThat(tables.resolve(release, ResolveBudget.start()).source()).contains("光鸭");
        assertThat(tables.knownMagnet(1, "opaque")).contains(MAGNET);
        tables.resolve(release, ResolveBudget.start());
        verify(prowlarr, times(1)).resolveTorrent(any(), any());
        verify(guangya, times(1)).resolve(anyString(), any());
    }

    @Test void knownInvalidTableCannotBeWashedByFallbackOrNextRawCall() {
        var guangya = mock(GuangyaClient.class); var prowlarr = mock(ProwlarrClient.class);
        var tables = new ReleaseFileTables(guangya, prowlarr);
        var release = release();
        when(guangya.resolve(eq(MAGNET), any())).thenThrow(new GuangyaException(GuangyaException.Reason.INVALID_RESPONSE, "非法路径"));
        assertThatThrownBy(() -> tables.resolve(release, ResolveBudget.start())).hasMessageContaining("非法路径");
        when(guangya.resolve(eq(MAGNET), any())).thenReturn(table());
        assertThatThrownBy(() -> tables.resolve(MAGNET, ResolveBudget.start())).hasMessageContaining("非法路径");
        verifyNoInteractions(prowlarr);
    }

    @Test void bothUnavailableKeepBothReasonsAndNeverCreateAFileTable() {
        var guangya = mock(GuangyaClient.class); var prowlarr = mock(ProwlarrClient.class);
        var tables = new ReleaseFileTables(guangya, prowlarr);
        when(guangya.resolve(eq(MAGNET), any())).thenThrow(new GuangyaException(GuangyaException.Reason.RESOURCE_REJECTED, "文件违规（353）"));
        when(prowlarr.resolveTorrent(any(), any())).thenThrow(new ProwlarrClientException(ProwlarrClientException.Reason.UPSTREAM, "offline"));
        assertThatThrownBy(() -> tables.resolve(release(), ResolveBudget.start())).hasMessageContaining("353").hasMessageContaining("原始 torrent");
        assertThat(tables.cached(MAGNET)).isEmpty();
    }

    @Test void coldSubmissionRetainsUnsafeContentAndOriginalMagnetDeclarations() {
        var guangya = mock(GuangyaClient.class); var prowlarr = mock(ProwlarrClient.class);
        var tables = new ReleaseFileTables(guangya, prowlarr);
        var unsafeContent = new TorrentFileTable(PanSouMagnetIdentity.fromUrl(MAGNET).orElseThrow(), "Chernobyl S01-S02 1080p",
                List.of(new TorrentFileTable.Entry("Chernobyl S01E01 1080p.mkv", 100L),
                        new TorrentFileTable.Entry("Chernobyl S02E01 1080p.mkv", 100L)), 200L);
        when(prowlarr.resolveTorrent(any(), any())).thenReturn(new ResolvedTorrent(MAGNET, Optional.of(unsafeContent)));
        assertThat(tables.resolveSubmissionMagnet(1, "cold-ref", "Chernobyl S01 1080p")).isEqualTo(MAGNET);
        var safety = new ReleaseSubmissionSafety(tables, new ReleaseFileAssessment(new MovieSeriesFileRenameService(), new ReleaseTitleTagParser()));
        assertThatThrownBy(() -> safety.check(MAGNET, ReleaseTarget.of("Chernobyl", null, null, null, 1, "1080p"),
                ReleaseIngestMetadata.manual().withUnverifiedRisk(true))).hasMessageNotContaining("accept_unverified_risk");
        String camera = MAGNET + "&dn=Chernobyl.2019.CAM";
        assertThat(tables.resolveSubmissionMagnet(null, camera, "Chernobyl")).isEqualTo(camera);
        assertThatThrownBy(() -> safety.check(camera, ReleaseTarget.of("Chernobyl", null, null, 2019, null, null),
                ReleaseIngestMetadata.manual().withUnverifiedRisk(true))).hasMessageContaining("枪版");
    }

    @Test void conflictingXtCannotBeFilledBySearchHashAndBackupReferencesAreRetained() {
        var guangya = mock(GuangyaClient.class); var prowlarr = mock(ProwlarrClient.class);
        var tables = new ReleaseFileTables(guangya, prowlarr);
        var conflict = new ProwlarrRelease("Chernobyl", null, 5, null, null, "A", null, 1, null,
                MAGNET + "&xt=urn:btih:0000000000000000000000000000000000000000", "5307e0ac501eabb9891893122c91dfeacc8cf7ec", null);
        assertThatThrownBy(() -> tables.resolve(conflict, ResolveBudget.start())).hasMessageContaining("唯一有效 BTIH");
        var direct = new ProwlarrRelease("Chernobyl S01 1080p", 100L, 0, null, null, "A", null, 1, null, MAGNET);
        var backup = new ProwlarrRelease("Chernobyl S01 1080p", 100L, 10, null, null, "B", null, 2, null, MAGNET, null, "backup");
        tables.remember(direct); tables.remember(backup);
        when(guangya.resolve(eq(MAGNET), any())).thenThrow(new GuangyaException(GuangyaException.Reason.RESOURCE_REJECTED, "353"));
        when(prowlarr.resolveTorrent(eq(direct), any())).thenReturn(new ResolvedTorrent(MAGNET, Optional.empty()));
        when(prowlarr.resolveTorrent(eq(backup), any())).thenReturn(new ResolvedTorrent(MAGNET, Optional.of(table())));
        assertThat(tables.resolve(direct, ResolveBudget.start()).table()).isEqualTo(table());
        verify(prowlarr).resolveTorrent(eq(backup), any());
    }

    private ProwlarrRelease release() {
        return new ProwlarrRelease("Chernobyl S01 1080p", 100L, 5, null, null,
                "Indexer", null, 1, "guid", MAGNET, null, "opaque");
    }
    private TorrentFileTable table() {
        return new TorrentFileTable(PanSouMagnetIdentity.fromUrl(MAGNET).orElseThrow(), "Chernobyl S01 1080p",
                List.of(new TorrentFileTable.Entry("Chernobyl S01E01 1080p.mkv", 100L)), 100L);
    }
}
