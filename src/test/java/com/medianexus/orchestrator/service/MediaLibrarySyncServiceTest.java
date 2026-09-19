package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MediaLibrarySyncServiceTest {

    @Test
    void parsesUnicodeLocalMediaSourcePath() throws Exception {
        assertThat(MediaLibrarySyncService.remotePath(
                "/srv/media/CloudNAS/PikPak/Media/Anime/黄金神威 第二季/Season 2/黄金神威 第二季 S02E01.mp4",
                false
        )).isEqualTo("/srv/media/CloudNAS/PikPak/Media/Anime/黄金神威 第二季/Season 2");
    }

    @Test
    void parsesSmartStrmUrl() {
        assertThat(MediaLibrarySyncService.remotePath(
                "http://107.172.224.11:8024/smartstrm_fid/id/TV/繁花/Season%2001/01.mp4?sign=x",
                false
        )).isEqualTo("/TV/繁花/Season 01");
    }

    @Test
    void shallowAnimeCheckStopsAtAnimeSeriesDirectory() {
        assertThat(MediaLibrarySyncService.remotePath(
                "/srv/media/CloudNAS/PikPak/Media/Anime/黄金神威 第二季/Season 2/黄金神威 第二季 S02E01.mp4",
                false,
                AdminMediaLibraryScope.ANIME
        )).isEqualTo("/srv/media/CloudNAS/PikPak/Media/Anime/黄金神威 第二季");
    }

    @Test
    void libraryShallowCheckStopsAtAdultOtherTopLevelDirectory() {
        assertThat(MediaLibrarySyncService.topLevelRemotePath(
                "/srv/media/CloudNAS/PikPak/Media/Adult/Other/电报/子目录/视频.mp4",
                AdminMediaLibraryScope.ADULT_OTHER
        )).isEqualTo("/srv/media/CloudNAS/PikPak/Media/Adult/Other/电报");
    }

    @Test
    void libraryShallowCheckKeepsDirectAdultOtherFile() {
        assertThat(MediaLibrarySyncService.topLevelRemotePath(
                "/srv/media/CloudNAS/PikPak/Media/Adult/Other/独立视频.mp4",
                AdminMediaLibraryScope.ADULT_OTHER
        )).isEqualTo("/srv/media/CloudNAS/PikPak/Media/Adult/Other/独立视频.mp4");
    }
}
