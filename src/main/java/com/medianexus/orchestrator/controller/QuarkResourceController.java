package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.resources.request.QuarkReleaseSearchRequest;
import com.medianexus.orchestrator.dto.resources.response.QuarkReleaseSearchResponse;
import com.medianexus.orchestrator.service.PanSouResourceSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/resources/quark")
@Tag(name = "Quark 资源搜索", description = "通过 PanSou 搜索并规范化 Quark 分享候选")
public class QuarkResourceController {

    private final PanSouResourceSearchService searchService;

    public QuarkResourceController(PanSouResourceSearchService searchService) {
        this.searchService = searchService;
    }

    @PostMapping("/releases/search")
    @Operation(summary = "搜索 Quark 分享候选")
    public ApiResponse<QuarkReleaseSearchResponse> search(
            @Valid @RequestBody QuarkReleaseSearchRequest request
    ) {
        return ApiResponse.success(searchService.search(request));
    }
}
