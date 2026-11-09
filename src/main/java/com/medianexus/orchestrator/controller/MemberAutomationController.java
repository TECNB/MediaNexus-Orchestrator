package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.Provider;
import com.medianexus.orchestrator.service.MemberAutomation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/automation")
@Tag(name = "个人自动化")
public class MemberAutomationController {
    private final MemberAutomation automation;

    public MemberAutomationController(MemberAutomation automation) { this.automation = automation; }

    @GetMapping("/access")
    @Operation(summary = "读取当前账号可见的自动化入口")
    public ApiResponse<MemberAutomation.Access> access() { return ApiResponse.success(automation.access()); }

    @GetMapping("/wishlists")
    @Operation(summary = "读取本站归属成员为当前账号的想看快照和最近操作")
    public ApiResponse<List<MemberAutomation.Wishlist>> wishlists(@RequestParam Provider provider) {
        return ApiResponse.success(automation.wishlists(provider));
    }
}
