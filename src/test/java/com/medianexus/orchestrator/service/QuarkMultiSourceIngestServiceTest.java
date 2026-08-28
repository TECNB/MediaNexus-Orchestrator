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
    void alignsDateNamedVarietyFilesToTmdbEpisodesAndLeavesOnlyUnknownDatesPending() throws Exception {
        String shareUrl = "https://pan.quark.cn/s/share123";
        QasShareTree tree = new QasShareTree(shareUrl, List.of(directory(
                "season", "欢乐喜剧人 S1",
                video("150425_超清.mp4"), video("150509_超清.mp4"), video("150725_超清.mp4")
        )));
        TmdbClient tmdbClient = mock(TmdbClient.class);
        when(tmdbClient.getTvSeasonDetails(100, 1, "zh-CN")).thenReturn(new ObjectMapper().readTree("""
                {"episodes":[
                  {"episode_number":1,"air_date":"2015-04-25","name":"第1期"},
                  {"episode_number":2,"air_date":"2015-05-09","name":"第2期"},
                  {"episode_number":3,"air_date":"2015-07-18","name":"第3期"}
                ]}
                """));
        QuarkMultiSourceIngestService service = serviceFor(tree, shareUrl, tmdbClient);
        QuarkMultiSourcePreviewResponse structure = service.previewStructure(
                request(shareUrl, null, List.of()), "VARIETY"
        );
        String candidateId = structure.sources().get(0).sourceCandidateId();

        QuarkMultiSourcePreviewResponse preview = service.previewPlan(
                new QuarkMultiSourceRequest(
                        shareUrl, "欢乐喜剧人", null, 100, structure.previewId(), false,
                        List.of(new QuarkSourceSelectionRequest(candidateId, 1, false, false))
                ),
                "VARIETY"
        );

        assertThat(preview.ready()).isFalse();
        assertThat(preview.seasonCoverages()).singleElement().satisfies(coverage -> {
            assertThat(coverage.videoCount()).isEqualTo(2);
            assertThat(coverage.recognizedEpisodeCount()).isEqualTo(2);
            assertThat(coverage.missingEpisodeNumbers()).containsExactly(3);
            assertThat(coverage.unknownVideoCount()).isEqualTo(1);
        });
        assertThat(preview.episodeAlignments())
                .filteredOn(alignment -> !alignment.files().isEmpty())
                .extracting(alignment -> alignment.episodeNumber())
                .containsExactly(1, 2);
        assertThat(preview.sources().get(0).files())
                .filteredOn(file -> file.sourceName().equals("150509_超清.mp4"))
                .singleElement()
                .satisfies(file -> {
                    assertThat(file.episodeNumber()).isEqualTo(2);
                    assertThat(file.tmdbAirDate()).isEqualTo("2015-05-09");
                });
        assertThat(preview.sources().get(0).files())
                .filteredOn(file -> file.sourceName().equals("150725_超清.mp4"))
                .singleElement()
                .satisfies(file -> {
                    assertThat(file.status()).isEqualTo("UNRECOGNIZED");
                    assertThat(file.message()).contains("TMDB 当前季度没有对应播出日期");
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

    @Test
    void exposesAutomaticEditionCandidateAndStableGroup() {
        String shareUrl = "https://pan.quark.cn/s/share123";
        QasShareTree tree = new QasShareTree(shareUrl, List.of(directory(
                "season", "新世界", video("01 4K.mp4"), video("02 4K.mp4")
        )));
        QuarkMultiSourceIngestService service = serviceFor(tree, shareUrl);
        QuarkMultiSourcePreviewResponse structure = service.previewStructure(
                request(shareUrl, null, List.of()), "SERIES"
        );
        String candidateId = structure.sources().get(0).sourceCandidateId();

        QuarkMultiSourcePreviewResponse preview = service.previewPlan(
                request(shareUrl, structure.previewId(), List.of(
                        new QuarkSourceSelectionRequest(candidateId, 1, false, false)
                )),
                "SERIES"
        );

        assertThat(preview.ready()).isTrue();
        assertThat(preview.plannedTaskCount()).isEqualTo(1);
        assertThat(preview.sources().get(0).files())
                .filteredOn(file -> file.sourceName().equals("01 4K.mp4"))
                .singleElement()
                .satisfies(file -> {
                    assertThat(file.assignmentType()).isEqualTo("EDITION");
                    assertThat(file.editionLabel()).isEqualTo("4K");
                    assertThat(file.groupId()).isNotBlank();
                    assertThat(file.reasonCodes()).contains("LEADING_EPISODE", "EDITION_CANDIDATE");
                });
    }

    @Test
    void acceptsOneSourceSplitIntoMultipleSafeRenameTasks() {
        String shareUrl = "https://pan.quark.cn/s/share123";
        QasShareTree tree = new QasShareTree(shareUrl, List.of(directory(
                "season", "新世界",
                video("01.mp4"), video("02.mp4"),
                video("S01E03 - 第3集.mkv"), video("S01E04 - 第4集.mkv")
        )));
        QuarkMultiSourceIngestService service = serviceFor(tree, shareUrl);
        QuarkMultiSourcePreviewResponse structure = service.previewStructure(
                request(shareUrl, null, List.of()), "SERIES"
        );
        String candidateId = structure.sources().get(0).sourceCandidateId();

        QuarkMultiSourcePreviewResponse preview = service.previewPlan(
                request(shareUrl, structure.previewId(), List.of(
                        new QuarkSourceSelectionRequest(candidateId, 1, false, false)
                )),
                "SERIES"
        );

        assertThat(preview.ready()).isTrue();
        assertThat(preview.plannedTaskCount()).isEqualTo(2);
        assertThat(preview.sources().get(0).files()).hasSize(4)
                .allMatch(file -> !"UNRECOGNIZED".equals(file.status()));
    }

    @Test
    void requiresExtraContentToHaveAnExplicitEpisodeAndKeepsSeasonPath() {
        String shareUrl = "https://pan.quark.cn/s/share123";
        QasShareTree tree = new QasShareTree(shareUrl, List.of(directory(
                "season", "新世界", video("加更花絮.mp4")
        )));
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
        String fileId = blocked.sources().get(0).files().get(0).fileId();

        QuarkMultiSourcePreviewResponse stillBlocked = service.previewPlan(
                request(shareUrl, structure.previewId(), List.of(
                        new QuarkSourceSelectionRequest(candidateId, 1, false, false, List.of(
                                new QuarkFileSelectionRequest(fileId, null, false,
                                        "EXTRA", "花絮", null, true)
                        ))
                )),
                "SERIES"
        );
        assertThat(stillBlocked.ready()).isFalse();
        assertThat(stillBlocked.message()).contains("额外内容，请先指定当前季度中的集数或忽略");

        QuarkMultiSourcePreviewResponse ready = service.previewPlan(
                request(shareUrl, structure.previewId(), List.of(
                        new QuarkSourceSelectionRequest(candidateId, 1, false, false, List.of(
                                new QuarkFileSelectionRequest(fileId, 2, false,
                                        "EXTRA", "花絮", null, true)
                        ))
                )),
                "SERIES"
        );
        assertThat(ready.ready()).isTrue();
        assertThat(ready.sources().get(0).savePath()).isEqualTo("/TV/新世界/Season 01");
        assertThat(ready.sources().get(0).files()).singleElement()
                .satisfies(file -> assertThat(file.targetName()).contains("S01E02 - 花絮"));
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
