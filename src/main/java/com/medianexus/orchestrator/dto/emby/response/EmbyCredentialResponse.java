package com.medianexus.orchestrator.dto.emby.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "当前用户或管理员可查看的 Emby 托管凭据")
public record EmbyCredentialResponse(
        @Schema(description = "该用户是否由 MediaNexus 托管 Emby 账号")
        boolean managed,
        @Schema(description = "Emby 用户名；历史未托管用户为空", nullable = true)
        String username,
        @Schema(description = "8 位 Emby 托管密码；历史未托管用户为空", nullable = true)
        String password,
        @Schema(description = "Emby 用户 id；仅用于管理员排查", nullable = true)
        @JsonProperty("emby_user_id")
        String embyUserId
) {
    public static EmbyCredentialResponse unmanaged() {
        return new EmbyCredentialResponse(false, null, null, null);
    }
}
