package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.anime.request.AnimeSubscriptionPreviewRequest;
import com.medianexus.orchestrator.dto.anime.response.AnimeSearchResponse;
import com.medianexus.orchestrator.dto.anime.response.AnimeSubtitleGroupsResponse;
import com.medianexus.orchestrator.dto.anime.response.AnimeSubscriptionPreviewResponse;
import com.medianexus.orchestrator.dto.anime.response.AnimeSubscriptionResponse;
import com.medianexus.orchestrator.service.AnimeSearchService;
import com.medianexus.orchestrator.service.AnimeSubscriptionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/resources/anime")
public class AnimeResourceController {

    private final AnimeSearchService animeSearchService;
    private final AnimeSubscriptionService animeSubscriptionService;

    public AnimeResourceController(
            AnimeSearchService animeSearchService,
            AnimeSubscriptionService animeSubscriptionService
    ) {
        this.animeSearchService = animeSearchService;
        this.animeSubscriptionService = animeSubscriptionService;
    }

    @GetMapping("/search")
    public ApiResponse<AnimeSearchResponse> search(@RequestParam(name = "term", required = false) String term) {
        return ApiResponse.success(animeSearchService.search(term));
    }

    @GetMapping("/groups")
    public ApiResponse<AnimeSubtitleGroupsResponse> groups(
            @RequestParam(name = "sourceUrl", required = false) String sourceUrl
    ) {
        return ApiResponse.success(animeSubscriptionService.groups(sourceUrl));
    }

    @GetMapping("/{id}/groups")
    public ApiResponse<AnimeSubtitleGroupsResponse> groupsForItem(
            @PathVariable String id,
            @RequestParam(name = "sourceUrl", required = false) String sourceUrl
    ) {
        return ApiResponse.success(animeSubscriptionService.groups(sourceUrl));
    }

    @PostMapping("/preview")
    public ApiResponse<AnimeSubscriptionPreviewResponse> preview(@RequestBody AnimeSubscriptionPreviewRequest request) {
        return ApiResponse.success(animeSubscriptionService.preview(request));
    }

    @PostMapping("/subscribe")
    public ApiResponse<AnimeSubscriptionResponse> subscribe(@RequestBody AnimeSubscriptionPreviewRequest request) {
        return ApiResponse.success(animeSubscriptionService.subscribe(request));
    }
}
