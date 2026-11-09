package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.resources.request.ReleaseEpisodeCoverageRequest;
import com.medianexus.orchestrator.dto.resources.response.ReleaseEpisodeCoverageResponse;
import com.medianexus.orchestrator.service.AuthService;
import com.medianexus.orchestrator.service.TorrentEpisodeCoverage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/resources/releases")
@Tag(name = "发布正片覆盖", description = "登录用户可选查询，不作为搜索或入库前置条件")
public class ReleaseEpisodeCoverageController {

    private final AuthService authService;
    private final TorrentEpisodeCoverage episodeCoverage;

    public ReleaseEpisodeCoverageController(AuthService authService, TorrentEpisodeCoverage episodeCoverage) {
        this.authService = authService;
        this.episodeCoverage = episodeCoverage;
    }

    @PostMapping("/episode-coverage")
    @Operation(summary = "查询或排队核验发布正片集数", description = "立即返回缓存或 CHECKING；无可信来源、繁忙或核验失败返回 UNVERIFIED，可稍后再次查询。")
    public ApiResponse<ReleaseEpisodeCoverageResponse> check(@Valid @RequestBody ReleaseEpisodeCoverageRequest request) {
        authService.requireCurrentUser();
        return ApiResponse.success(episodeCoverage.check(request.downloadRef(), request.tmdbId(), request.seasonNumber()));
    }
}
