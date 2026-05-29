package com.medianexus.orchestrator.dto;

public record CreateTestUserRequest(
        String username,
        String email,
        String displayName,
        Boolean enabled
) {
}
