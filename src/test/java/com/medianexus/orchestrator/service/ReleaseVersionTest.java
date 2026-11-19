package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReleaseVersionTest {

    @Test
    void distinguishesChinesePreviewFromCameraEvidence() {
        for (String marker : java.util.List.of("尝鲜版", "抢先版", "嘗鮮版", "搶先版")) {
            assertThat(ReleaseVersion.isPreview("隐秘的角落" + marker + "07.mp4")).as(marker).isTrue();
            assertThat(ReleaseVersion.isCamera("隐秘的角落" + marker + "07.mp4")).as(marker).isFalse();
        }
        for (String text : java.util.List.of("Movie.CAM.1080p.mkv", "Movie.HDCAM.mkv", "Movie CAMRip.mkv",
                "电影枪版.mp4", "电影槍版.mp4", "Movie.HDTS.mkv", "电影TS版.mp4", "Movie TeleSync.mkv")) {
            assertThat(ReleaseVersion.isCamera(text)).as(text).isTrue();
        }
    }

    @Test
    void transportStreamAndUnqualifiedTsAreNotCameraEvidence() {
        for (String text : java.util.List.of("Show.S01E01.ts", "Show.S01E01.TS", "Show TS 1080p.mkv",
                "TS Show S01E01.mkv", "Cats 1080p.mkv", "Cambridge S01E01.mkv", "Movie.WEB-DL.m2ts")) {
            assertThat(ReleaseVersion.isCamera(text)).as(text).isFalse();
            assertThat(ReleaseVersion.marker(text)).as(text).isEmpty();
        }
        assertThat(ReleaseVersion.isCamera(null)).isFalse();
        assertThat(ReleaseVersion.isPreview(null)).isFalse();
        assertThat(ReleaseVersion.marker(null)).isEmpty();
    }

    @Test
    void returnsStableMarkersWithoutDroppingMixedVersionEvidence() {
        assertThat(ReleaseVersion.marker("尝鲜版07.mp4")).isEqualTo("尝鲜版");
        assertThat(ReleaseVersion.marker("搶先版07.mp4")).isEqualTo("抢先版");
        assertThat(ReleaseVersion.marker("尝鲜 抢先版 尝鲜版")).isEqualTo("尝鲜版 抢先版");
        assertThat(ReleaseVersion.marker("抢先版 CAM")).isEqualTo("抢先版 枪版");
        assertThat(ReleaseVersion.isCamera("抢先版 CAM")).isTrue();
        assertThat(ReleaseVersion.isPreview("抢先版 CAM")).isTrue();
    }
}
