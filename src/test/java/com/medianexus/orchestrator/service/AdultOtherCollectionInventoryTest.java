package com.medianexus.orchestrator.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.EmbyProperties;
import com.medianexus.orchestrator.dto.emby.response.AdultOtherCollectionInventoryResponse;
import com.medianexus.orchestrator.dto.emby.response.AdultOtherCollectionSourceFolderResponse;
import com.medianexus.orchestrator.integration.emby.EmbyClient;
import com.medianexus.orchestrator.integration.emby.EmbyCollection;
import com.medianexus.orchestrator.integration.emby.EmbyItem;
import com.medianexus.orchestrator.integration.emby.EmbyLibrary;
import com.medianexus.orchestrator.mapper.AdultOtherCollectionKnownItemMapper;
import com.medianexus.orchestrator.mapper.AdultOtherCollectionSyncGroupMapper;
import com.medianexus.orchestrator.mapper.AdultOtherCollectionSyncRunMapper;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AdultOtherCollectionInventoryTest {

    @Test
    void readsCollectionMembersConcurrently() throws Exception {
        AuthService authService = mock(AuthService.class);
        EmbyClient embyClient = mock(EmbyClient.class);
        EmbyProperties properties = new EmbyProperties();
        properties.setAdultOtherLibraryName("Adult - Other");
        properties.setAdultOtherCollectionReadConcurrency(2);
        AdultOtherCollectionSyncService service = new AdultOtherCollectionSyncService(
                authService,
                properties,
                embyClient,
                mock(AdultOtherCollectionSyncRunMapper.class),
                mock(AdultOtherCollectionSyncGroupMapper.class),
                mock(AdultOtherCollectionKnownItemMapper.class),
                new ObjectMapper()
        );
        when(embyClient.listLibraries()).thenReturn(List.of(
                new EmbyLibrary("library-1", "Adult - Other", List.of("/adult"))
        ));
        when(embyClient.listLibraryVideoItems("library-1")).thenReturn(List.of(
                item("a-1", "/adult/7.11/A/a.strm"),
                item("a-2", "/adult/7.11/A/b.strm"),
                item("b-1", "/adult/7.11/B/a.strm"),
                item("b-2", "/adult/7.11/B/b.strm")
        ));
        when(embyClient.listCollections()).thenReturn(List.of(
                new EmbyCollection("collection-a", "A"),
                new EmbyCollection("collection-b", "B")
        ));
        CyclicBarrier simultaneousReads = new CyclicBarrier(2);
        when(embyClient.listCollectionVideoItems(anyString())).thenAnswer(invocation -> {
            simultaneousReads.await(2, TimeUnit.SECONDS);
            String collectionId = invocation.getArgument(0);
            return "collection-a".equals(collectionId)
                    ? List.of(item("a-1", "/adult/7.11/A/a.strm"), item("a-2", "/adult/7.11/A/b.strm"))
                    : List.of(item("b-1", "/adult/7.11/B/a.strm"), item("b-2", "/adult/7.11/B/b.strm"));
        });

        AdultOtherCollectionInventoryResponse inventory = service.collectionInventory();

        assertEquals(2, inventory.healthyGroupCount());
        service.shutdownCollectionReadExecutor();
    }

    @Test
    void derivesActionableHealthOnlyFromCurrentEmbyState() {
        AuthService authService = mock(AuthService.class);
        EmbyClient embyClient = mock(EmbyClient.class);
        AdultOtherCollectionSyncRunMapper runMapper = mock(AdultOtherCollectionSyncRunMapper.class);
        AdultOtherCollectionSyncGroupMapper groupMapper = mock(AdultOtherCollectionSyncGroupMapper.class);
        AdultOtherCollectionKnownItemMapper knownItemMapper = mock(AdultOtherCollectionKnownItemMapper.class);
        EmbyProperties properties = new EmbyProperties();
        properties.setAdultOtherLibraryName("Adult - Other");
        AdultOtherCollectionSyncService service = new AdultOtherCollectionSyncService(
                authService,
                properties,
                embyClient,
                runMapper,
                groupMapper,
                knownItemMapper,
                new ObjectMapper()
        );
        EmbyLibrary library = new EmbyLibrary("library-1", "Adult - Other", List.of("/adult"));
        when(embyClient.listLibraries()).thenReturn(List.of(library));
        when(embyClient.listLibraryVideoItems("library-1")).thenReturn(List.of(
                item("a-1", "/adult/7.11/A/a.strm"),
                item("a-2", "/adult/7.11/A/b.strm"),
                item("b-1", "/adult/7.11/B/a.strm"),
                item("d-1", "/adult/7.11/D/a.strm"),
                item("d-2", "/adult/7.11/D/b.strm")
        ));
        when(embyClient.listCollections()).thenReturn(List.of(new EmbyCollection("collection-a", "A")));
        when(embyClient.listCollectionVideoItems("collection-a")).thenReturn(List.of(
                item("a-1", "/adult/7.11/A/a.strm")
        ));

        AdultOtherCollectionInventoryResponse inventory = service.collectionInventory();

        assertEquals(1, inventory.sourceFolderCount());
        assertEquals(3, inventory.groupCount());
        assertEquals(0, inventory.healthyGroupCount());
        assertEquals(1, inventory.pendingCreateGroupCount());
        assertEquals(1, inventory.pendingMemberGroupCount());
        assertEquals(1, inventory.skippedGroupCount());
        AdultOtherCollectionSourceFolderResponse folder = inventory.sourceFolders().get(0);
        assertEquals("7.11", folder.path());
        assertEquals("MIXED", folder.healthStatus());
        verify(runMapper, never()).selectFolderRunSummaries();
    }

    private EmbyItem item(String id, String path) {
        return new EmbyItem(id, id, "Movie", path, "2026-07-11T00:00:00Z");
    }
}
