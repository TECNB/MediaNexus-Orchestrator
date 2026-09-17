package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.admin.request.AdminMediaIdentifyRequest;
import com.medianexus.orchestrator.dto.admin.request.AdminMediaPosterSelectRequest;
import com.medianexus.orchestrator.dto.admin.response.AdminMediaLibraryPageResponse;
import com.medianexus.orchestrator.dto.admin.response.AdminMediaMetadataCandidateResponse;
import com.medianexus.orchestrator.dto.admin.response.AdminMediaPosterCandidateResponse;
import com.medianexus.orchestrator.service.AdminMediaLibraryCatalogService;
import com.medianexus.orchestrator.service.AdminMediaLibraryPoster;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/admin/media-library")
@Tag(name = "管理员媒体库", description = "管理员浏览、重新识别及选择 Emby 媒体封面")
public class AdminMediaLibraryController {

    private static final int PAGE_SIZE = 24;

    private final AdminMediaLibraryCatalogService catalogService;

    public AdminMediaLibraryController(AdminMediaLibraryCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/items")
    @Operation(summary = "分页浏览媒体库", description = "仅返回指定虚拟媒体库中的 Movie 或 Series 作品，按入库时间倒序。")
    public ApiResponse<AdminMediaLibraryPageResponse> listItems(
            @Parameter(description = "媒体库：movies、tv、anime、adult-other 或 adult-jav")
            @RequestParam @NotBlank String library,
            @Parameter(description = "页码，从 1 开始")
            @RequestParam(defaultValue = "1")
            @Min(value = 1, message = "页码必须大于 0")
            @Max(value = 1_000_000, message = "页码不能大于 1000000") int page,
            @Parameter(description = "固定为 24")
            @RequestParam(name = "page_size", defaultValue = "24")
            @Min(value = PAGE_SIZE, message = "每页条数必须为 24")
            @Max(value = PAGE_SIZE, message = "每页条数必须为 24") int pageSize,
            @Parameter(description = "可选的标题关键词")
            @RequestParam(required = false) @Size(max = 200, message = "搜索关键词不能超过 200 个字符") String search
    ) {
        return ApiResponse.success(catalogService.listItems(library, page, pageSize, search));
    }

    @GetMapping("/items/{itemId}/poster")
    @Operation(summary = "获取媒体封面", description = "管理员按列表返回的条目 id 代理 Emby Primary 图片。")
    public ResponseEntity<byte[]> getPoster(
            @Parameter(description = "Emby 条目 id")
            @PathVariable @NotBlank String itemId
    ) {
        AdminMediaLibraryPoster poster = catalogService.getPoster(itemId);
        return ResponseEntity.ok()
                .contentType(safeImageContentType(poster.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .body(poster.bytes());
    }

    @GetMapping("/items/{itemId}/metadata-candidates")
    @Operation(summary = "搜索重新识别候选", description = "电影及 Adult 使用 Movie 搜索，电视剧及动漫使用 Series 搜索。")
    public ApiResponse<List<AdminMediaMetadataCandidateResponse>> searchMetadataCandidates(
            @PathVariable @NotBlank String itemId,
            @RequestParam @NotBlank String library,
            @RequestParam @NotBlank @Size(max = 200) String query,
            @RequestParam(required = false) @Min(1800) @Max(3000) Integer year
    ) {
        return ApiResponse.success(catalogService.searchMetadataCandidates(itemId, library, query, year));
    }

    @GetMapping("/items/{itemId}/metadata-candidates/{candidateId}/image")
    public ResponseEntity<byte[]> getMetadataCandidateImage(
            @PathVariable @NotBlank String itemId,
            @PathVariable @NotBlank String candidateId,
            @RequestParam @NotBlank String library,
            @RequestParam @NotBlank @Size(max = 200) String query,
            @RequestParam(required = false) @Min(1800) @Max(3000) Integer year
    ) {
        return preview(catalogService.getMetadataCandidateImage(itemId, library, query, year, candidateId));
    }

    @PostMapping("/items/{itemId}/identify")
    @Operation(summary = "应用重新识别候选", description = "重查候选后更新作品身份和元数据；可显式替换所有图片。")
    public ApiResponse<Void> identify(
            @PathVariable @NotBlank String itemId,
            @Valid @RequestBody AdminMediaIdentifyRequest request
    ) {
        catalogService.applyMetadataCandidate(itemId, request.library(), request.query(), request.year(),
                request.candidateId(), request.replaceAllImages());
        return ApiResponse.success();
    }

    @GetMapping("/items/{itemId}/poster-candidates")
    @Operation(summary = "获取候选封面", description = "最多返回 40 张 Primary 候选图片。")
    public ApiResponse<List<AdminMediaPosterCandidateResponse>> listPosterCandidates(
            @PathVariable @NotBlank String itemId,
            @RequestParam @NotBlank String library
    ) {
        return ApiResponse.success(catalogService.listPosterCandidates(itemId, library));
    }

    @GetMapping("/items/{itemId}/poster-candidates/{candidateId}/image")
    public ResponseEntity<byte[]> getPosterCandidateImage(
            @PathVariable @NotBlank String itemId,
            @PathVariable @NotBlank String candidateId,
            @RequestParam @NotBlank String library
    ) {
        return preview(catalogService.getPosterCandidateImage(itemId, library, candidateId));
    }

    @PostMapping("/items/{itemId}/poster-selection")
    @Operation(summary = "选择封面", description = "仅替换 Primary 封面，不更新作品 ID 或元数据。")
    public ApiResponse<Void> selectPoster(
            @PathVariable @NotBlank String itemId,
            @Valid @RequestBody AdminMediaPosterSelectRequest request
    ) {
        catalogService.selectPosterCandidate(itemId, request.library(), request.candidateId());
        return ApiResponse.success();
    }

    private ResponseEntity<byte[]> preview(AdminMediaLibraryPoster image) {
        return ResponseEntity.ok()
                .contentType(safeImageContentType(image.contentType()))
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(image.bytes());
    }

    private MediaType safeImageContentType(String contentType) {
        try {
            MediaType parsed = MediaType.parseMediaType(contentType);
            return "image".equalsIgnoreCase(parsed.getType())
                    ? parsed
                    : MediaType.APPLICATION_OCTET_STREAM;
        } catch (IllegalArgumentException exception) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
