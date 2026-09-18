package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.integration.emby.EmbyClient;
import com.medianexus.orchestrator.integration.emby.EmbyDeletionItem;
import com.medianexus.orchestrator.integration.emby.EmbyLibrary;
import com.medianexus.orchestrator.integration.emby.EmbyMediaLibraryItem;
import com.medianexus.orchestrator.mapper.MediaDeletionTaskMapper;
import com.medianexus.orchestrator.model.MediaDeletionTask;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MediaLibraryDeletionWorkflowTest {

    private final AuthService authService = mock(AuthService.class);
    private final AdminMediaLibraryCatalogService catalogService = mock(AdminMediaLibraryCatalogService.class);
    private final EmbyClient embyClient = mock(EmbyClient.class);
    private final MediaSourceDeletion mediaSourceDeletion = mock(MediaSourceDeletion.class);
    private final LocalStrmDeletion localStrmDeletion = mock(LocalStrmDeletion.class);
    private final MediaDeletionTaskMapper taskMapper = mock(MediaDeletionTaskMapper.class);
    private final MediaLibraryDeletionWorkflow workflow = new MediaLibraryDeletionWorkflow(
            authService,
            catalogService,
            embyClient,
            mediaSourceDeletion,
            localStrmDeletion,
            taskMapper,
            new ObjectMapper()
    );

    @Test
    void deletesAnAdultOtherCollectionByItsSharedDirectory() throws Exception {
        EmbyLibrary library = new EmbyLibrary(
                "adult-other-id", "Adult - Other", List.of("/srv/media/STRM/Adult/Other")
        );
        EmbyMediaLibraryItem collection = new EmbyMediaLibraryItem(
                "collection-1", "Creator collection", "BoxSet", null,
                "2026-09-16T16:12:21Z", "poster-tag"
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
