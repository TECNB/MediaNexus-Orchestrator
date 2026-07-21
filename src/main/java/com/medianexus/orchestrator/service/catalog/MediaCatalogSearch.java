package com.medianexus.orchestrator.service.catalog;

import java.util.List;

public interface MediaCatalogSearch {

    List<SeriesCatalogItem> searchSeries(String term);

    SeriesCatalogSeasons getSeriesSeasons(Integer tmdbId);
}
