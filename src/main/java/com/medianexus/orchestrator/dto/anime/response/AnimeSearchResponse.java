package com.medianexus.orchestrator.dto.anime.response;

import java.util.List;

public record AnimeSearchResponse(List<AnimeSearchItem> items, int total) {
}
