package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.medianexus.orchestrator.config.QasProperties;
import com.medianexus.orchestrator.config.TmdbProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.dto.quark.request.QuarkFileSelectionRequest;
import com.medianexus.orchestrator.dto.quark.request.QuarkMultiSourceRequest;
import com.medianexus.orchestrator.dto.quark.request.QuarkSourceSelectionRequest;
import com.medianexus.orchestrator.dto.quark.response.QuarkMultiSourcePreviewResponse;
import com.medianexus.orchestrator.integration.qas.QasClient;
import com.medianexus.orchestrator.integration.qas.QasShareNode;
import com.medianexus.orchestrator.integration.qas.QasShareTree;
import com.medianexus.orchestrator.integration.tmdb.TmdbClient;
import com.medianexus.orchestrator.mapper.QuarkIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.QuarkIngestTaskMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuarkMultiSourceIngestServiceTest {

    @Test
    void explainsWhyAZipOnlyShareCannotBePlanned() {
        String shareUrl = "https://pan.quark.cn/s/share123";
        QuarkShareTreeService shareTreeService = mock(QuarkShareTreeService.class);
        when(shareTreeService.inspectShare(shareUrl)).thenReturn(new QasShareTree(
                shareUrl,
                List.of(directory("outer", "动画", directory(
                        "range", "100-200", file("201.zip"), file("202.zip")
                )))
        ));
        QasProperties qasProperties = new QasProperties();
        qasProperties.setVarietyRootPath("/Variety");
        QuarkMultiSourceIngestService service = new QuarkMultiSourceIngestService(
                mock(QasClient.class),
                shareTreeService,
                qasProperties,
                new TmdbProperties(),
                mock(AuthService.class),
                mock(QuarkIngestPlanner.class),
                mock(TmdbClient.class),
                new QuarkShareSourceRegistry(),
                mock(QuarkIngestTaskMapper.class),
                mock(QuarkIngestTaskLogMapper.class)
        );

        QuarkMultiSourcePreviewResponse response = service.previewStructure(
                new QuarkMultiSourceRequest(
                        shareUrl, "测试动画", null, null, null, false, List.of()
                ),
                "VARIETY"
        );

        assertThat(response.ready()).isFalse();
        assertThat(response.sources()).isEmpty();
        assertThat(response.message()).isEqualTo(
                "未发现可播放视频；检测到 2 个 ZIP 压缩包，当前链路不支持解压后入库"
        );
    }

    @Test
    void explainsDuplicateEpisodeAndAcceptsManualEpisodeCorrection() {
        String shareUrl = "https://pan.quark.cn/s/share123";
        QasShareTree tree = duplicateEpisodeTree(shareUrl);
        QuarkMultiSourceIngestService service = serviceFor(tree, shareUrl);

        QuarkMultiSourcePreviewResponse structure = service.previewStructure(
                request(shareUrl, null, List.of()), "SERIES"
        );
        String candidateId = structure.sources().get(0).sourceCandidateId();
        QuarkMultiSourcePreviewResponse blocked = service.previewPlan(
                request(shareUrl, structure.previewId(), List.of(
                        new QuarkSourceSelectionRequest(candidateId, 1, false, false)
                )),
                "SERIES"
        );

        assertThat(blocked.ready()).isFalse();
        assertThat(blocked.sources().get(0).files())
                .filteredOn(file -> "CONFLICT".equals(file.status()))
                .extracting(file -> file.sourceName())
                .containsExactlyInAnyOrder("S01E06.mkv", "S01E06 (1).mkv");
        String duplicateFileId = blocked.sources().get(0).files().stream()
                .filter(file -> file.sourceName().equals("S01E06 (1).mkv"))
                .findFirst()
                .orElseThrow()
                .fileId();

        QuarkMultiSourcePreviewResponse corrected = service.previewPlan(
                request(shareUrl, structure.previewId(), List.of(
                        new QuarkSourceSelectionRequest(candidateId, 1, false, false, List.of(
                                new QuarkFileSelectionRequest(duplicateFileId, 5, false)
                        ))
                )),
                "SERIES"
        );

        assertThat(corrected.ready()).isTrue();
        assertThat(corrected.sources().get(0).files())
                .filteredOn(file -> file.sourceName().equals("S01E06 (1).mkv"))
                .singleElement()
                .satisfies(file -> {
                    assertThat(file.status()).isEqualTo("MANUAL");
                    assertThat(file.targetName()).isEqualTo("新世界 - S01E05.mkv");
                });
    }

    @Test
    void completelyExcludesAnIgnoredDuplicateFile() {
        String shareUrl = "https://pan.quark.cn/s/share123";
        QasShareTree tree = duplicateEpisodeTree(shareUrl);
        QuarkMultiSourceIngestService service = serviceFor(tree, shareUrl);
        QuarkMultiSourcePreviewResponse structure = service.previewStructure(
                request(shareUrl, null, List.of()), "SERIES"
        );
        String candidateId = structure.sources().get(0).sourceCandidateId();
        QuarkMultiSourcePreviewResponse blocked = service.previewPlan(
                request(shareUrl, structure.previewId(), List.of(
                        new QuarkSourceSelectionRequest(candidateId, 1, false, false)
                )),
                "SERIES"
        );
        String duplicateFileId = blocked.sources().get(0).files().stream()
                .filter(file -> file.sourceName().equals("S01E06 (1).mkv"))
                .findFirst()
                .orElseThrow()
                .fileId();

        QuarkMultiSourcePreviewResponse corrected = service.previewPlan(
                request(shareUrl, structure.previewId(), List.of(
                        new QuarkSourceSelectionRequest(candidateId, 1, false, false, List.of(
                                new QuarkFileSelectionRequest(duplicateFileId, null, true)
                        ))
                )),
                "SERIES"
        );

        assertThat(corrected.ready()).isTrue();
        assertThat(corrected.sources().get(0).files())
                .filteredOn(file -> file.sourceName().equals("S01E06 (1).mkv"))
                .singleElement()
                .satisfies(file -> {
                    assertThat(file.status()).isEqualTo("IGNORED");
                    assertThat(file.message()).contains("不会转存");
                });
    }

    @Test
    void blocksManualEpisodeThatConflictsWithAnAutomaticallyPlannedFile() {
        String shareUrl = "https://pan.quark.cn/s/share123";
        QasShareTree tree = duplicateEpisodeTree(shareUrl);
        QuarkMultiSourceIngestService service = serviceFor(tree, shareUrl);
        QuarkMultiSourcePreviewResponse structure = service.previewStructure(
                request(shareUrl, null, List.of()), "SERIES"
        );
        String candidateId = structure.sources().get(0).sourceCandidateId();
        QuarkMultiSourcePreviewResponse blocked = service.previewPlan(
                request(shareUrl, structure.previewId(), List.of(
                        new QuarkSourceSelectionRequest(candidateId, 1, false, false)
                )),
                "SERIES"
        );
        String duplicateFileId = blocked.sources().get(0).files().stream()
                .filter(file -> file.sourceName().equals("S01E06 (1).mkv"))
                .findFirst()
                .orElseThrow()
                .fileId();

        QuarkMultiSourcePreviewResponse conflicting = service.previewPlan(
                request(shareUrl, structure.previewId(), List.of(
                        new QuarkSourceSelectionRequest(candidateId, 1, false, false, List.of(
                                new QuarkFileSelectionRequest(duplicateFileId, 1, false)
                        ))
                )),
                "SERIES"
        );

        assertThat(conflicting.ready()).isFalse();
        assertThat(conflicting.sources().get(0).files())
                .filteredOn(file -> "CONFLICT".equals(file.status()))
                .extracting(file -> file.targetName())
                .contains("新世界 - S01E01.mkv");
    }

    @Test
    void summarizesAiredMissingAndExtraEpisodesFromFinalRenamePreview() throws Exception {
        String shareUrl = "https://pan.quark.cn/s/share123";
        QasShareTree tree = new QasShareTree(shareUrl, List.of(directory(
                "season", "新世界", video("S01E01.mkv"), video("S01E03.mkv"), video("S01E04.mkv")
        )));
        TmdbClient tmdbClient = mock(TmdbClient.class);
        when(tmdbClient.getTvSeasonDetails(100, 1, "zh-CN")).thenReturn(new ObjectMapper().readTree("""
                {"episodes":[
                  {"episode_number":1,"air_date":"2020-01-01"},
                  {"episode_number":2,"air_date":"2020-01-02"},
                  {"episode_number":3,"air_date":"2020-01-03"}
                ]}
                """));
        QuarkMultiSourceIngestService service = serviceFor(tree, shareUrl, tmdbClient);
        QuarkMultiSourcePreviewResponse structure = service.previewStructure(
                request(shareUrl, null, List.of()), "SERIES"
        );
        String candidateId = structure.sources().get(0).sourceCandidateId();

        QuarkMultiSourcePreviewResponse preview = service.previewPlan(
                new QuarkMultiSourceRequest(
                        shareUrl, "新世界", null, 100, structure.previewId(), false,
                        List.of(new QuarkSourceSelectionRequest(candidateId, 1, false, false))
                ),
                "SERIES"
        );

        assertThat(preview.seasonCoverages()).singleElement().satisfies(coverage -> {
            assertThat(coverage.videoCount()).isEqualTo(3);
            assertThat(coverage.recognizedEpisodeCount()).isEqualTo(3);
            assertThat(coverage.expectedEpisodeCount()).isEqualTo(3);
            assertThat(coverage.airedEpisodeCount()).isEqualTo(3);
            assertThat(coverage.missingEpisodeNumbers()).containsExactly(2);
            assertThat(coverage.extraEpisodeNumbers()).containsExactly(4);
            assertThat(coverage.coverageStatus()).isEqualTo("MISSING");
        });
    }

    @Test
    void countsOneMergedEpisodeVideoAsCoveringBothEpisodes() throws Exception {
        String shareUrl = "https://pan.quark.cn/s/share123";
        QasShareTree tree = new QasShareTree(shareUrl, List.of(directory(
                "season", "第二季", video("07.24.mp4")
        )));
        TmdbClient tmdbClient = mock(TmdbClient.class);
        when(tmdbClient.getTvSeasonDetails(100, 2, "zh-CN")).thenReturn(new ObjectMapper().readTree("""
                {"episodes":[
                  {"episode_number":7,"air_date":"2022-07-24"},
                  {"episode_number":8,"air_date":"2022-07-24"}
                ]}
                """));
        QuarkMultiSourceIngestService service = serviceFor(tree, shareUrl, tmdbClient);
        QuarkMultiSourcePreviewResponse structure = service.previewStructure(
                request(shareUrl, null, List.of()), "VARIETY"
        );
        String candidateId = structure.sources().get(0).sourceCandidateId();

        QuarkMultiSourcePreviewResponse preview = service.previewPlan(
                new QuarkMultiSourceRequest(
                        shareUrl, "五十公里桃花坞", null, 100, structure.previewId(), false,
                        List.of(new QuarkSourceSelectionRequest(candidateId, 2, false, false))
                ),
                "VARIETY"
        );

        assertThat(preview.seasonCoverages()).singleElement().satisfies(coverage -> {
            assertThat(coverage.videoCount()).isEqualTo(1);
            assertThat(coverage.recognizedEpisodeCount()).isEqualTo(2);
            assertThat(coverage.missingEpisodeNumbers()).isEmpty();
        });
        assertThat(preview.sources().get(0).files()).singleElement()
                .satisfies(file -> assertThat(file.targetName()).contains("S02E07-E08"));
    }

    private static QuarkMultiSourceIngestService serviceFor(QasShareTree tree, String shareUrl) {
        return serviceFor(tree, shareUrl, mock(TmdbClient.class));
    }

    private static QuarkMultiSourceIngestService serviceFor(
            QasShareTree tree,
            String shareUrl,
            TmdbClient tmdbClient
    ) {
        QuarkShareTreeService shareTreeService = mock(QuarkShareTreeService.class);
        when(shareTreeService.inspectShare(shareUrl)).thenReturn(tree);
        QasProperties qasProperties = new QasProperties();
        qasProperties.setTvRootPath("/TV");
        qasProperties.setVarietyRootPath("/Variety");
        return new QuarkMultiSourceIngestService(
                mock(QasClient.class), shareTreeService, qasProperties, new TmdbProperties(), mock(AuthService.class),
                new QuarkIngestPlanner(), tmdbClient, new QuarkShareSourceRegistry(),
                mock(QuarkIngestTaskMapper.class), mock(QuarkIngestTaskLogMapper.class)
        );
    }

    private static QuarkMultiSourceRequest request(
            String shareUrl,
            String previewId,
            List<QuarkSourceSelectionRequest> selections
    ) {
        return new QuarkMultiSourceRequest(
                shareUrl, "新世界", null, null, previewId, false, selections
        );
    }

    private static QasShareTree duplicateEpisodeTree(String shareUrl) {
        return new QasShareTree(shareUrl, List.of(directory(
                "season", "新世界", video("S01E01.mkv"), video("S01E02.mkv"),
                video("S01E06.mkv"), video("S01E06 (1).mkv"), video("S01E07.mkv")
        )));
    }

    private static QasShareNode directory(String fid, String name, QasShareNode... children) {
        return new QasShareNode(fid, name, true, null, 0, List.of(children));
    }

    private static QasShareNode file(String name) {
        return new QasShareNode("fid-" + name, name, false, "archive", 1, List.of());
    }

    private static QasShareNode video(String name) {
        return new QasShareNode("fid-" + name, name, false, "video", 1, List.of());
    }
}
