package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.dto.magnet.request.MovieMagnetIngestRequest;
import com.medianexus.orchestrator.dto.magnet.request.SeriesMagnetIngestRequest;
import com.medianexus.orchestrator.dto.resources.request.MovieReleaseOpenListIngestRequest;
import com.medianexus.orchestrator.dto.resources.request.SeriesReleaseOpenListIngestRequest;
import com.medianexus.orchestrator.dto.taskcenter.request.OpenListManualMagnetRetryRequest;
import com.medianexus.orchestrator.dto.taskcenter.request.OpenListReleaseRetryRequest;
import com.medianexus.orchestrator.integration.guangya.GuangyaClient;
import com.medianexus.orchestrator.integration.guangya.GuangyaException;
import com.medianexus.orchestrator.integration.guangya.TorrentFileTable;
import com.medianexus.orchestrator.integration.pansou.PanSouMagnetIdentity;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReleaseSubmissionSafetyTest {
    private static final String MAGNET = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567";
    private final GuangyaClient client = mock(GuangyaClient.class);
    private final ReleaseTarget movie = ReleaseTarget.of("Arrival", null, null, 2016, null, null);
    private final ReleaseTarget series = ReleaseTarget.of("Show", null, null, null, 1, null);
    private ReleaseSubmissionSafety safety;

    @BeforeEach
    void setUp() {
        safety = new ReleaseSubmissionSafety(client,
                new ReleaseFileAssessment(new MovieSeriesFileRenameService(), new ReleaseTitleTagParser()));
        when(client.cached(anyString())).thenReturn(Optional.empty());
        doThrow(new GuangyaException(GuangyaException.Reason.NOT_CONFIGURED, "未配置"))
                .when(client).resolve(anyString(), any());
    }

    @Test
    void unknownRequiresExplicitPermissionAndNeverFabricatesFiles() {
        assertThatThrownBy(() -> safety.check(MAGNET, movie, ReleaseIngestMetadata.manual()))
                .hasMessageContaining("accept_unverified_risk");
        assertThat(safety.check(MAGNET, movie, ReleaseIngestMetadata.manual().withUnverifiedRisk(true)))
                .contains("UNVERIFIED").contains("显式接受");
    }

    @Test
    void invalidMagnetAndInvalidResponseCannotBeAcknowledgedOrForgotten() {
        var accepted = ReleaseIngestMetadata.manual().withUnverifiedRisk(true);
        assertThatThrownBy(() -> safety.check("magnet:?xt=urn:btih:bad", movie, accepted))
                .hasMessageContaining("BTIH");
        doThrow(new GuangyaException(GuangyaException.Reason.INVALID_RESPONSE, "非法相对文件路径"))
                .when(client).resolve(anyString(), any());
        assertThatThrownBy(() -> safety.check(MAGNET, movie, accepted)).hasMessageContaining("非法相对文件路径");
        doThrow(new GuangyaException(GuangyaException.Reason.UNAVAILABLE, "超时"))
                .when(client).resolve(anyString(), any());
        assertThatThrownBy(() -> safety.check(MAGNET, movie, accepted)).hasMessageContaining("非法相对文件路径");
    }

    @Test
    void knownReleaseConflictsAndCameraAreCheckedEvenWithoutResolver() {
        for (String title : List.of("Arrival 2017 1080p", "Arrival 2016 CAM 1080p")) {
            setUp();
            assertThatThrownBy(() -> safety.check(MAGNET, movie, metadata(title, true)))
                    .hasMessageNotContaining("accept_unverified_risk");
        }
        setUp();
        assertThatThrownBy(() -> safety.check(MAGNET, series, metadata("Show S02 1080p", true)))
                .hasMessageContaining("季");
    }

    @Test
    void sourceConflictSurvivesSameHashRawMagnetSubmission() {
        assertThatThrownBy(() -> safety.check(MAGNET, movie, metadata("Arrival 2017 1080p", true)))
                .hasMessageContaining("年份");
        assertThatThrownBy(() -> safety.check(MAGNET, movie, ReleaseIngestMetadata.manual().withUnverifiedRisk(true)))
                .hasMessageContaining("年份");
    }

    @Test
    void unsafeExecutionIsNotAnUnverifiedRisk() {
        cacheTable("Show S01 1080p", "Show S01E01 1080p.mkv", "unknown.mp4");
        assertThatThrownBy(() -> safety.check(MAGNET, series, ReleaseIngestMetadata.manual().withUnverifiedRisk(true)))
                .hasMessageContaining("不能安全执行");
    }

    @Test
    void resolvedMovieAndMixedPreviewSeasonKeepOriginalNamesAndVersionEvidence() {
        cacheTable("Arrival 2016 1080p", "Arrival 2016 1080p.mkv");
        assertThat(safety.check(MAGNET, movie, ReleaseIngestMetadata.manual()))
                .contains("RECOMMENDABLE", "Arrival 2016 1080p.mkv");
        cacheTable("Show S01 1080p", "Show S01E01 1080p.mkv", "Show S01E02 尝鲜版 1080p.ts");
        assertThat(safety.check(MAGNET, series, ReleaseIngestMetadata.manual()))
                .contains("RECOMMENDABLE", "preview=true", "Show S01E02 尝鲜版 1080p.ts");
    }

    @Test
    void magnetDisplayNameAlsoCarriesNonBypassableEvidence() {
        assertThatThrownBy(() -> safety.check(MAGNET + "&dn=Arrival.2016.CAM", movie,
                ReleaseIngestMetadata.manual().withUnverifiedRisk(true))).hasMessageContaining("枪版");
    }

    @Test
    void comparisonSafetyFailuresAndSourceTitlesAreSharedWithRawSubmission() {
        safety.rememberInvalidFileTable(MAGNET);
        assertThatThrownBy(() -> safety.check(MAGNET, movie, ReleaseIngestMetadata.manual().withUnverifiedRisk(true)))
                .hasMessageContaining("文件表安全校验失败");
        setUp();
        safety.rememberSourceTitles(MAGNET, List.of("Arrival 2017 1080p"));
        assertThatThrownBy(() -> safety.check(MAGNET, movie, ReleaseIngestMetadata.manual().withUnverifiedRisk(true)))
                .hasMessageContaining("年份");
    }


    @Test
    void requestJsonDefaultsAndExplicitPermissionsAndLegacyConstructors() throws Exception {
        ObjectMapper json = new ObjectMapper();
        for (Class<?> type : List.of(MovieMagnetIngestRequest.class, SeriesMagnetIngestRequest.class,
                MovieReleaseOpenListIngestRequest.class, SeriesReleaseOpenListIngestRequest.class,
                OpenListManualMagnetRetryRequest.class, OpenListReleaseRetryRequest.class)) {
            Object absent = json.readValue("{}", type);
            Object explicit = json.readValue("{\"accept_unverified_risk\":true}", type);
            assertThat(type.getMethod("acceptUnverifiedRisk").invoke(absent)).isEqualTo(false);
            assertThat(type.getMethod("acceptUnverifiedRisk").invoke(explicit)).isEqualTo(true);
        }
        assertThat(new MovieMagnetIngestRequest(MAGNET, "Arrival", null, 2016, null).acceptUnverifiedRisk()).isFalse();
        assertThat(new OpenListManualMagnetRetryRequest(MAGNET).acceptUnverifiedRisk()).isFalse();
    }

    private ReleaseIngestMetadata metadata(String title, boolean accepted) {
        return new ReleaseIngestMetadata("PROWLARR", title, null, null, 1, "opaque", List.of("1080p"), List.of())
                .withUnverifiedRisk(accepted);
    }

    private void cacheTable(String root, String... names) {
        var entries = java.util.Arrays.stream(names).map(name -> new TorrentFileTable.Entry(name, 100L)).toList();
        when(client.cached(MAGNET)).thenReturn(Optional.of(new TorrentFileTable(
                PanSouMagnetIdentity.fromUrl(MAGNET).orElseThrow(), root, entries, entries.size() * 100L)));
    }
}
