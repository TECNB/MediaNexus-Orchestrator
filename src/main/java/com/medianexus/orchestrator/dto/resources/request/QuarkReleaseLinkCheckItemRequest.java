package com.medianexus.orchestrator.dto.resources.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

@JsonIgnoreProperties(ignoreUnknown = false)
public record QuarkReleaseLinkCheckItemRequest(
        @NotBlank(message = "候选 ID 不能为空")
        String id,
        @JsonProperty("share_url")
        @NotBlank(message = "Quark 分享链接不能为空")
        String shareUrl
) {
}
