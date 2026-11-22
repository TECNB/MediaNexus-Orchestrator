package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MovieSeriesFileRenameServiceTest {

    private final MovieSeriesFileRenameService renameService = new MovieSeriesFileRenameService();

    @Test void movieFeatureEligibilityUsesTheLandingSizeBoundary() {
        assertThat(renameService.isMovieFeatureVideo("unknown.MKV", 104857599)).isFalse();
        assertThat(renameService.isMovieFeatureVideo("unknown.MP4", 104857600)).isTrue();
        assertThat(renameService.isMovieFeatureVideo("unknown.mkv", 104857601)).isTrue();
        assertThat(renameService.isMovieFeatureVideo("unknown.txt", 2000000000L)).isFalse();
    }

    @Test
    void recognizesEpisodeSuffixOnlyAfterTheKnownSeriesTitle() {
        for (int episode = 1; episode <= 12; episode++) {
            var result = renameService.seriesVideo("隐秘的角落%02d.mp4".formatted(episode), "隐秘的角落", 1).orElseThrow();
            assertThat(result.episodeNumber()).isEqualTo(episode);
        }
        assertThat(renameService.seriesSubtitle("隐秘的角落01.srt", "隐秘的角落", 1, "1080p")).isPresent();
        assertThat(renameService.seriesVideo("其他剧01.mp4", "隐秘的角落", 1)).isEmpty();
        assertThat(renameService.seriesVideo("隐秘的角落2020.mp4", "隐秘的角落", 1)).isEmpty();
        assertThat(renameService.seriesVideo("隐秘的角落1080p.mp4", "隐秘的角落", 1)).isEmpty();
        assertThat(renameService.seriesVideo("隐秘的角落00.mp4", "隐秘的角落", 1)).isEmpty();
        assertThat(renameService.seriesVideo("隐秘的角落SP01.mp4", "隐秘的角落", 1)).isEmpty();
        assertThat(renameService.seriesVideo("隐秘的角落01.mp4", "The Bad Kids", 1, "隐秘的角落")
                .orElseThrow().fileName()).isEqualTo("The Bad Kids - S01E01.mp4");
    }

    @Test
    void recognizesBareAnimeEpisodeAfterTitleSeparator() {
        String sourceName = "[LoliHouse] Hell Mode - 01 [WebRip 1080p HEVC-10bit AAC SRTx2].mkv";

        MovieSeriesFileRenameService.RenameResult result = renameService.seriesVideo(
                sourceName,
                "地狱模式 ～喜欢速通游戏的玩家在废设定异世界无双～",
                1
        ).orElseThrow();

        assertThat(result.episodeNumber()).isEqualTo(1);
        assertThat(renameService.episodeNumber("[LoliHouse] Hell Mode - 01.mkv", 1)).contains(1);
        assertThat(renameService.episodeNumber("[LoliHouse] Hell Mode - 01v2 [WebRip 1080p].mkv", 1)).contains(1);
        assertThat(renameService.episodeNumber("[Sakurato] Show [01v2][1080P].mp4", 1)).contains(1);
        assertThat(renameService.episodeNumber("Show 【13V3】 [1080p].mkv", 1)).contains(13);
        assertThat(renameService.episodeNumber("Show [01v2-13v2][1080p].mkv", 1)).isEmpty();
        assertThat(renameService.episodeNumber("Show [02.5v2][1080p].mkv", 1)).isEmpty();
    }

    @Test
    void recognizesBdParenthesesAndExcludesAuxiliaryFiles() {
        for (int episode = 1; episode <= 12; episode++) {
            String name = "[LowPower-Raws] Hai to Gensou no Grimgar - " + String.format("%02d", episode)
                    + " (BD 1080P x264 FLAC).mkv";
            assertThat(renameService.episodeNumber(name, 1)).contains(episode);
        }
        for (String name : java.util.List.of("Show - 02.5 (BD 1080P).mkv", "Show - NCOP1 (BD).mkv",
                "Show - NCED2 [1080p].mkv", "Show - 13(OVA) [1080p].mkv", "Show - CM01 [1080p].mkv",
                "Show [PV] S01E01.mkv", "Show Menu01 [01].mkv")) {
            assertThat(renameService.episodeNumber(name, 1)).as(name).isEmpty();
        }
        assertThat(renameService.episodeNumber("2.5 Jigen no Ririsa - 01 (BD 1080P).mkv", 1)).contains(1);
    }

    @Test
    void doesNotTreatReleaseMetadataAsBareEpisode() {
        assertThat(renameService.episodeNumber(
                "[LoliHouse] Hell Mode [01-12][WebRip 1080p HEVC-10bit AAC].mkv",
                1
        )).isEmpty();
        assertThat(renameService.episodeNumber("Hell Mode - 1080p WEBRip.mkv", 1)).isEmpty();
        assertThat(renameService.episodeNumber("Hell Mode - HEVC-10bit.mkv", 1)).isEmpty();
    }

    @Test
    void recognizesEveryFileInTheRealMixedPreviewPackWithoutInventingQuality() {
        String prefix = "【大陆剧网www.daluju123.com】隐秘的角落";
        java.util.Set<Integer> recognized = new java.util.LinkedHashSet<>();
        for (int episode = 1; episode <= 12; episode++) {
            String suffix = episode <= 6 ? "第" + episode + "集-蓝光1080P"
                    : "尝鲜版%02d%s".formatted(episode, episode == 12 ? "End" : "");
            var result = renameService.seriesVideo(prefix + suffix + ".mp4", "隐秘的角落", 1).orElseThrow();
            recognized.add(result.episodeNumber());
            assertThat(result.fileName()).isEqualTo("隐秘的角落 - S01E%02d%s.mp4".formatted(episode,
                    episode <= 6 ? " 1080p" : " 尝鲜版"));
            assertThat(result.quality()).isEqualTo(episode <= 6 ? "1080p" : "尝鲜版");
        }
        assertThat(recognized).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
    }

    @Test
    void keepsVersionMarkersInVideoAndMatchedSubtitleNames() {
        for (String marker : java.util.List.of("尝鲜版", "抢先版")) {
            var result = renameService.seriesVideo("Show.S01E07.1080p." + marker + ".mp4", "Show", 1).orElseThrow();
            assertThat(result.fileName()).isEqualTo("Show - S01E07 1080p " + marker + ".mp4");
            assertThat(renameService.seriesSubtitle("Show.S01E07.zh.srt", "Show", 1, result.quality())
                    .orElseThrow().fileName()).isEqualTo("Show - S01E07 1080p " + marker + ".zh.srt");
            assertThat(renameService.movieVideo("Movie.1080p." + marker + ".mkv", "Movie", 2020).fileName())
                    .isEqualTo("Movie (2020) 1080p " + marker + ".mkv");
        }
        assertThat(renameService.seriesVideo("隐秘的角落尝鲜版07 [1080p].mp4", "隐秘的角落", 1)
                .orElseThrow().fileName()).isEqualTo("隐秘的角落 - S01E07 1080p 尝鲜版.mp4");
        assertThat(renameService.qualityFull("Show.S01E01.ts")).isEmpty();
        assertThat(renameService.qualityFull("Show.S01E01.抢先版.CAM.mkv")).isEqualTo("抢先版 枪版");
    }

    @Test
    void limitsDecoratedEpisodeSuffixToTheExactKnownTitle() {
        assertThat(renameService.seriesVideo("【大陆剧网www.daluju123.com】抢先版隐秘的角落07End.mp4",
                "The Bad Kids", 1, "隐秘的角落").orElseThrow().fileName())
                .isEqualTo("The Bad Kids - S01E07 抢先版.mp4");
        assertThat(renameService.seriesVideo("【站点123】3-gatsu no Lion尝鲜版07.mp4", "3-gatsu no Lion", 1))
                .isPresent();
        for (String name : java.util.List.of("【站点123】其他剧尝鲜版07.mp4", "63-gatsu no Lion尝鲜版07.mp4",
                "【站点123】隐秘的角落2020.mp4", "【站点123】隐秘的角落1080P.mp4", "01.flv",
                "Show_01_1080p.mkv", "隐秘的角落尝鲜版07.5.mp4", "隐秘的角落尝鲜版00.mp4")) {
            assertThat(renameService.seriesVideo(name, "隐秘的角落", 1)).as(name).isEmpty();
        }
        assertThat(renameService.seriesVideo("63-gatsu no Lion尝鲜版07.mp4", "3-gatsu no Lion", 1)).isEmpty();
        assertThat(renameService.seriesVideo("Show.S02E23.mkv", "Show", 1)).isEmpty();
        assertThat(renameService.episodeNumber("Show - 23.mkv", 2)).contains(23);
    }

    @Test
    void decimalEpisodeTitleIsNotAFractionalEpisode() {
        String title = "切尔诺贝利S01E01 - 1.23.45 [1080p].mkv";
        assertThat(renameService.episodeNumber(title, 1)).contains(1);
        assertThat(renameService.isNonFeatureVideo(title)).isFalse();
        assertThat(renameService.seriesVideo(title, "切尔诺贝利", 1).orElseThrow().episodeNumber()).isEqualTo(1);
        for (String name : java.util.List.of("Show - 02.5.mkv", "Show - 02.5 (BD).mkv",
                "Show.S01E02.5.mkv", "Show [02.5v2].mkv", "Show.S01E02.5v2.mkv")) {
            assertThat(renameService.episodeNumber(name, 1)).as(name).isEmpty();
            assertThat(renameService.isNonFeatureVideo(name)).as(name).isTrue();
        }
        assertThat(renameService.episodeNumber("Show.S01E01 - Trailer 1.23.45.mkv", 1)).isEmpty();
    }

    @Test
    void resolutionLexingRecognizesOnlyKnownAttachedPrefixes() {
        for (String prefix : java.util.List.of("WEB", "HD", "蓝光")) {
            assertThat(renameService.qualityFull("Show EP01 " + prefix + "1080P.mp4")).isEqualTo("1080p");
        }
        assertThat(renameService.qualityFull("Show EP01 FOO1080P.mp4")).isEmpty();
    }
}
