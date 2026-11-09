package com.medianexus.orchestrator.integration.clouddrive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.config.CloudDrive2Properties;
import com.medianexus.orchestrator.config.OpenListProperties;
import com.medianexus.orchestrator.integration.clouddrive.CloudDrive2MediaStorage.Entry;
import com.medianexus.orchestrator.service.settings.targets.IngestTargetRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CloudDrive2MediaStorageTest {
    private final CloudDrive2FileOperations files = mock(CloudDrive2FileOperations.class);
    private final IngestTargetRegistry registry = mock(IngestTargetRegistry.class);
    private final OpenListProperties openList = new OpenListProperties();
    private final CloudDrive2Properties cloud = new CloudDrive2Properties();
    private final CloudDrive2MediaStorage storage = new CloudDrive2MediaStorage(files, registry, cloud, openList);

    @BeforeEach
    void configuredAdultRoot() {
        openList.setAdultRootPath("/pikpak/Adult");
        when(registry.rootsForExistingMedia("ADULT", "/pikpak/Adult")).thenReturn(List.of("/pikpak/Adult"));
        when(registry.toCloudDrivePath("/pikpak/Adult", cloud.getIngestPathPrefix(), cloud.getCloudDrivePathPrefix()))
                .thenReturn("/disk/Adult");
    }

    @Test
    void refusesRootSiblingAndTraversalBeforeReadingStorage() {
        for (String path : List.of("/disk/Adult/JAV", "/disk/Adult/JAV2/movie.mp4", "/disk/Adult/JAV/../Other/x")) {
            assertThatThrownBy(() -> storage.confirmAdultTarget("adult-jav", new Entry(path, true, "fid", 0)))
                    .isInstanceOf(BusinessException.class);
        }
        verifyNoInteractions(files);
    }

    @Test
    void collectsNestedFilesWithoutCountingDirectoryMetadataAsBytes() {
        String root = "/disk/Adult/JAV/collection";
        when(files.list(root, false)).thenReturn(List.of(
                new CloudDrive2FileEntry("first.mp4", root + "/first.mp4", 12, false, "first"),
                new CloudDrive2FileEntry("nested", root + "/nested", 999, true, "folder")
        ));
        when(files.list(root + "/nested", false)).thenReturn(List.of(
                new CloudDrive2FileEntry("second.mp4", root + "/nested/second.mp4", 27, false, "second")
        ));

        assertThat(storage.cachedFolderFiles(root)).containsExactlyInAnyOrderEntriesOf(
                Map.of(root + "/first.mp4", 12L, root + "/nested/second.mp4", 27L));
    }

    @Test
    void refusesReplacementAtTheSamePathWhenTheWorkerRuns() {
        String path = "/disk/Adult/JAV/episode.mp4";
        when(files.list("/disk/Adult/JAV", true))
                .thenReturn(List.of(new CloudDrive2FileEntry("episode.mp4", path, 42, false, "replacement")));
        assertThatThrownBy(() -> storage.deleteAdultTarget("adult-jav", new Entry(path, false, "original", 42)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已变化");
        verify(files).list("/disk/Adult/JAV", true);
        verifyNoMoreInteractions(files);
    }
}
