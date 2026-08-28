package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class QuarkFileCandidateAnalyzerTest {

    private final QuarkFileCandidateAnalyzer analyzer = new QuarkFileCandidateAnalyzer();

    @Test
    void extractsEditionSegmentAndExtraCandidates() {
        QuarkFileCandidateAnalyzer.Candidate edition = analyzer.analyze(
                "source", "01 4K高码率.mp4"
        );
        assertThat(edition.detectedEpisode()).isEqualTo(1);
        assertThat(edition.assignmentType()).isEqualTo("EDITION");
        assertThat(edition.editionLabel()).isEqualTo("4K高码率");

        QuarkFileCandidateAnalyzer.Candidate segment = analyzer.analyze(
                "source", "第03期 - 上.mp4"
        );
        assertThat(segment.detectedEpisode()).isEqualTo(3);
        assertThat(segment.assignmentType()).isEqualTo("SEGMENT");
        assertThat(segment.segmentLabel()).isEqualTo("上");

        QuarkFileCandidateAnalyzer.Candidate extra = analyzer.analyze(
                "source", "加更花絮.mp4"
        );
        assertThat(extra.assignmentType()).isEqualTo("EXTRA");
        assertThat(extra.editionLabel()).isEqualTo("加更 花絮");
        assertThat(extra.reasonCodes()).contains("EXTRA_CANDIDATE");
    }

    @Test
    void extractsDateAndKeepsUnknownFilesExplicit() {
        QuarkFileCandidateAnalyzer.Candidate dated = analyzer.analyze(
                "source", "20250927第1期下纯享版.mp4"
        );
        assertThat(dated.detectedDate()).isEqualTo("2025-09-27");
        assertThat(dated.detectedEpisode()).isEqualTo(1);
        assertThat(dated.assignmentType()).isEqualTo("EXTRA");
        assertThat(dated.segmentLabel()).isEqualTo("下");

        QuarkFileCandidateAnalyzer.Candidate unknown = analyzer.analyze(
                "source", "海报.mp4"
        );
        assertThat(unknown.assignmentType()).isEqualTo("UNKNOWN");
        assertThat(unknown.reasonCodes()).containsExactly("NO_IDENTITY_ANCHOR");
    }

    @Test
    void extractsIssueNumbersWithoutTheChineseOrdinalPrefix() {
        QuarkFileCandidateAnalyzer.Candidate candidate = analyzer.analyze(
                "source", "04期 - 上.mp4"
        );

        assertThat(candidate.detectedEpisode()).isEqualTo(4);
        assertThat(candidate.assignmentType()).isEqualTo("SEGMENT");
        assertThat(candidate.segmentLabel()).isEqualTo("上");
    }

    @Test
    void groupIdUsesIdentityAndNormalizedSubject() {
        String first = analyzer.analyze("source", "01 4K.mp4").groupId();
        String sameEpisodeDifferentEdition = analyzer.analyze("source", "01 VIP.mp4").groupId();
        String sameEpisodeSlightlyDifferentTitle = analyzer.analyze("source", "欢乐喜剧人 EP01.mp4").groupId();
        String sameEpisodeTypoTitle = analyzer.analyze("source", "欢乐喜剧亻 EP01 完整版.mp4").groupId();
        String nextEpisode = analyzer.analyze("source", "02 4K.mp4").groupId();

        assertThat(first).isEqualTo(sameEpisodeDifferentEdition);
        assertThat(sameEpisodeSlightlyDifferentTitle).isEqualTo(sameEpisodeTypoTitle);
        assertThat(first).isNotEqualTo(nextEpisode);
        assertThat(analyzer.groupId("source", "unknown-name.mp4", 7))
                .isEqualTo(analyzer.groupId("source", "unknown-name.mp4", 7));
    }
}
