package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MovieSeriesFileRenameServiceTest {

    private final MovieSeriesFileRenameService renameService = new MovieSeriesFileRenameService();

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
}
