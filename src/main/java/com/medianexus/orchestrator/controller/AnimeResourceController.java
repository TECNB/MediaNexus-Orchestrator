package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.anime.AnimeSearchResponse;
import com.medianexus.orchestrator.service.AnimeSearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/resources/anime")
public class AnimeResourceController {

    private final AnimeSearchService animeSearchService;

    public AnimeResourceController(AnimeSearchService animeSearchService) {
        this.animeSearchService = animeSearchService;
    }

    @GetMapping("/search")
    public ApiResponse<AnimeSearchResponse> search(@RequestParam(name = "term", required = false) String term) {
        return ApiResponse.success(animeSearchService.search(term));
    }
}
