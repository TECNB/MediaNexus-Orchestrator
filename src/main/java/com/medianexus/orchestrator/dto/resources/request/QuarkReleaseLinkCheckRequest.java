package com.medianexus.orchestrator.dto.resources.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = false)
public record QuarkReleaseLinkCheckRequest(
        @JsonProperty("view_token")
        String viewToken,
        @Valid
        @NotEmpty(message = "待检查链接不能为空")
        @Size(max = 6, message = "每批最多检查 6 个链接")
        List<QuarkReleaseLinkCheckItemRequest> items
) {
}
