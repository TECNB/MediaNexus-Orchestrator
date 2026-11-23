package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.medianexus.orchestrator.integration.guangya.TorrentFileTable;
import com.medianexus.orchestrator.integration.pansou.PanSouMagnetIdentity;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReleaseFileAssessmentTest {
    private final ReleaseFileAssessment assessment = new ReleaseFileAssessment(new MovieSeriesFileRenameService(), new ReleaseTitleTagParser());
    private final ReleaseTarget target = ReleaseTarget.of("切尔诺贝利", "Chernobyl", null, null, 1, "1080p");

    @Test void ignoresOnlyStandalonePromotionalVideosRegardlessOfPaddedSize() {
        String advertisement = "【更多无水印高品质资源请访问 www.Butailing.com】.MKV";
        var safe = sizedTable("Chernobyl S01 1080p", new TorrentFileTable.Entry("Chernobyl.S01E01.mkv", 200000000),
                new TorrentFileTable.Entry(advertisement, 636976));
        assertThat(assessment.assess(target, List.of("Chernobyl S01 1080p"), safe).evidence().status()).isEqualTo("RECOMMENDABLE");
        var variant = sizedTable("Chernobyl S01 1080p", new TorrentFileTable.Entry("Chernobyl.S01E01.mkv", 200000000),
                new TorrentFileTable.Entry(advertisement.replace("无水印", ""), 296080));
        assertThat(assessment.assess(target, List.of("Chernobyl S01 1080p"), variant).evidence().status()).isEqualTo("RECOMMENDABLE");
        var padded = sizedTable("Chernobyl S01 1080p", new TorrentFileTable.Entry("Chernobyl.S01E01.mkv", 200000000),
                new TorrentFileTable.Entry(advertisement, 500L * 1024 * 1024));
        assertThat(assessment.assess(target, List.of("Chernobyl S01 1080p"), padded).evidence().status()).isEqualTo("RECOMMENDABLE");
        for (var extra : List.of(new TorrentFileTable.Entry("unknown.mkv", 636976),
                new TorrentFileTable.Entry("Chernobyl " + advertisement, 500L * 1024 * 1024))) {
            assertThat(assessment.assess(target, List.of("Chernobyl S01 1080p"), sizedTable("Chernobyl S01 1080p",
                    new TorrentFileTable.Entry("Chernobyl.S01E01.mkv", 200000000), extra)).evidence().status()).isEqualTo("NEEDS_PROCESSING");
        }
    }

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
        var table = sizedTable("Chernobyl (2019) Season 1 1080p",
                new TorrentFileTable.Entry("Chernobyl.S01E01.1.23.45.mkv", 100),
                new TorrentFileTable.Entry("Chernobyl.S01E02.mkv", 100));
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
        assertThat(evidence.totalBytes()).isEqualTo(2L * 100 * 1024 * 1024);
        assertThat(evidence.targetBytes()).isEqualTo(100L * 1024 * 1024);
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

    @Test void movieSmallVideoSidecarsDoNotInflateFeatureCountOrBytes() {
        var film = ReleaseTarget.of("疯狂舞蹈办公室", "Mad Dance Office", null, 2026, null, "1080p");
        // 真实广告的大小与后缀；任意 basename 都适用同一落地门槛。
        var table = sizedTable("Mad Dance Office 2026 1080p",
                new TorrentFileTable.Entry("Mad.Dance.Office.2026.1080p.mkv", 7844369377L),
                new TorrentFileTable.Entry("unknown.MP4", 296080),
                new TorrentFileTable.Entry("unknown.MKV", 636976));
        var evidence = assessment.assess(film, List.of("Mad Dance Office 2026 1080p"), table).evidence();
        assertThat(evidence.status()).isEqualTo("RECOMMENDABLE");
        assertThat(evidence.targetFiles()).isEqualTo(1);
        assertThat(evidence.targetBytes()).isEqualTo(7844369377L);
        assertThat(evidence.totalBytes()).isEqualTo(7845302433L);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "104857599, RECOMMENDABLE, 1", "104857600, NEEDS_PROCESSING, 2", "104857601, NEEDS_PROCESSING, 2",
            "2147483648, NEEDS_PROCESSING, 2"
    })
    void movieSizeBoundaryNeverHidesUnknownLargeVideo(long sidecarBytes, String status, int count) {
        var film = ReleaseTarget.of("Buddy", null, null, 2026, null, "1080p");
        var evidence = assessment.assess(film, List.of("Buddy 2026 1080p"), sizedTable("Buddy 2026 1080p",
                new TorrentFileTable.Entry("Buddy.2026.1080p.mkv", 2000000000L),
                new TorrentFileTable.Entry("unknown.mkv", sidecarBytes))).evidence();
        assertThat(evidence.status()).isEqualTo(status);
        assertThat(evidence.targetFiles()).isEqualTo(count);
        assertThat(evidence.targetBytes()).isEqualTo(2000000000L + (count == 2 ? sidecarBytes : 0));
    }

    @Test void smallMovieCannotEstablishIdentityOrBecomeAFeature() {
        var film = ReleaseTarget.of("Buddy", null, null, 2026, null, "1080p");
        assertThat(assessment.assess(film, List.of("Buddy 2026 1080p"), sizedTable("Download 2026 1080p",
                new TorrentFileTable.Entry("Buddy.2026.1080p.mkv", 636976),
                new TorrentFileTable.Entry("unknown.mkv", 2000000000L))).evidence().status()).isEqualTo("UNVERIFIED");
        assertThat(assessment.assess(film, List.of("Buddy 2026 1080p"), sizedTable("Buddy 2026 1080p",
                new TorrentFileTable.Entry("Buddy.2026.1080p.mkv", 104857599))).evidence().status()).isEqualTo("UNSUITABLE");
        assertThat(assessment.assess(film, List.of("Buddy 2026 1080p"), sizedTable("Buddy 2026 1080p",
                new TorrentFileTable.Entry("Buddy.2026.1080p.mkv", 104857600))).evidence().status()).isEqualTo("RECOMMENDABLE");
    }

    @Test void twoRealMovieVersionsStillRequireProcessing() {
        var film = ReleaseTarget.of("Buddy", null, null, 2026, null, "1080p");
        var evidence = assessment.assess(film, List.of("Buddy 2026 1080p"), sizedTable("Buddy 2026 1080p",
                new TorrentFileTable.Entry("Buddy.2026.1080p.WEB-DL.mkv", 2000000000L),
                new TorrentFileTable.Entry("Buddy.2026.1080p.BluRay.mkv", 3000000000L))).evidence();
        assertThat(evidence.status()).isEqualTo("NEEDS_PROCESSING");
        assertThat(evidence.targetFiles()).isEqualTo(2);
        assertThat(evidence.targetBytes()).isEqualTo(5000000000L);
    }

    private TorrentFileTable sizedTable(String root, TorrentFileTable.Entry... files) {
        return new TorrentFileTable(new PanSouMagnetIdentity("a".repeat(40)), root,
                List.of(files), java.util.Arrays.stream(files).mapToLong(TorrentFileTable.Entry::sizeBytes).sum());
    }

    private TorrentFileTable table(String root, String... paths) {
        return new TorrentFileTable(new PanSouMagnetIdentity("a".repeat(40)), root,
                java.util.Arrays.stream(paths).map(path -> new TorrentFileTable.Entry(root + "/" + path, 100L * 1024 * 1024)).toList(), paths.length * 100L * 1024 * 1024);
    }
}
