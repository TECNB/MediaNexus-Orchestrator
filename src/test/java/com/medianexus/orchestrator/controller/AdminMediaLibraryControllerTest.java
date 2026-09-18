package com.medianexus.orchestrator.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medianexus.orchestrator.dto.admin.response.AdminMediaLibraryPageResponse;
import com.medianexus.orchestrator.service.AdminMediaLibraryCatalogService;
import com.medianexus.orchestrator.service.AdminMediaLibraryPoster;
import com.medianexus.orchestrator.service.MediaLibraryDeletionWorkflow;
import com.medianexus.orchestrator.service.MediaLibrarySyncService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class AdminMediaLibraryControllerTest {

    private final AdminMediaLibraryCatalogService catalogService = mock(AdminMediaLibraryCatalogService.class);
    private final MediaLibraryDeletionWorkflow deletionWorkflow = mock(MediaLibraryDeletionWorkflow.class);
    private final MediaLibrarySyncService syncService = mock(MediaLibrarySyncService.class);
    private final AdminMediaLibraryController controller = new AdminMediaLibraryController(
            catalogService, deletionWorkflow, syncService
    );

    @Test
    void delegatesListQueryWithoutChangingItsPagingContract() {
        AdminMediaLibraryPageResponse page = new AdminMediaLibraryPageResponse(List.of(), 3, 24, 50);
        when(catalogService.listItems("anime", 3, 24, "title", true)).thenReturn(page);

        var response = controller.listItems("anime", 3, 24, "title", true);

        verify(catalogService).listItems("anime", 3, 24, "title", true);
        assertThat(response.data()).isSameAs(page);
    }

    @Test
    void returnsPosterContentTypeAndPrivateBrowserCacheHeaders() {
        when(catalogService.getPoster("item-1"))
                .thenReturn(new AdminMediaLibraryPoster(new byte[]{1, 2}, "image/webp"));

        var response = controller.getPoster("item-1");

        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType("image/webp"));
        assertThat(response.getHeaders().getCacheControl()).contains("private", "max-age=86400");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getBody()).containsExactly(1, 2);
    }
}
