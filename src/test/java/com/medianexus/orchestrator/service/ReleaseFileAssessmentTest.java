package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.medianexus.orchestrator.integration.guangya.TorrentFileTable;
import com.medianexus.orchestrator.integration.pansou.PanSouMagnetIdentity;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReleaseFileAssessmentTest {
    private final ReleaseFileAssessment assessment = new ReleaseFileAssessment(new MovieSeriesFileRenameService(), new ReleaseTitleTagParser());
    private final ReleaseTarget target = ReleaseTarget.of("切尔诺贝利", "Chernobyl", null, null, 1, "1080p");

    @Test
    void recognizesChineseRootWithEpisodeRangeAndRelativeSecondSeasonWithoutBlindOffset() {
        var chinese = ReleaseTarget.of("隐秘的角落", "The Bad Kids", null, null, 1, "1080p");
        assertThat(assessment.assess(chinese, List.of("隐秘的角落01-12全集1080p"),
                table("隐秘的角落01-12全集1080p", "隐秘的角落第1集-蓝光1080P.mp4", "隐秘的角落尝鲜版07.mp4"))
                .evidence().status()).isEqualTo("RECOMMENDABLE");
        var second = ReleaseTarget.of("Show", null, null, null, 2, "1080p");
        assertThat(assessment.assess(second, List.of("Show Season 2 1080p"),
                table("Show Season 2 1080p", "Show - 01 [1080p].mkv", "Show - 02 [1080p].mkv"))
                .evidence().status()).isEqualTo("RECOMMENDABLE");
        assertThat(assessment.assess(second, List.of("Show Season 2 1080p"),
                table("Show Season 2 1080p", "Show - 26 [1080p].mkv", "Show - 27 [1080p].mkv"))
                .evidence().status()).isEqualTo("NEEDS_PROCESSING");
    }
    @Test
    void fileEvidencePromotesSmallPackWithoutTitleCompleteOrCapacityHeuristic() {
        var table = table("Chernobyl (2019) Season 1 1080p", "Chernobyl.S01E01.1.23.45.mkv", "Chernobyl.S01E02.mkv");
        var evidence = assessment.assess(target, List.of("Chernobyl Season 1 HEVC 1080p 7RIP"), table).evidence();
        assertThat(evidence.status()).isEqualTo("RECOMMENDABLE");
        assertThat(evidence.targetFiles()).isEqualTo(2);
        assertThat(evidence.targetBytes()).isEqualTo(200L);
    }

    @Test
    void unknownVideosAndCrossSeasonPackagesCannotBeAutoSubmitted() {
        assertThat(assessment.assess(target, List.of("Chernobyl S01 1080p"),
                table("Chernobyl Season 1 1080p", "Chernobyl.S01E01.mkv", "02.flv")).evidence().status()).isEqualTo("NEEDS_PROCESSING");
        assertThat(assessment.assess(target, List.of("Chernobyl S01-S02 Complete 1080p"),
                table("Chernobyl 1080p", "Season 1/Chernobyl.S01E01.mkv", "Season 2/Chernobyl.S02E01.mkv"))
                .evidence().status()).isEqualTo("NEEDS_PROCESSING");
    }

    @Test
    void explicitIdentityConflictCannotBeWashedByMatchingDirectory() {
        var evidence = assessment.assess(target, List.of("Chernobyl Movie 1080p"),
                table("Chernobyl Season 1 1080p", "Chernobyl.S01E01.mkv")).evidence();
        assertThat(evidence.status()).isEqualTo("UNSUITABLE");
    }

    @Test
    void filesOverrideMissingTitleQualityButNeverAllowLowerQualityFallback() {
        assertThat(assessment.assess(target, List.of("Chernobyl"), table("Chernobyl Season 1", "Chernobyl.S01E01.HD1080P.mp4"))
                .evidence().status()).isEqualTo("RECOMMENDABLE");
        assertThat(assessment.assess(target, List.of("Chernobyl"), table("Chernobyl Season 1", "Chernobyl.S01E01.720p.mp4"))
                .evidence().status()).isEqualTo("UNSUITABLE");
    }

    @Test
    void filmExtrasDoNotInflateTargetSizeOrRejectMainFeature() {
        var film = ReleaseTarget.of("死亡诗社", "Dead Poets Society", null, 1989, null, "1080p");
        var evidence = assessment.assess(film, List.of("Dead Poets Society 1989 1080p"),
                table("Dead Poets Society 1989 1080p", "Dead.Poets.Society.1989.mkv", "Extras/Interview.mkv")).evidence();
        assertThat(evidence.status()).isEqualTo("RECOMMENDABLE");
        assertThat(evidence.totalBytes()).isEqualTo(200L);
        assertThat(evidence.targetBytes()).isEqualTo(100L);
    }

    @Test
    void nonFeatureFilesCannotEstablishMovieIdentity() {
        var film = ReleaseTarget.of("Buddy", null, null, 2026, null, "1080p");
        for (String decoy : new String[]{"Buddy.txt", "Extras/Buddy.2026.1080p.mkv", "Buddy.sample.mkv"}) {
            assertThat(assessment.assess(film, List.of("Buddy 2026 1080p"),
                    table("Download 2026 1080p", decoy, "Other.Movie.mkv")).evidence().status())
                    .as(decoy).isEqualTo("UNVERIFIED");
        }
        var emptyDecoy = new TorrentFileTable(new PanSouMagnetIdentity("a".repeat(40)), "Download 2026 1080p",
                List.of(new TorrentFileTable.Entry("Buddy.2026.1080p.mkv", 0L),
                        new TorrentFileTable.Entry("Other.Movie.mkv", 100L)), 100L);
        assertThat(assessment.assess(film, List.of("Buddy 2026 1080p"), emptyDecoy).evidence().status()).isEqualTo("UNVERIFIED");
        assertThat(assessment.assess(film, List.of("Buddy 2026 1080p"),
                table("Download 2026 1080p", "Buddy.2026.1080p.mkv")).evidence().status()).isEqualTo("RECOMMENDABLE");
        assertThat(assessment.assess(film, List.of("Buddy 2026 1080p"),
                table("Download 2026 1080p", "Buddy.2026.1080p.mkv", "Other.Movie.mkv")).evidence().status()).isEqualTo("NEEDS_PROCESSING");
    }

    @Test
    void movieSeasonDeclarationsInSourceRootOrFeatureCannotBeRecommended() {
        var film = ReleaseTarget.of("Buddy", null, null, 2026, null, "1080p");
        assertThat(assessment.assess(film, List.of("Buddy 2026 S01E01 1080p"),
                table("Buddy 2026 1080p", "Buddy.2026.1080p.mkv")).evidence().status()).isEqualTo("UNSUITABLE");
        assertThat(assessment.assess(film, List.of("Buddy 2026 1080p"),
                table("Buddy 2026 S01E01 1080p", "Buddy.2026.1080p.mkv")).evidence().status()).isEqualTo("UNSUITABLE");
        assertThat(assessment.assess(film, List.of("Buddy 2026 1080p"),
                table("Buddy 2026 1080p", "Buddy.2026.S01E01.1080p.mkv")).evidence().status()).isEqualTo("UNSUITABLE");
        assertThat(assessment.assess(film, List.of("Buddy 2026 1080p"),
                table("Buddy 2026 1080p", "Season 1/Buddy.2026.1080p.mkv")).evidence().status()).isEqualTo("UNSUITABLE");
    }

    @Test void explicitTextualSeasonsAndSourceSeasonConflictsAreNotRecommended() {
        var movie = ReleaseTarget.of("Buddy", null, null, 2026, null, "1080p");
        for (String season : new String[]{"第一季", "2nd Season", "S01E01"}) {
            assertThat(assessment.assess(movie, List.of("Buddy 2026 " + season + " 1080p"),
                    table("Buddy 2026 1080p", "Buddy.2026.1080p.mkv")).evidence().status()).isEqualTo("UNSUITABLE");
        }
        var series = ReleaseTarget.of("Show", null, null, null, 1, "1080p");
        assertThat(assessment.assess(series, List.of("Show 第二季 1080p"),
                table("Show S01 1080p", "Show.S01E01.mkv")).evidence().status()).isEqualTo("UNSUITABLE");
    }

    @Test void resolutionDimensionsCannotProveMovieReleaseYear() {
        var film = ReleaseTarget.of("The Golem", null, null, 1920, null, "1080p");
        assertThat(assessment.assess(film, List.of("The Golem 1920x1080"),
                table("The Golem 1920x1080", "The.Golem.1080p.mkv")).evidence().status()).isEqualTo("UNVERIFIED");
        assertThat(assessment.assess(film, List.of("The Golem 1920 1920x1080"),
                table("The Golem 1920 1920x1080", "The.Golem.1080p.mkv")).evidence().status()).isEqualTo("RECOMMENDABLE");
    }

    private TorrentFileTable table(String root, String... paths) {
        return new TorrentFileTable(new PanSouMagnetIdentity("a".repeat(40)), root,
                java.util.Arrays.stream(paths).map(path -> new TorrentFileTable.Entry(root + "/" + path, 100L)).toList(), paths.length * 100L);
    }
}
