package com.medianexus.orchestrator.integration.emby;

import java.util.List;

public record EmbyDeletionItem(
        String id,
        String name,
        String type,
        String path,
        Integer indexNumber,
        String dateCreated,
        List<String> mediaSourcePaths
) {
}
