package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.*;
import com.medianexus.orchestrator.dto.douban.DoubanChartContract.*;
import com.medianexus.orchestrator.service.DoubanChartService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/douban-charts")
@Tag(name = "豆瓣公共榜单入库", description = "成员只读；所有变更由应用层强制管理员授权")
public class DoubanChartController {
    private final DoubanChartService charts;
    public DoubanChartController(DoubanChartService charts) { this.charts = charts; }

    @GetMapping
    @io.swagger.v3.oas.annotations.Operation(summary = "读取榜单与共享业务限额")
    public ApiResponse<Overview> overview() { return ApiResponse.success(charts.overview()); }

    @GetMapping("/{chartId}")
    @io.swagger.v3.oas.annotations.Operation(summary = "读取本榜当前和掉榜历史候选")
    public ApiResponse<Board> board(@PathVariable ChartId chartId) { return ApiResponse.success(charts.board(chartId)); }

    @PostMapping("/{chartId}/refresh")
    @io.swagger.v3.oas.annotations.Operation(summary = "刷新公共榜单与历史进度；失败保留旧榜")
    public ApiResponse<Board> refresh(@PathVariable ChartId chartId) { return ApiResponse.success(charts.refresh(chartId)); }

    @PutMapping("/{chartId}/automation")
    @io.swagger.v3.oas.annotations.Operation(summary = "设置本榜自动入库并绑定实际管理员身份")
    public ApiResponse<Board> automation(@PathVariable ChartId chartId, @Valid @RequestBody AutoInput input) {
        return ApiResponse.success(charts.automation(chartId, input));
    }

    @PutMapping("/settings")
    @io.swagger.v3.oas.annotations.Operation(summary = "设置榜单共享每日业务限额；null 为不限制")
    public ApiResponse<Settings> settings(@Valid @RequestBody LimitsInput input) { return ApiResponse.success(charts.settings(input)); }

    @PutMapping("/{chartId}/candidates/{subjectId}/exclusion")
    @io.swagger.v3.oas.annotations.Operation(summary = "持久排除或解除本榜候选")
    public ApiResponse<Board> exclusion(@PathVariable ChartId chartId, @PathVariable long subjectId, @Valid @RequestBody ExclusionInput input) {
        return ApiResponse.success(charts.exclusion(chartId, subjectId, input));
    }

    @PutMapping("/{chartId}/candidates/{subjectId}/identity")
    @io.swagger.v3.oas.annotations.Operation(summary = "纠正真实目录及目标季；不放宽安全规则")
    public ApiResponse<Board> identity(@PathVariable ChartId chartId, @PathVariable long subjectId, @Valid @RequestBody IdentityInput input) {
        return ApiResponse.success(charts.identity(chartId, subjectId, input));
    }

    @GetMapping("/catalog/search")
    @io.swagger.v3.oas.annotations.Operation(summary = "按电影或剧集搜索真实目录")
    public ApiResponse<List<CatalogCandidate>> search(@RequestParam String query, @RequestParam String mediaType) {
        return ApiResponse.success(charts.search(query, mediaType));
    }

    @GetMapping("/catalog/SERIES/{id}/seasons")
    @io.swagger.v3.oas.annotations.Operation(summary = "读取真实剧集目标季")
    public ApiResponse<List<Season>> seasons(@PathVariable int id) { return ApiResponse.success(charts.seasons(id)); }

    @PostMapping("/{chartId}/operations")
    @io.swagger.v3.oas.annotations.Operation(summary = "立即持久受理本榜一轮；重复请求返回已有操作")
    public ApiResponse<Operation> execute(@PathVariable ChartId chartId) { return ApiResponse.success(charts.execute(chartId)); }

    @GetMapping("/{chartId}/operations")
    @io.swagger.v3.oas.annotations.Operation(summary = "分页读取本榜操作及实时关联任务")
    public ApiResponse<Operations> operations(@PathVariable ChartId chartId, @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.success(charts.operations(chartId, page, pageSize));
    }

    @GetMapping("/operations/{id}")
    @io.swagger.v3.oas.annotations.Operation(summary = "读取持久操作三阶段结果")
    public ApiResponse<Operation> operation(@PathVariable String id) { return ApiResponse.success(charts.get(id)); }

    @PostMapping("/operations/{id}/stop")
    @io.swagger.v3.oas.annotations.Operation(summary = "停止本轮新受理；不撤销已创建任务")
    public ApiResponse<Operation> stop(@PathVariable String id) { return ApiResponse.success(charts.stop(id)); }
}
