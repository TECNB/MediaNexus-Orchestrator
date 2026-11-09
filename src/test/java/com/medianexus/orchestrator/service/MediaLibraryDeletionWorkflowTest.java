package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.dto.admin.request.AdminAdultStorageDeletionRequest;
import com.medianexus.orchestrator.integration.clouddrive.CloudDrive2MediaStorage;
import com.medianexus.orchestrator.integration.clouddrive.CloudDrive2MediaStorage.Entry;
import com.medianexus.orchestrator.integration.emby.EmbyClient;
import com.medianexus.orchestrator.integration.emby.EmbyDeletionItem;
import com.medianexus.orchestrator.integration.emby.EmbyLibrary;
import com.medianexus.orchestrator.integration.emby.EmbyMediaLibraryItem;
import com.medianexus.orchestrator.mapper.MediaDeletionTaskMapper;
import com.medianexus.orchestrator.model.MediaDeletionTask;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class MediaLibraryDeletionWorkflowTest {

    private final AuthService authService = mock(AuthService.class);
    private final AdminMediaLibraryCatalogService catalogService = mock(AdminMediaLibraryCatalogService.class);
    private final EmbyClient embyClient = mock(EmbyClient.class);
    private final MediaSourceDeletion mediaSourceDeletion = mock(MediaSourceDeletion.class);
    private final LocalStrmDeletion localStrmDeletion = mock(LocalStrmDeletion.class);
    private final MediaDeletionTaskMapper taskMapper = mock(MediaDeletionTaskMapper.class);
    private final CloudDrive2MediaStorage storage = mock(CloudDrive2MediaStorage.class);
    private final MediaLibraryDeletionWorkflow workflow = new MediaLibraryDeletionWorkflow(
            authService,
            catalogService,
            embyClient,
            mediaSourceDeletion,
            localStrmDeletion,
            taskMapper,
            new ObjectMapper(),
            storage,
            mock(MediaCapacityIndex.class)
    );

    @Test
    void deletesUnindexedAdultFileThroughTheTaskQueue() {
        var request = new AdminAdultStorageDeletionRequest("adult-jav", "/disk/Adult/JAV/file.mp4", false, "fid", 10L);
        var target = new Entry(request.path(), false, "fid", 10);
        when(storage.confirmAdultTarget(request.library(), target)).thenReturn(target);
        when(storage.sourcePathMapping()).thenReturn(source -> source);
        var library = new EmbyLibrary("jav", "Adult-JAV", List.of("/srv/media/STRM/Adult/JAV"));
        when(catalogService.resolveLibrary(AdminMediaLibraryScope.ADULT_JAV)).thenReturn(library);

        workflow.startAdultStorage(request);
        var captor = ArgumentCaptor.forClass(MediaDeletionTask.class);
        verify(taskMapper).insert(captor.capture());
        var queued = captor.getValue();
        when(taskMapper.findNextActive()).thenReturn(queued);

        workflow.executeNext();

        verify(storage).deleteAdultTarget("adult-jav", target);
        assertThat(queued.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(queued.getStrmPaths()).isEqualTo("[]");
    }

    @Test
    void refusesFolderDeletionWhenAnEmbyItemHasAnotherVersionOutsideIt(@TempDir Path strmRoot) throws Exception {
        String folder = "/disk/Adult/Other/bundle";
        var request = new AdminAdultStorageDeletionRequest("adult-other", folder, true, "fid", null);
        var target = new Entry(folder, true, "fid", 0);
        when(storage.confirmAdultTarget(request.library(), new Entry(folder, true, "fid", -1))).thenReturn(target);
        when(storage.sourcePathMapping()).thenReturn(source -> source);
        var library = new EmbyLibrary("other", "Adult - Other", List.of(strmRoot.toString()));
        when(catalogService.resolveLibrary(AdminMediaLibraryScope.ADULT_OTHER)).thenReturn(library);
        String link = Files.createFile(strmRoot.resolve("item.strm")).toString();
        String movie = folder + "/inside.mp4";
        String outside = "/disk/Adult/Other/outside.mp4";
        when(localStrmDeletion.allStrmFiles()).thenReturn(List.of(link));
        when(localStrmDeletion.resolveMediaSourcePath(link)).thenReturn(movie);
        when(localStrmDeletion.resolveMediaSourcePath(movie)).thenReturn(movie);
        when(localStrmDeletion.resolveMediaSourcePath(outside)).thenReturn(outside);
        when(embyClient.listLibraryDeletionVideos("other", 0, 500)).thenReturn(List.of(
                new EmbyDeletionItem("item", "Movie", "Movie", link, null, null, List.of(movie, outside))
        ));

        workflow.startAdultStorage(request);
        var captor = ArgumentCaptor.forClass(MediaDeletionTask.class);
        verify(taskMapper).insert(captor.capture());
        var queued = captor.getValue();
        when(taskMapper.findNextActive()).thenReturn(queued);

        workflow.executeNext();

        verify(storage, never()).deleteAdultTarget("adult-other", target);
        assertThat(queued.getStatus()).isEqualTo("FAILED");
        assertThat(queued.getErrorMessage()).contains("媒体源不完全一致");
    }

    @Test
    void deletesAnAdultOtherCollectionByItsSharedDirectory() throws Exception {
        when(storage.sourcePathMapping()).thenReturn(source -> source);
        EmbyLibrary library = new EmbyLibrary(
                "adult-other-id", "Adult - Other", List.of("/srv/media/STRM/Adult/Other")
        );
        EmbyMediaLibraryItem collection = new EmbyMediaLibraryItem(
                "collection-1", "Creator collection", "Creator collection", "BoxSet", null,
                "2026-09-16T16:12:21Z", "poster-tag", null
        );
        when(catalogService.resolveLibrary(AdminMediaLibraryScope.ADULT_OTHER)).thenReturn(library);
        when(catalogService.requireDeletableItem(
                "collection-1", AdminMediaLibraryScope.ADULT_OTHER, library
        )).thenReturn(collection);
        when(embyClient.listCollectionItemsForDeletion("collection-1")).thenReturn(List.of(
                deletionItem("movie-1", "one"),
                deletionItem("movie-2", "two")
        ));

        var response = workflow.start("collection-1", "adult-other", null);

        ArgumentCaptor<MediaDeletionTask> taskCaptor = ArgumentCaptor.forClass(MediaDeletionTask.class);
        verify(taskMapper).insert(taskCaptor.capture());
        MediaDeletionTask task = taskCaptor.getValue();
        assertThat(response.targetLabel()).isEqualTo("整套合集");
        assertThat(task.getItemId()).isEqualTo("collection-1");
        assertThat(new ObjectMapper().readValue(task.getEmbyItemIds(), String[].class))
                .containsExactly("movie-1", "movie-2");
        assertThat(new ObjectMapper().readValue(task.getSourcePaths(), String[].class))
                .containsExactly(
                        "/srv/media/CloudNAS/PikPak/Media/Adult/Other/Creator collection/one.mp4",
                        "/srv/media/CloudNAS/PikPak/Media/Adult/Other/Creator collection/two.mp4"
                );
        assertThat(new ObjectMapper().readValue(task.getStrmPaths(), String[].class))
                .containsExactly(
                        "/srv/media/STRM/Adult/Other/Creator collection/one.strm",
                        "/srv/media/STRM/Adult/Other/Creator collection/two.strm"
                );
        verify(taskMapper).countActiveTarget("collection-1");

        when(taskMapper.findNextActive()).thenReturn(task);
        workflow.executeNext();

        verify(mediaSourceDeletion).delete(
                List.of("/srv/media/CloudNAS/PikPak/Media/Adult/Other/Creator collection"),
                "Creator collection"
        );
        verify(localStrmDeletion).delete(List.of(
                "/srv/media/STRM/Adult/Other/Creator collection"
        ));
        verify(embyClient).notifyMediaDeleted(List.of(
                "/srv/media/STRM/Adult/Other/Creator collection/one.strm",
                "/srv/media/STRM/Adult/Other/Creator collection/two.strm"
        ));
        assertThat(task.getStatus()).isEqualTo("SUCCEEDED");
    }

    @Test
    void persistsNullWhenRetryAndSuccessClearPreviousTaskState() {
        Stream.of("errorMessage", "finishedAt").forEach(fieldName -> {
            try {
                TableField mapping = MediaDeletionTask.class.getDeclaredField(fieldName)
                        .getAnnotation(TableField.class);
                assertThat(mapping).isNotNull();
                assertThat(mapping.updateStrategy()).isEqualTo(FieldStrategy.ALWAYS);
            } catch (NoSuchFieldException exception) {
                throw new AssertionError(exception);
            }
        });
    }

    @Test
    void allowsDeletingASelectedSeasonWhenEmbyPlacesItOutsideSeriesDirectory() throws Exception {
        EmbyLibrary library = new EmbyLibrary(
                "tv-id", "电视剧", List.of("/srv/media/STRM/TV")
        );
        EmbyMediaLibraryItem series = new EmbyMediaLibraryItem(
                "series-1", "新世界：虚拟货币争霸战", "新世界：虚拟货币争霸战", "Series", null,
                "2026-09-16T16:12:21Z", "poster-tag", null
        );
        EmbyDeletionItem season = new EmbyDeletionItem(
                "season-1", "Season 01", "Season", "/srv/media/STRM/TV/Season 01",
                1, "2026-09-16T16:12:21Z", List.of()
        );
        EmbyDeletionItem episode = new EmbyDeletionItem(
                "episode-1", "E01", "Episode", "/srv/media/STRM/TV/Season 01/E01.strm",
                1, "2026-09-16T16:12:21Z", List.of(
                        "/srv/media/CloudNAS/PikPak/TV/新世界：虚拟货币争霸战/Season 01/E01.mp4"
                )
        );
        when(catalogService.resolveLibrary(AdminMediaLibraryScope.TV)).thenReturn(library);
        when(catalogService.requireDeletableItem("series-1", AdminMediaLibraryScope.TV, library))
                .thenReturn(series);
        when(embyClient.getMediaItemForDeletion("tv-id", "Series", "series-1"))
                .thenReturn(new EmbyDeletionItem(
                        "series-1", series.title(), "Series", "/srv/media/STRM/TV/新世界：虚拟货币争霸战",
                        null, series.dateCreated(), List.of()
                ));
        when(embyClient.listSeriesSeasonsForDeletion("series-1")).thenReturn(List.of(season));
        when(embyClient.listSeasonEpisodesForDeletion("season-1")).thenReturn(List.of(episode));
        when(taskMapper.countActiveTarget("season-1")).thenReturn(0);

        var response = workflow.start("series-1", "tv", "season-1");

        assertThat(response.targetLabel()).isEqualTo("Season 01");
        ArgumentCaptor<MediaDeletionTask> taskCaptor = ArgumentCaptor.forClass(MediaDeletionTask.class);
        verify(taskMapper).insert(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getSeasonNumber()).isEqualTo(1);
    }

    private EmbyDeletionItem deletionItem(String id, String name) {
        return new EmbyDeletionItem(
                id,
                name,
                "Movie",
                "/srv/media/STRM/Adult/Other/Creator collection/" + name + ".strm",
                null,
                "2026-09-16T16:12:21Z",
                List.of("/srv/media/CloudNAS/PikPak/Media/Adult/Other/Creator collection/" + name + ".mp4")
        );
    }
}
