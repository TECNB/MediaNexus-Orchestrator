package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.settings.SettingsDtos.*;
import com.medianexus.orchestrator.service.AuthService;
import com.medianexus.orchestrator.service.SettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/settings")
@Tag(name = "管理员设置", description = "配置来源、凭据状态和实际生效版本")
public class AdminSettingsController {
    private final SettingsService settings;
    private final AuthService auth;
    public AdminSettingsController(SettingsService settings, AuthService auth) { this.settings = settings; this.auth = auth; }

    @GetMapping
    @Operation(summary = "读取可管理设置目录")
    public ApiResponse<Directory> directory() { auth.requireAdminUser(); return ApiResponse.success(settings.directory()); }

    @GetMapping("/groups/{id}")
    @Operation(summary = "读取组的保存值、有效值与来源")
    public ApiResponse<Group> get(@PathVariable String id) { auth.requireAdminUser(); return ApiResponse.success(settings.get(id)); }

    @PutMapping("/groups/{id}")
    @Operation(summary = "校验候选配置并按版本原子保存", description = "连接或凭据变化时执行必要只读检查；失败不发布候选配置。")
    public ApiResponse<Group> save(@PathVariable String id, @RequestBody Update update) {
        auth.requireAdminUser(); return ApiResponse.success(settings.save(id, update));
    }

    @PostMapping("/groups/{id}/restore")
    @Operation(summary = "显式恢复部署初始值", description = "不清除账号目标或历史资源；不等同停用集成。")
    public ApiResponse<Group> restore(@PathVariable String id, @RequestBody Restore restore) {
        auth.requireAdminUser(); return ApiResponse.success(settings.restore(id, restore.version()));
    }

    @PostMapping("/groups/{id}/check")
    @Operation(summary = "只读检查候选配置", description = "不保存，不触发业务写入；未传请求体时检查当前配置。")
    public ApiResponse<Probe> check(@PathVariable String id, @RequestBody(required = false) Update update) {
        auth.requireAdminUser(); return ApiResponse.success(settings.check(id, update));
    }

    @GetMapping("/groups/{id}/resources")
    @Operation(summary = "只读获取当前有效连接的资源状态")
    public ApiResponse<Probe> resources(@PathVariable String id) { auth.requireAdminUser(); return ApiResponse.success(settings.resources(id)); }
}
