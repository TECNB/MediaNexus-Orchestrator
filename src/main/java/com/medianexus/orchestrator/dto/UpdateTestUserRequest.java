package com.medianexus.orchestrator.dto;

public record UpdateTestUserRequest(
        String username,
        String email,
        String displayName,
        Boolean enabled
) {
}
