package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.emby.response.EmbyLibraryListResponse;
import com.medianexus.orchestrator.dto.emby.response.EmbyLibraryRefreshResponse;
import com.medianexus.orchestrator.service.EmbyLibraryRefreshService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/emby/libraries")
@Tag(name = "Emby 媒体库", description = "列出 Emby 媒体库并按媒体库提交刷新")
public class EmbyLibraryController {

    private final EmbyLibraryRefreshService embyLibraryRefreshService;

    public EmbyLibraryController(EmbyLibraryRefreshService embyLibraryRefreshService) {
        this.embyLibraryRefreshService = embyLibraryRefreshService;
    }

    @GetMapping
    @Operation(summary = "列出可刷新的 Emby 媒体库")
    public ApiResponse<EmbyLibraryListResponse> listLibraries() {
        return ApiResponse.success(embyLibraryRefreshService.listLibraries());
    }

    @PostMapping("/{libraryId}/refresh")
    @Operation(summary = "刷新指定 Emby 媒体库")
    public ApiResponse<EmbyLibraryRefreshResponse> refreshLibrary(
            @Parameter(description = "Emby 媒体库 ItemId")
            @PathVariable String libraryId
    ) {
        return ApiResponse.success(embyLibraryRefreshService.refreshLibrary(libraryId));
    }
}
