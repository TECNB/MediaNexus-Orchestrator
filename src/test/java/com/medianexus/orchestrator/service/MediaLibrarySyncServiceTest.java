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
}
