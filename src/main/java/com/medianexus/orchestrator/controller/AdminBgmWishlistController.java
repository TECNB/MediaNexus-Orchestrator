package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.admin.response.AdminMediaDeletionTaskResponse;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.CatalogCandidate;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.IdentityInput;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.Item;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.Season;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.Source;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.SourceInput;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.Wishlist;
import com.medianexus.orchestrator.service.BgmWishlistService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/bgm-wishlist")
@Tag(name = "BGM 想看", description = "管理员配置想看来源、读取真实清单及确认目录资格")
@Validated
public class AdminBgmWishlistController {
    private final BgmWishlistService wishlist;

    public AdminBgmWishlistController(BgmWishlistService wishlist) {
        this.wishlist = wishlist;
    }

    @GetMapping("/sources")
    @Operation(summary = "读取独立想看来源配置")
    public ApiResponse<List<Source>> sources() {
        return ApiResponse.success(wishlist.listSources());
    }

    @PostMapping("/sources")
    @Operation(summary = "添加来源；首次启用会完整读取并建立基线，不创建任务")
    public ApiResponse<Source> create(@Valid @RequestBody SourceInput input) {
        return ApiResponse.success(wishlist.createSource(input));
    }

    @PutMapping("/sources/{sourceId}")
    @Operation(summary = "保存独立来源配置；暂停重开保留基线及执行记录")
    public ApiResponse<Source> update(@PathVariable("sourceId") String sourceId, @Valid @RequestBody SourceInput input) {
        return ApiResponse.success(wishlist.updateSource(sourceId, input));
    }

    @GetMapping("/sources/{sourceId}/items")
    @Operation(summary = "读取上次完整清单并重新检查资格；不刷新上游收藏")
    public ApiResponse<Wishlist> items(@PathVariable("sourceId") String sourceId) {
        return ApiResponse.success(wishlist.items(sourceId));
    }

    @GetMapping("/sources/{sourceId}/items/{subjectId}")
    @Operation(summary = "重新检查单条作品的在库状态和删除进度")
    public ApiResponse<Item> item(@PathVariable("sourceId") String sourceId, @PathVariable("subjectId") @Positive long subjectId) {
        return ApiResponse.success(wishlist.item(sourceId, subjectId));
    }

    @PostMapping("/sources/{sourceId}/items/{subjectId}/deletions")
    @Operation(summary = "为已确认的电影或单个目标季度创建永久删除任务")
    public ApiResponse<AdminMediaDeletionTaskResponse> delete(@PathVariable("sourceId") String sourceId,
            @PathVariable("subjectId") @Positive long subjectId, @Valid @RequestBody IdentityInput expected) {
        return ApiResponse.success(wishlist.deleteFromLibrary(sourceId, subjectId, expected));
    }

    @PostMapping("/sources/{sourceId}/refresh")
    @Operation(summary = "完整刷新官方想看清单；失败保留快照并返回 FAILED 状态")
    public ApiResponse<Wishlist> refresh(@PathVariable("sourceId") String sourceId) {
        return ApiResponse.success(wishlist.refresh(sourceId));
    }

    @GetMapping("/catalog/search")
    @Operation(summary = "搜索真实电影和剧集目录供人工确认")
    public ApiResponse<List<CatalogCandidate>> search(@RequestParam("query") @NotBlank @Size(max = 200) String query) {
        return ApiResponse.success(wishlist.searchCatalog(query));
    }

    @GetMapping("/catalog/SERIES/{tmdbId}/seasons")
    @Operation(summary = "读取真实剧集的可选目标季度")
    public ApiResponse<List<Season>> seasons(@PathVariable("tmdbId") @Positive int tmdbId) {
        return ApiResponse.success(wishlist.seasons(tmdbId));
    }

    @PutMapping("/sources/{sourceId}/items/{subjectId}/identity")
    @Operation(summary = "确认真实目录及季度并重新检查在库；不创建任务")
    public ApiResponse<Item> identity(@PathVariable("sourceId") String sourceId, @PathVariable("subjectId") @Positive long subjectId,
                                      @Valid @RequestBody IdentityInput input) {
        return ApiResponse.success(wishlist.correctIdentity(sourceId, subjectId, input));
    }
}
