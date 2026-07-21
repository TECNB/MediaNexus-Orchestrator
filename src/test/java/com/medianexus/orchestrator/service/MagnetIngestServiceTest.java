package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medianexus.orchestrator.config.OpenListProperties;
import com.medianexus.orchestrator.integration.openlist.OpenListClient;
import com.medianexus.orchestrator.integration.openlist.OpenListClientException;
import com.medianexus.orchestrator.integration.openlist.OpenListLibraryOrganizer;
import com.medianexus.orchestrator.integration.openlist.OpenListFileInfo;
import com.medianexus.orchestrator.mapper.MovieMagnetIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.MovieMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.SeriesMagnetIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.SeriesMagnetIngestTaskMapper;
import com.medianexus.orchestrator.model.MovieMagnetIngestTask;
import com.medianexus.orchestrator.model.SeriesMagnetIngestTask;
import com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan;
import com.medianexus.orchestrator.service.organization.LibraryOrganizationProgressObserver;
import com.medianexus.orchestrator.service.organization.LibraryOrganizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class MagnetIngestServiceTest {

    @Test
    void movieOrganizeRejectsTinyFilesDisguisedAsVideos() {
        OpenListClient openListClient = mock(OpenListClient.class);
        stubPathHelpers(openListClient);
        String savePath = "/movies/Furious 7 (2015)";
        String releasePath = savePath + "/release";
        String mainVideo = "Fast.&.Furious.7.2015.2160p.WEB-DL.60fps.H265.10bit.AAC-MOMOWEB.mp4";
        String adMkv = "【更多无水印高清电影请访问 www.BBEDDE.com】.MKV";
        String adMp4 = "【更多无水印蓝光原盘请访问 www.BBEDDE.com】.MP4";
        String advertisement = "【更多无水印蓝光电影请访问 www.BBEDDE.com】.DOC";
        when(openListClient.findFiles(savePath)).thenReturn(List.of(
                new OpenListFileInfo(mainVideo, 25_647_428_045L, false, releasePath),
                new OpenListFileInfo(adMkv, 636_976L, false, releasePath),
                new OpenListFileInfo(adMp4, 296_080L, false, releasePath),
                new OpenListFileInfo(advertisement, 296_080L, false, releasePath)
        ));
        when(openListClient.listFiles(savePath)).thenReturn(List.of());
        RecordingLibraryOrganizer organizer = new RecordingLibraryOrganizer();
        MagnetIngestService service = service(openListClient, organizer);

        Object result = ReflectionTestUtils.invokeMethod(service, "organizeMovieFiles", movieTask(savePath));

        LibraryOrganizationPlan plan = organizer.plan;
        String targetName = "Furious 7 (2015) 2160p WEB-DL H.265.mp4";
        assertThat(plan.renames()).containsExactly(
                new LibraryOrganizationPlan.RenameOperation(releasePath + "/" + mainVideo, targetName)
        );
        assertThat(plan.moves()).containsExactly(
                new LibraryOrganizationPlan.MoveOperation(releasePath + "/" + targetName, savePath)
        );
        assertThat(plan.deletions())
                .extracting(LibraryOrganizationPlan.DeleteOperation::path)
                .containsExactlyInAnyOrder(
                        releasePath + "/" + adMkv,
                        releasePath + "/" + adMp4,
                        releasePath + "/" + advertisement
        );
        assertThat(plan.expectedTargetNames()).containsExactly(targetName);
        assertThat(ReflectionTestUtils.getField(result, "organizedCount")).isEqualTo(1);
        assertThat(ReflectionTestUtils.getField(result, "skippedCount")).isEqualTo(3);
        assertThat(ReflectionTestUtils.getField(result, "videoCount")).isEqualTo(1);
    }

    @Test
    void movieOrganizeKeepsLargestFilePerLogicalVersionAndRetainsDistinctQualities() {
        OpenListClient openListClient = mock(OpenListClient.class);
        stubPathHelpers(openListClient);
        String savePath = "/movies/Furious 7 (2015)";
        String releasePath = savePath + "/release";
        String smallerContainer = "release.mkv";
        String largerContainer = "release.mp4";
        String distinctQuality = "release.1080p.mkv";
        when(openListClient.findFiles(savePath)).thenReturn(List.of(
                new OpenListFileInfo(smallerContainer, 200L * 1024L * 1024L, false, releasePath),
                new OpenListFileInfo(largerContainer, 300L * 1024L * 1024L, false, releasePath),
                new OpenListFileInfo(distinctQuality, 100L * 1024L * 1024L, false, releasePath)
        ));
        when(openListClient.listFiles(savePath)).thenReturn(List.of());
        RecordingLibraryOrganizer organizer = new RecordingLibraryOrganizer();
        MagnetIngestService service = service(openListClient, organizer);

        Object result = ReflectionTestUtils.invokeMethod(service, "organizeMovieFiles", movieTask(savePath));

        assertThat(organizer.plan.renames()).containsExactlyInAnyOrder(
                new LibraryOrganizationPlan.RenameOperation(
                        releasePath + "/" + largerContainer,
                        "Furious 7 (2015).mp4"
                ),
                new LibraryOrganizationPlan.RenameOperation(
                        releasePath + "/" + distinctQuality,
                        "Furious 7 (2015) 1080p.mkv"
                )
        );
        assertThat(organizer.plan.deletions()).containsExactly(
                new LibraryOrganizationPlan.DeleteOperation(releasePath + "/" + smallerContainer)
        );
        assertThat(ReflectionTestUtils.getField(result, "organizedCount")).isEqualTo(2);
        assertThat(ReflectionTestUtils.getField(result, "skippedCount")).isEqualTo(1);
        assertThat(ReflectionTestUtils.getField(result, "videoCount")).isEqualTo(2);
    }

    @Test
    void movieOrganizeStopsWithoutExecutingPlanWhenVideoSizeIsMissing() {
        OpenListClient openListClient = mock(OpenListClient.class);
        stubPathHelpers(openListClient);
        String savePath = "/movies/Furious 7 (2015)";
        when(openListClient.findFiles(savePath)).thenReturn(List.of(
                new OpenListFileInfo("release.mkv", null, false, savePath + "/release")
        ));
        when(openListClient.listFiles(savePath)).thenReturn(List.of());
        RecordingLibraryOrganizer organizer = new RecordingLibraryOrganizer();
        MagnetIngestService service = service(openListClient, organizer);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service,
                "organizeMovieFiles",
                movieTask(savePath)
        ))
                .isInstanceOf(OpenListClientException.class)
                .hasMessageContaining("OpenList 未返回电影视频文件大小")
                .hasMessageContaining("release.mkv");
        assertThat(organizer.plan).isNull();
    }

    @Test
    void animeSeriesOrganizeDeletesAuxiliaryDirectoryWithoutScanningIt() {
        OpenListClient openListClient = mock(OpenListClient.class);
        stubPathHelpers(openListClient);
        when(openListClient.listFiles("/anime/Show/Season 2")).thenReturn(List.of(
                new OpenListFileInfo("[Release]", null, true, "/anime/Show/Season 2")
        ));
        when(openListClient.listFiles("/anime/Show/Season 2/[Release]")).thenReturn(List.of(
                new OpenListFileInfo("[Group] Show - S02E01 1080p.mkv", 1024L, false, "/anime/Show/Season 2/[Release]"),
                new OpenListFileInfo("Scans", null, true, "/anime/Show/Season 2/[Release]")
        ));
        MagnetIngestService service = service(openListClient);

        ReflectionTestUtils.invokeMethod(service, "organizeSeriesFiles", animeSeriesTask());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> deletedNames = ArgumentCaptor.forClass(List.class);
        verify(openListClient).remove(eq("/anime/Show/Season 2/[Release]"), deletedNames.capture());
        assertThat(deletedNames.getValue()).containsExactly("Scans");
        verify(openListClient, never()).findFiles(anyString());
        verify(openListClient, never()).listFiles("/anime/Show/Season 2/[Release]/Scans");
    }

    @Test
    void animeSeriesVideoVisibilityCheckDoesNotScanAuxiliaryDirectories() {
        OpenListClient openListClient = mock(OpenListClient.class);
        stubPathHelpers(openListClient);
        when(openListClient.listFiles("/anime/Show/Season 2")).thenReturn(List.of(
                new OpenListFileInfo("[Release]", null, true, "/anime/Show/Season 2"),
                new OpenListFileInfo("Scans", null, true, "/anime/Show/Season 2")
        ));
        when(openListClient.listFiles("/anime/Show/Season 2/[Release]")).thenReturn(List.of(
                new OpenListFileInfo("[Group] Show - S02E01 1080p.mkv", 1024L, false, "/anime/Show/Season 2/[Release]")
        ));
        MagnetIngestService service = service(openListClient);

        Boolean hasVideoFiles = ReflectionTestUtils.invokeMethod(
                service,
                "hasVideoFiles",
                "/anime/Show/Season 2",
                true
        );

        assertThat(hasVideoFiles).isTrue();
        verify(openListClient, never()).findFiles(anyString());
        verify(openListClient, never()).listFiles("/anime/Show/Season 2/Scans");
    }

    @Test
    void animeSeriesOrganizeSplitsLargeRenameAndMoveBatches() {
        OpenListClient openListClient = mock(OpenListClient.class);
        stubPathHelpers(openListClient);
        List<OpenListFileInfo> releaseFiles = new ArrayList<>();
        for (int episode = 1; episode <= 25; episode++) {
            releaseFiles.add(new OpenListFileInfo(
                    String.format("[Group] Show - S02E%02d 1080p.mkv", episode),
                    1024L,
                    false,
                    "/anime/Show/Season 2/[Release]"
            ));
        }
        when(openListClient.listFiles("/anime/Show/Season 2")).thenReturn(List.of(
                new OpenListFileInfo("[Release]", null, true, "/anime/Show/Season 2")
        ));
        when(openListClient.listFiles("/anime/Show/Season 2/[Release]")).thenReturn(releaseFiles);
        MagnetIngestService service = service(openListClient);

        ReflectionTestUtils.invokeMethod(service, "organizeSeriesFiles", animeSeriesTask());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> renameBatches = ArgumentCaptor.forClass(Map.class);
        verify(openListClient, times(3)).batchRename(eq("/anime/Show/Season 2/[Release]"), renameBatches.capture());
        assertThat(renameBatches.getAllValues()).extracting(Map::size).containsExactly(10, 10, 5);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> moveBatches = ArgumentCaptor.forClass(List.class);
        verify(openListClient, times(3)).move(
                eq("/anime/Show/Season 2/[Release]"),
                eq("/anime/Show/Season 2"),
                moveBatches.capture()
        );
        assertThat(moveBatches.getAllValues()).extracting(List::size).containsExactly(10, 10, 5);
    }

    @Test
    void animeSeriesOrganizeUsesChineseLibraryTitleInsteadOfOriginalTitle() {
        OpenListClient openListClient = mock(OpenListClient.class);
        stubPathHelpers(openListClient);
        String seasonPath = "/anime/无职转生：到了异世界就拿出真本事/Season 1";
        when(openListClient.listFiles(seasonPath)).thenReturn(List.of(
                new OpenListFileInfo(
                        "[Group] Mushoku Tensei - S01E01 1080p.mkv",
                        1024L,
                        false,
                        seasonPath
                )
        ));
        MagnetIngestService service = service(openListClient);
        SeriesMagnetIngestTask task = new SeriesMagnetIngestTask();
        task.setId("mushoku-tensei-season-1");
        task.setTaskProductType("ANIME");
        task.setTitle("无职转生：到了异世界就拿出真本事");
        task.setOriginalTitle("無職転生 ～異世界行ったら本気だす～");
        task.setSeasonNumber(1);
        task.setSavePath(seasonPath);
        task.setTempPath(seasonPath);

        ReflectionTestUtils.invokeMethod(service, "organizeSeriesFiles", task);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> renames = ArgumentCaptor.forClass(Map.class);
        verify(openListClient).batchRename(eq(seasonPath), renames.capture());
        assertThat(renames.getValue())
                .containsEntry(
                        "[Group] Mushoku Tensei - S01E01 1080p.mkv",
                        "无职转生：到了异世界就拿出真本事 - S01E01 1080p.mkv"
                );
    }

    private MagnetIngestService service(OpenListClient openListClient) {
        return service(openListClient, new OpenListLibraryOrganizer(openListClient));
    }

    private MagnetIngestService service(OpenListClient openListClient, LibraryOrganizer libraryOrganizer) {
        return new MagnetIngestService(
                mock(MovieMagnetIngestTaskMapper.class),
                mock(MovieMagnetIngestTaskLogMapper.class),
                mock(SeriesMagnetIngestTaskMapper.class),
                mock(SeriesMagnetIngestTaskLogMapper.class),
                openListClient,
                new OpenListProperties(),
                new MovieSeriesFileRenameService(),
                libraryOrganizer,
                null,
                null,
                null,
                null
        );
    }

    private MovieMagnetIngestTask movieTask(String savePath) {
        MovieMagnetIngestTask task = new MovieMagnetIngestTask();
        task.setId("furious-7");
        task.setTitle("Furious 7");
        task.setOriginalTitle("Furious 7");
        task.setYear(2015);
        task.setSavePath(savePath);
        task.setTempPath(savePath);
        return task;
    }

    private SeriesMagnetIngestTask animeSeriesTask() {
        SeriesMagnetIngestTask task = new SeriesMagnetIngestTask();
        task.setId("series-1");
        task.setTaskProductType("ANIME");
        task.setTitle("Show");
        task.setSeasonNumber(2);
        task.setSavePath("/anime/Show/Season 2");
        task.setTempPath("/anime/Show/Season 2");
        return task;
    }

    private void stubPathHelpers(OpenListClient openListClient) {
        when(openListClient.normalizePath(anyString())).thenAnswer(invocation -> normalizePath(invocation.getArgument(0)));
        when(openListClient.joinPath(anyString(), anyString())).thenAnswer(invocation ->
                normalizePath(invocation.getArgument(0) + "/" + invocation.getArgument(1)));
    }

    private String normalizePath(String path) {
        String normalized = path == null ? "" : path.trim().replaceAll("/{2,}", "/");
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        return normalized.length() > 1 && normalized.endsWith("/")
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
    }

    private static class RecordingLibraryOrganizer implements LibraryOrganizer {

        private LibraryOrganizationPlan plan;

        @Override
        public void organize(
                LibraryOrganizationPlan plan,
                LibraryOrganizationProgressObserver progressObserver
        ) {
            this.plan = plan;
        }
    }
}
