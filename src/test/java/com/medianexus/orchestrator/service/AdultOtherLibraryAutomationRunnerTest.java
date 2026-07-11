package com.medianexus.orchestrator.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medianexus.orchestrator.config.EmbyProperties;
import com.medianexus.orchestrator.dto.emby.response.AdultOtherCollectionSyncGroupResponse;
import com.medianexus.orchestrator.dto.emby.response.AdultOtherCollectionSyncRunResponse;
import com.medianexus.orchestrator.integration.emby.EmbyClient;
import com.medianexus.orchestrator.integration.emby.EmbyItemState;
import com.medianexus.orchestrator.integration.emby.EmbyLibrary;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AdultOtherLibraryAutomationRunnerTest {

    private final EmbyClient embyClient = mock(EmbyClient.class);
    private final AdultOtherCollectionSyncService collectionSyncService =
            mock(AdultOtherCollectionSyncService.class);
    private final AdultOtherAutomationRunRecorder automationRunRecorder =
            mock(AdultOtherAutomationRunRecorder.class);
    private final EmbyProperties properties = properties();
    private final AdultOtherLibraryAutomationRunner runner = new AdultOtherLibraryAutomationRunner(
            properties,
            embyClient,
            collectionSyncService,
            automationRunRecorder,
            new SameThreadExecutorService(),
            duration -> {
            }
    );

    @Test
    void refreshesOnlyMissingPrimaryThenReconcilesMembershipAndArtwork() {
        when(automationRunRecorder.start("NEW_ITEMS", 2)).thenReturn("automation-1");
        EmbyItemState missing = state("item-1", false, "/srv/media/STRM/Adult/Other/7.11/Creator/a.strm");
        EmbyItemState ready = state("item-2", true, "/srv/media/STRM/Adult/Other/7.11/Creator/b.strm");
        when(embyClient.listLibraries()).thenReturn(List.of(adultOtherLibrary()));
        when(embyClient.listItemStates(Set.of("item-1", "item-2")))
                .thenReturn(List.of(missing, ready))
                .thenReturn(List.of(missing, ready))
                .thenReturn(List.of(state("item-1", true, missing.path()), ready));
        when(collectionSyncService.reconcileAutomatic())
                .thenReturn(syncResponse("collection-1", "Creator"));
        when(collectionSyncService.collectionNameForPath(any(), any()))
                .thenReturn("Creator");
        when(embyClient.hasPrimaryImage("collection-1")).thenReturn(true);

        runner.processNewItems(Set.of("item-1", "item-2"));

        verify(embyClient).refreshItemImages("item-1");
        verify(embyClient, never()).refreshItemImages("item-2");
        verify(embyClient, times(3)).listItemStates(Set.of("item-1", "item-2"));
        verify(collectionSyncService, times(2)).reconcileAutomatic();
        verify(embyClient).refreshCollectionImages("collection-1");
        verify(embyClient).materializePrimaryImage("collection-1");
        verify(automationRunRecorder).completeNewItems(
                "automation-1", 0, 0, 1, 1
        );
    }

    @Test
    void ignoresDeletionOutsideAdultOtherLibrary() {
        when(embyClient.listLibraries()).thenReturn(List.of(adultOtherLibrary()));

        runner.processDeletedPaths(Set.of("/srv/media/STRM/Movie/example.strm"));

        verify(collectionSyncService, times(0)).cleanupAutomatic();
    }

    @Test
    void reconcilesEmptyCollectionsOnceForDeletedFolderBurst() {
        when(automationRunRecorder.start("DELETIONS", 2)).thenReturn("automation-2");
        when(embyClient.listLibraries()).thenReturn(List.of(adultOtherLibrary()));
        when(collectionSyncService.cleanupAutomatic()).thenReturn(syncResponse(null, "unused"));

        runner.processDeletedPaths(Set.of(
                "/srv/media/STRM/Adult/Other/6.25",
                "/srv/media/STRM/Adult/Other/电报/Folder"
        ));

        verify(collectionSyncService).cleanupAutomatic();
    }

    private EmbyProperties properties() {
        EmbyProperties result = new EmbyProperties();
        result.setAdultOtherLibraryName("Adult - Other");
        result.setAdultOtherPrimaryQuietPeriod(Duration.ZERO);
        result.setAdultOtherPrimaryPollInterval(Duration.ZERO);
        result.setAdultOtherRefreshTimeout(Duration.ofSeconds(1));
        return result;
    }

    private EmbyLibrary adultOtherLibrary() {
        return new EmbyLibrary("library-1", "Adult - Other", List.of("/srv/media/STRM/Adult/Other"));
    }

    private EmbyItemState state(String id, boolean primary, String path) {
        return new EmbyItemState(id, id, "Movie", path, primary, primary ? 2 : 0);
    }

    private AdultOtherCollectionSyncRunResponse syncResponse(String collectionId, String collectionName) {
        return new AdultOtherCollectionSyncRunResponse(
                "run-1", "AUTO_WEBHOOK", "SUCCEEDED", 2, null,
                2, 2, 0, 1, 1, 0, 0, 1, 0, 0, 0,
                null, null, null,
                List.of(new AdultOtherCollectionSyncGroupResponse(
                        collectionName, "7.11/" + collectionName, 2, true,
                        "UNCHANGED", collectionId, 0, null, List.of("a", "b")
                ))
        );
    }

    private static final class SameThreadExecutorService extends AbstractExecutorService {

        private boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }
}
