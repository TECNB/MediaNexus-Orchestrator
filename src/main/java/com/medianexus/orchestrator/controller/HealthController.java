package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    @GetMapping
    public ApiResponse<HealthResponse> health() {
        return ApiResponse.success(new HealthResponse("UP", "MediaNexus-Orchestrator"));
    }

    public record HealthResponse(String status, String service) {
    }
}
