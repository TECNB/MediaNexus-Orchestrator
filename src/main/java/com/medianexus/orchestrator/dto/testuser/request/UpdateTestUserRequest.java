package com.medianexus.orchestrator.dto.testuser.request;

public record UpdateTestUserRequest(
        String username,
        String email,
        String displayName,
        Boolean enabled
) {
}
