package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.medianexus.orchestrator.config.CloudDrive2Properties;
import com.medianexus.orchestrator.integration.clouddrive.CloudDrive2MediaDeletion;
import com.medianexus.orchestrator.integration.emby.EmbyClient;
import com.medianexus.orchestrator.integration.emby.EmbyDeletionItem;
import com.medianexus.orchestrator.integration.emby.EmbyLibrary;
import com.medianexus.orchestrator.integration.quark.QuarkDirectClient;
import com.medianexus.orchestrator.support.IngestTargetFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class MediaLibrarySyncServiceTest {

    @Test
    void syncUsesBothActualTargetsAndPreservesUnknownPrefixCollision(@TempDir Path strmRoot) throws Exception {
        IngestTargetFixtures targets = new IngestTargetFixtures();
        targets.useDefault("new");
        CloudDrive2Properties properties = new CloudDrive2Properties();
        properties.setStrmPathPrefix(strmRoot.toString());
        properties.setMediaSourcePathPrefix("/mount/new2");
        String oldSource = "/mount/old/Movies/Old/old.mkv";
        String newSource = "/mount/new/Movies/New/new.mkv";
        Path oldFile = Files.writeString(strmRoot.resolve("old.strm"), oldSource);
        Path newFile = Files.writeString(strmRoot.resolve("new.strm"), newSource);
        Path unknown = Files.writeString(strmRoot.resolve("unknown.strm"), "/mount/new2/Movies/Unknown/file.mkv");
        EmbyClient emby = mock(EmbyClient.class);
        when(emby.listLibraries()).thenReturn(List.of(new EmbyLibrary("movies", "Movies", List.of(strmRoot.toString()))));
        when(emby.listLibraryMediaItemsForSync("movies")).thenReturn(List.of(
                syncItem("old", oldFile), syncItem("new", newFile), syncItem("unknown", unknown)));
        CloudDrive2MediaDeletion deletion = mock(CloudDrive2MediaDeletion.class);
        when(deletion.checkMediaSourcePaths(anyCollection())).thenReturn(
                new CloudDrive2MediaDeletion.MediaSourceCheckResult(Set.of(oldSource), Set.of()));
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("deletion", deletion);
        QuarkDirectClient quark = mock(QuarkDirectClient.class);
        AuthService auth = mock(AuthService.class);
        MediaLibrarySyncService service = new MediaLibrarySyncService(auth,
                new AdminMediaLibraryCatalogService(auth, emby, mock(MediaCapacityIndex.class)), emby, quark,
                beans.getBeanProvider(CloudDrive2MediaDeletion.class), new LocalStrmDeletion(properties), properties,
                targets.registry);

        var response = service.sync("movies", true, List.of(oldFile.toString(), newFile.toString(), unknown.toString()));

        assertThat(response.removedItems()).isEqualTo(1);
        assertThat(response.skippedItems()).isEqualTo(1);
        assertThat(oldFile).exists();
        assertThat(newFile).doesNotExist();
        assertThat(unknown).exists();
        verify(deletion).checkMediaSourcePaths(Set.of(oldSource, newSource));
        verify(emby).notifyMediaDeleted(List.of(newFile.toString()));
        verifyNoInteractions(quark);
    }

    private EmbyDeletionItem syncItem(String id, Path file) {
        return new EmbyDeletionItem(id, id, "Movie", file.toString(), null, null, List.of(file.toString()));
    }

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
