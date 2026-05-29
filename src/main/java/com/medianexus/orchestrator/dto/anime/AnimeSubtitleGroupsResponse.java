package com.medianexus.orchestrator.dto.anime;

import java.util.List;

public record AnimeSubtitleGroupsResponse(List<AnimeSubtitleGroup> groups, int total) {
}
