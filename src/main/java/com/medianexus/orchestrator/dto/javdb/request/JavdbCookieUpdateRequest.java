package com.medianexus.orchestrator.dto.javdb.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "JAVDB 登录 Cookie 更新请求")
public record JavdbCookieUpdateRequest(
        @Schema(description = "完整 JAVDB Cookie；仅用于覆盖保存，接口不会返回原值")
        @NotBlank(message = "JAVDB Cookie 不能为空")
        String cookie
) {
}
