package com.medianexus.orchestrator.dto.magnet.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "Adult 批量下载链接导入任务创建请求")
public record AdultMagnetIngestTaskCreateRequest(
        @Schema(description = "Adult 分类：JAV 或 OTHER")
        @NotBlank(message = "Adult 分类不能为空")
        String category,

        @Schema(description = "多条 magnet 或 ed2k 链接，最多 50 条")
        @NotEmpty(message = "下载链接列表不能为空")
        @Size(max = 50, message = "单批最多提交 50 条下载链接")
        List<String> magnets
) {
}
