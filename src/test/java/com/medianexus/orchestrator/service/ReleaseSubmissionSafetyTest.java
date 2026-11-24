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
        safety = new ReleaseSubmissionSafety(new ReleaseFileTables(client, mock(com.medianexus.orchestrator.integration.prowlarr.ProwlarrClient.class)),
                new ReleaseFileAssessment(new MovieSeriesFileRenameService(), new ReleaseTitleTagParser()));
        when(client.cached(anyString())).thenReturn(Optional.empty());
        doThrow(new GuangyaException(GuangyaException.Reason.NOT_CONFIGURED, "未配置"))
                .when(client).resolve(anyString(), any());
    }

    private static final String HASH = "0123456789abcdef0123456789abcdef01234567";

    private String originalMovieEvidence() {
        return "RECOMMENDABLE; reason=原提交已核实; preview=false; camera=false; root=Arrival 2016 1080p; sourceTitles=[Arrival 2016 1080p]; file_table_source=光鸭文件表"
                + "\noriginal_file=Arrival.2016.1080p.mkv; bytes=2000000000; preview=false; camera=false";
    }

    @Test void organizationUsesReassessedOriginalTableWhenLiveSourceUnavailableWithoutDownloading() {
        doThrow(new GuangyaException(GuangyaException.Reason.UNAVAILABLE, "temporary" )).when(client).resolve(anyString(), any());
        String record = safety.checkMovieOrganizationRecovery(null, MAGNET, HASH, movie, ReleaseIngestMetadata.manual(), originalMovieEvidence());
        assertThat(record).contains("RECOMMENDABLE", "原任务持久文件表", "live=UNAVAILABLE", "bytes=2000000000");
        verify(client, times(1)).resolve(anyString(), any());
        // Normal admission still refuses missing current evidence, with no risk override introduced.
        assertThatThrownBy(() -> safety.check(MAGNET, movie, ReleaseIngestMetadata.manual())).hasMessageContaining("accept_unverified_risk");
    }

    @Test void freshConflictingOrInvalidTableNeverFallsBackToAcceptedHistoricalTable() {
        cacheTable("Arrival 2017 1080p", "Arrival 2017 1080p.mkv");
        assertThatThrownBy(() -> safety.checkMovieOrganizationRecovery(null, MAGNET, HASH, movie, ReleaseIngestMetadata.manual(), originalMovieEvidence()))
                .hasMessageContaining("年份");
        setUp();
        doThrow(new GuangyaException(GuangyaException.Reason.INVALID_RESPONSE, "invalid")).when(client).resolve(anyString(), any());
        assertThatThrownBy(() -> safety.checkMovieOrganizationRecovery(null, MAGNET, HASH, movie, ReleaseIngestMetadata.manual(), originalMovieEvidence()))
                .hasMessageContaining("RECOVERY_LIVE_INVALID_RESPONSE");
        doThrow(new GuangyaException(GuangyaException.Reason.UNAVAILABLE, "temporary")).when(client).resolve(anyString(), any());
        assertThatThrownBy(() -> safety.checkMovieOrganizationRecovery(null, MAGNET, HASH, movie, ReleaseIngestMetadata.manual(), originalMovieEvidence()))
                .hasMessageContaining("INVALID_RESPONSE");
    }

    @Test void historicalTableMustBeRecommendableCompleteAndBoundToStoredTaskHash() {
        for (String record : List.of("UNVERIFIED; root=Arrival", "RECOMMENDABLE; root=Arrival", originalMovieEvidence().replace("bytes=2000000000", "bytes=-1"),
                originalMovieEvidence().replace("Arrival.2016.1080p.mkv", "../Arrival.2016.1080p.mkv"))) {
            setUp();
            assertThatThrownBy(() -> safety.checkMovieOrganizationRecovery(null, MAGNET, HASH, movie, ReleaseIngestMetadata.manual(), record))
                    .hasMessageContaining("RECOVERY_ORIGINAL");
        }
        assertThatThrownBy(() -> safety.checkMovieOrganizationRecovery(null, MAGNET, "f".repeat(40), movie, ReleaseIngestMetadata.manual(), originalMovieEvidence()))
                .hasMessageContaining("HASH_MISMATCH");
        setUp();
        assertThatThrownBy(() -> safety.checkMovieOrganizationRecovery(null, MAGNET, HASH, movie, ReleaseIngestMetadata.manual(),
                originalMovieEvidence().replace("Arrival 2016", "Arrival 2017"))).hasMessageContaining("年份");
    }

    @Test void historicalAcceptanceCannotWashCachedCameraOrFreshUnverifiedIdentity() {
        safety.rememberInvalidFileTable(MAGNET);
        assertThatThrownBy(() -> safety.checkMovieOrganizationRecovery(null, MAGNET, HASH, movie, ReleaseIngestMetadata.manual(), originalMovieEvidence()))
                .hasMessageContaining("文件表安全校验失败");
        setUp();
        cacheTable("Arrival 2016 CAM 1080p", "Arrival.2016.CAM.1080p.mkv");
        assertThatThrownBy(() -> safety.checkMovieOrganizationRecovery(null, MAGNET, HASH, movie, ReleaseIngestMetadata.manual(), originalMovieEvidence()))
                .hasMessageContaining("枪版");
        setUp();
        cacheTable("Unrelated 2016 1080p", "Unrelated.2016.1080p.mkv");
        assertThatThrownBy(() -> safety.checkMovieOrganizationRecovery(null, MAGNET, HASH, movie, ReleaseIngestMetadata.manual(), originalMovieEvidence()))
                .hasMessageContaining("未核验");
    }

    @Test void organizationSeriesReassessesAllOriginalEpisodeFiles() {
        StringBuilder record = new StringBuilder("RECOMMENDABLE; reason=已核实; preview=false; camera=false; root=Show S01 1080p; sourceTitles=[Show S01 1080p]; file_table_source=光鸭文件表");
        for (int episode = 1; episode <= 30; episode++) record.append("\noriginal_file=%02d.1080p.mkv; bytes=%d; preview=false; camera=false".formatted(episode, 200000000L + episode));
        assertThat(safety.checkSeriesOrganizationRecovery(null, MAGNET, HASH, series, ReleaseIngestMetadata.manual(), record.toString()))
                .contains("RECOMMENDABLE", "original_file=30.1080p.mkv");
        setUp();
        assertThatThrownBy(() -> safety.checkSeriesOrganizationRecovery(null, MAGNET, HASH, series, ReleaseIngestMetadata.manual(),
                record.toString().replace("30.1080p.mkv", "S02E30.1080p.mkv"))).hasMessageContaining("不能安全执行");
    }

    @Test void safeRecoveryDiagnosticContainsReasonButNeverSignedUrlsOrTokens() {
        var rejection = new com.medianexus.orchestrator.common.exception.BusinessException(com.medianexus.orchestrator.common.exception.ErrorCode.BAD_REQUEST,
                "SOURCE_UNAVAILABLE https://dl.mypikpak.net/f?sign=TOPSECRET magnet:?xt=SECRET token=HIDDEN", org.springframework.http.HttpStatus.BAD_REQUEST);
        String safe = ReleaseSubmissionSafety.organizationRejectionReason(rejection);
        assertThat(safe).contains("SAFETY_REJECTED", "SOURCE_UNAVAILABLE").doesNotContain("https://", "magnet:", "TOPSECRET", "HIDDEN", "xt=SECRET");
        assertThat(safe.length()).isLessThan(280);
    }

    @Test
    void unknownRequiresExplicitPermissionAndNeverFabricatesFiles() {
        assertThatThrownBy(() -> safety.check(MAGNET, movie, ReleaseIngestMetadata.manual()))
                .hasMessageContaining("accept_unverified_risk");
        assertThat(safety.check(MAGNET, movie, ReleaseIngestMetadata.manual().withUnverifiedRisk(true)))
                .contains("UNVERIFIED").contains("显式接受");
    }

    @Test void textualSeasonDeclarationsCannotPassMovieSubmission() {
        for (String season : List.of("第一季", "2nd Season")) {
            assertThatThrownBy(() -> safety.check(MAGNET, movie,
                    new ReleaseIngestMetadata("MANUAL_MAGNET", "Arrival 2016 " + season + " 1080p", null, null, null, null, List.of(), List.of(), true)))
                    .hasMessageContaining("季度");
        }
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

    @Test void movieSmallSidecarsPassButUnknownLargeVideosCannotBeAcknowledged() {
        var entries = List.of(new TorrentFileTable.Entry("Arrival.2016.1080p.mkv", 2000000000L),
                new TorrentFileTable.Entry("unknown.MP4", 296080),
                new TorrentFileTable.Entry("unknown.MKV", 636976));
        when(client.cached(MAGNET)).thenReturn(Optional.of(new TorrentFileTable(
                PanSouMagnetIdentity.fromUrl(MAGNET).orElseThrow(), "Arrival 2016 1080p", entries, 2000933056L)));
        assertThat(safety.check(MAGNET, movie, ReleaseIngestMetadata.manual())).contains("RECOMMENDABLE");
        cacheTable("Arrival 2016 1080p", "Arrival 2016 1080p.mkv", "unknown.mkv");
        assertThatThrownBy(() -> safety.check(MAGNET, movie, ReleaseIngestMetadata.manual().withUnverifiedRisk(true)))
                .hasMessageContaining("不能安全执行");
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
        var entries = java.util.Arrays.stream(names).map(name -> new TorrentFileTable.Entry(name, 100L * 1024 * 1024)).toList();
        when(client.cached(MAGNET)).thenReturn(Optional.of(new TorrentFileTable(
                PanSouMagnetIdentity.fromUrl(MAGNET).orElseThrow(), root, entries, entries.size() * 100L * 1024 * 1024)));
    }
}
