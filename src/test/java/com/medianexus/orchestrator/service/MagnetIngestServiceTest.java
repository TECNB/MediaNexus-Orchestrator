package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medianexus.orchestrator.config.OpenListProperties;
import com.medianexus.orchestrator.integration.openlist.OpenListClient;
import com.medianexus.orchestrator.integration.openlist.OpenListLibraryOrganizer;
import com.medianexus.orchestrator.integration.openlist.OpenListFileInfo;
import com.medianexus.orchestrator.mapper.MovieMagnetIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.MovieMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.SeriesMagnetIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.SeriesMagnetIngestTaskMapper;
import com.medianexus.orchestrator.model.SeriesMagnetIngestTask;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class MagnetIngestServiceTest {

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
        return new MagnetIngestService(
                mock(MovieMagnetIngestTaskMapper.class),
                mock(MovieMagnetIngestTaskLogMapper.class),
                mock(SeriesMagnetIngestTaskMapper.class),
                mock(SeriesMagnetIngestTaskLogMapper.class),
                openListClient,
                new OpenListProperties(),
                new MovieSeriesFileRenameService(),
                new OpenListLibraryOrganizer(openListClient),
                null,
                null,
                null,
                null
        );
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
}
