package com.medianexus.orchestrator.integration.emby;

import java.util.List;

public record EmbyMediaLibraryPage(
        List<EmbyMediaLibraryItem> items,
        int totalRecordCount
) {
}
