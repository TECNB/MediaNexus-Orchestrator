package com.medianexus.orchestrator.integration.emby;

import java.util.Optional;

public interface EmbyItemMetadataLookup {

    Optional<EmbyItemMetadata> findItemMetadata(String userId, String itemId);
}
