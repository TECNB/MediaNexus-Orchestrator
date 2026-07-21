package com.medianexus.orchestrator.service.catalog;

import java.util.List;

public record SeriesCatalogSeasons(
        Integer tmdbId,
        String title,
        List<Integer> seasonNumbers
) {

    public int seasonCount() {
        return seasonNumbers.size();
    }
}
