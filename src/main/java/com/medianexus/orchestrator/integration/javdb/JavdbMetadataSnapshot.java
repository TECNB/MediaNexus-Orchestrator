package com.medianexus.orchestrator.integration.javdb;

import java.util.List;

/** Verified work identity and source claims; version flags belong to the downloaded file, not this record. */
public record JavdbMetadataSnapshot(
        String code, String sourceId, String detailUrl, String title, String coverUrl,
        String releaseDate, List<String> actors, List<String> directors, List<String> genres,
        List<String> studios, Double communityRating
) { }
