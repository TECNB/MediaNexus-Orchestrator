package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.subtitle.SubtitleCommitRequest;
import com.medianexus.orchestrator.dto.subtitle.response.SubtitlePreparationResponse;
import com.medianexus.orchestrator.dto.subtitle.response.SubtitleUploadCheckResponse;
import org.springframework.web.bind.annotation.RequestBody;
import com.medianexus.orchestrator.dto.subtitle.response.SubtitleUploadListResponse;
import com.medianexus.orchestrator.dto.subtitle.response.SubtitleLocationsResponse;
import com.medianexus.orchestrator.dto.subtitle.response.SubtitleUploadLogListResponse;
import com.medianexus.orchestrator.dto.subtitle.response.SubtitleUploadResponse;
import com.medianexus.orchestrator.service.SubtitleUploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/subtitles")
@Tag(name = "字幕上传", description = "电影和剧集字幕 ZIP/单文件上传、OpenList 写入和处理日志接口")
public class SubtitleUploadController {

    private final SubtitleUploadService subtitleUploadService;

    public SubtitleUploadController(SubtitleUploadService subtitleUploadService) {
        this.subtitleUploadService = subtitleUploadService;
    }

    @GetMapping("/locations")
    @Operation(summary = "查找字幕可上传目标", description = "需要登录；仅返回实际存在媒体目录的目标 ID 和显示名，不返回服务器路径。")
    public ApiResponse<SubtitleLocationsResponse> locations(
            @Parameter(description = "媒体类型：movie 或 series")
            @RequestParam(value = "media_type", defaultValue = "movie") String mediaType,
            @Parameter(description = "电影或剧集标题") @RequestParam("title") String title,
            @Parameter(description = "原始标题；存在时优先匹配目录")
            @RequestParam(value = "original_title", required = false) String originalTitle,
            @Parameter(description = "电影年份；电影必填")
            @RequestParam(value = "year", required = false) Integer year,
            @Parameter(description = "剧集季数；剧集必填")
            @RequestParam(value = "season_number", required = false) Integer seasonNumber
    ) {
        return ApiResponse.success(subtitleUploadService.locations(mediaType, title, originalTitle, year, seasonNumber));
    }

    @PostMapping(value = "/preparations", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "预览字幕导入", description = "需要登录；仅在本地解析单字幕或 ZIP/RAR/7z，30 分钟有效；不会创建上传批次或远端写入。")
    public ApiResponse<SubtitlePreparationResponse> prepare(
            @RequestParam("file") MultipartFile file,
            @RequestParam("media_type") String mediaType,
            @RequestParam("title") String title,
            @RequestParam(value = "original_title", required = false) String originalTitle,
            @RequestParam(value = "year", required = false) Integer year,
            @RequestParam(value = "season_number", required = false) Integer seasonNumber,
            @RequestParam(value = "target_id", required = false) String targetId,
            @Parameter(description = "chs、cht 或 unknown；仅对未识别语言文件使用的兜底")
            @RequestParam(value = "language", defaultValue = "unknown") String language
    ) {
        return ApiResponse.success(subtitleUploadService.prepare(file, mediaType, title, originalTitle,
                year, seasonNumber, targetId, language));
    }

    @PostMapping("/preparations/{id}/commit")
    @Operation(summary = "确认字幕导入", description = "仅预览所有者可确认；ID 只能消费一次，重新检查视频与冲突，变化时返回 409。")
    public ApiResponse<SubtitleUploadResponse> commit(@PathVariable String id, @RequestBody SubtitleCommitRequest request) {
        return ApiResponse.success(subtitleUploadService.commit(id, request));
    }

    @GetMapping("/uploads/{id}/check")
    @Operation(summary = "检查字幕落位", description = "鉴权同上传批次；主动刷新源目录后逐文件检查名称和大小，COMPLETE 不表示 AS 或 Emby 已完成。")
    public ApiResponse<SubtitleUploadCheckResponse> check(@PathVariable String id) {
        return ApiResponse.success(subtitleUploadService.checkUpload(id));
    }

    @PostMapping(value = "/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "上传电影或剧集字幕",
            description = "接收字幕并创建后台处理批次；电影按名称和年份定位目录，剧集按名称和季数定位 Season 目录。"
    )
    public ApiResponse<SubtitleUploadResponse> uploadSubtitle(
            @Parameter(description = "字幕文件，支持单个字幕文件或 ZIP")
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "媒体类型：movie 或 series")
            @RequestParam(value = "media_type", defaultValue = "movie") String mediaType,
            @Parameter(description = "电影或剧集标题")
            @RequestParam("title") String title,
            @Parameter(description = "原始标题；存在时优先用于生成目标目录名")
            @RequestParam(value = "original_title", required = false) String originalTitle,
            @Parameter(description = "电影年份；电影上传必填，剧集上传可选")
            @RequestParam(value = "year", required = false) Integer year,
            @Parameter(description = "剧集季数；剧集上传必填")
            @RequestParam(value = "season_number", required = false) Integer seasonNumber,
            @Parameter(description = "是否覆盖目标目录中同名字幕")
            @RequestParam(value = "overwrite", defaultValue = "false") boolean overwrite,
            @Parameter(description = "下载目标稳定 ID；单命中可不传，多命中未选择返回 409，所选目标必须实际存在媒体目录")
            @RequestParam(value = "target_id", required = false) String targetId
    ) {
        return ApiResponse.success(subtitleUploadService.uploadSubtitle(
                file,
                mediaType,
                title,
                originalTitle,
                year,
                seasonNumber,
                overwrite,
                targetId
        ));
    }

    @GetMapping("/uploads")
    @Operation(summary = "列出最近字幕上传批次", description = "返回当前用户最近上传批次；管理员可见所有用户批次。")
    public ApiResponse<SubtitleUploadListResponse> listUploads() {
        return ApiResponse.success(subtitleUploadService.listUploads());
    }

    @GetMapping("/uploads/{uploadId}")
    @Operation(summary = "获取字幕上传批次详情", description = "按上传批次 id 返回状态、目标路径和文件明细。")
    public ApiResponse<SubtitleUploadResponse> getUpload(
            @Parameter(description = "上传批次 id")
            @PathVariable String uploadId
    ) {
        return ApiResponse.success(subtitleUploadService.getUpload(uploadId));
    }

    @GetMapping("/uploads/{uploadId}/logs")
    @Operation(summary = "获取字幕上传批次日志", description = "按上传批次 id 返回解析、命名、上传和失败阶段的日志。")
    public ApiResponse<SubtitleUploadLogListResponse> getUploadLogs(
            @Parameter(description = "上传批次 id")
            @PathVariable String uploadId
    ) {
        return ApiResponse.success(subtitleUploadService.getUploadLogs(uploadId));
    }
}
