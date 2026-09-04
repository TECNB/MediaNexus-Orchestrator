package com.medianexus.orchestrator.dto.javdb.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "JAVDB Cookie 状态；不包含 Cookie 原文")
public record JavdbCredentialStatusResponse(
        @JsonProperty("credential_configured")
        boolean credentialConfigured,
        @JsonProperty("credential_valid")
        boolean credentialValid,
        @JsonProperty("last_validated_at")
        LocalDateTime lastValidatedAt
) {
}
