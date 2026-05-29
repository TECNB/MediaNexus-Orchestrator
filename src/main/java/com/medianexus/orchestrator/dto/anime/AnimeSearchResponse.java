package com.medianexus.orchestrator.dto.anime;

import java.util.List;

public record AnimeSearchResponse(List<AnimeSearchItem> items, int total) {
}
