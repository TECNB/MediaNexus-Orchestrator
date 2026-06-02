package com.medianexus.orchestrator.dto.testuser.request;

public record CreateTestUserRequest(
        String username,
        String email,
        String displayName,
        Boolean enabled
) {
}
