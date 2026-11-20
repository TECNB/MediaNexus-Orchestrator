package com.medianexus.orchestrator.integration.prowlarr;

public record ProwlarrRelease(
        String title,
        Long size,
        Integer seeders,
        Integer leechers,
        Integer grabs,
        String indexer,
        String publishDate,
        Integer indexerId,
        String guid,
        String downloadRef,
        String infoHash,
        String torrentRef
) {
    public ProwlarrRelease(String title, Long size, Integer seeders, Integer leechers, Integer grabs,
            String indexer, String publishDate, Integer indexerId, String guid, String downloadRef) {
        this(title, size, seeders, leechers, grabs, indexer, publishDate, indexerId, guid, downloadRef, null, null);
    }
    @Override public String toString() {
        return "ProwlarrRelease[title=" + title + ", indexerId=" + indexerId + ", references=REDACTED]";
    }
}
