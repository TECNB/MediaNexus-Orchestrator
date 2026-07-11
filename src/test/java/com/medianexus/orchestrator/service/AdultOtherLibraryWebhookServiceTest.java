package com.medianexus.orchestrator.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.config.EmbyProperties;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AdultOtherLibraryWebhookServiceTest {

    private final AdultOtherLibraryAutomationRunner runner = mock(AdultOtherLibraryAutomationRunner.class);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final EmbyProperties properties = properties();
    private final AdultOtherLibraryWebhookService service = new AdultOtherLibraryWebhookService(
            properties,
            new ObjectMapper(),
            runner,
            scheduler,
            false
    );

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void batchesNewMovieIdsAndIgnoresNonMovieItems() {
        service.receiveLibraryEvent("secret", payload("library.new", "item-1", "Movie", "/adult/a.strm"));
        service.receiveLibraryEvent("secret", payload("library.new", "item-2", "Movie", "/adult/b.strm"));
        service.receiveLibraryEvent("secret", payload("library.new", "folder-1", "Folder", "/adult/folder"));

        verify(runner, timeout(1000)).processNewItems(Set.of("item-1", "item-2"));
    }

    @Test
    void batchesMovieAndFolderDeletionPathsIntoOneCleanup() {
        service.receiveLibraryEvent("secret", payload("library.deleted", "item-1", "Movie", "/adult/a.strm"));
        service.receiveLibraryEvent("secret", payload("library.deleted", "folder-1", "Folder", "/adult/folder"));

        verify(runner, timeout(1000)).processDeletedPaths(Set.of("/adult/a.strm", "/adult/folder"));
    }

    @Test
    void rejectsInvalidSecretBeforeSchedulingWork() {
        assertThrows(BusinessException.class, () -> service.receiveLibraryEvent(
                "wrong",
                payload("library.new", "item-1", "Movie", "/adult/a.strm")
        ));

        verify(runner, never()).processNewItems(Set.of("item-1"));
    }

    private EmbyProperties properties() {
        EmbyProperties result = new EmbyProperties();
        result.setWebhookSecret("secret");
        result.setAdultOtherAutomationEnabled(true);
        result.setAdultOtherNewEventDebounce(Duration.ofMillis(25));
        result.setAdultOtherDeleteEventDebounce(Duration.ofMillis(25));
        return result;
    }

    private String payload(String event, String id, String type, String path) {
        return """
                {
                  "Event": "%s",
                  "Item": {
                    "Id": "%s",
                    "Type": "%s",
                    "Path": "%s"
                  }
                }
                """.formatted(event, id, type, path);
    }
}
