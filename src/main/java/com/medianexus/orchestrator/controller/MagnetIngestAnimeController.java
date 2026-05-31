package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.magnet.AnimeMagnetSearchResponse;
import com.medianexus.orchestrator.service.AnimeMagnetSearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/magnet-ingest/anime")
public class MagnetIngestAnimeController {

    private final AnimeMagnetSearchService animeMagnetSearchService;

    public MagnetIngestAnimeController(AnimeMagnetSearchService animeMagnetSearchService) {
        this.animeMagnetSearchService = animeMagnetSearchService;
    }

    @GetMapping("/search")
    public ApiResponse<AnimeMagnetSearchResponse> search(
            @RequestParam(name = "term", required = false) String term
    ) {
        return ApiResponse.success(animeMagnetSearchService.search(term));
    }
}
