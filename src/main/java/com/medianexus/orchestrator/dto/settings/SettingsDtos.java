package com.medianexus.orchestrator.dto.settings;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/** 管理响应仅含脱敏值；密钥输入不支持清空。 */
public final class SettingsDtos {
    private SettingsDtos() {}

    @Schema(description = "配置更新；省略字段保持原值，version 用于并发冲突检测")
    public record Update(Long version, Map<String, Object> values, Map<String, SecretInput> secrets) {
        @JsonAnySetter public void rejectUnknown(String key, Object value) { throw invalid(); }
        @Override public String toString() { return "SettingsUpdate[redacted]"; }
    }
    @Schema(description = "凭据操作：KEEP 保持且不传 value；REPLACE 必须提供非空新值")
    public record SecretInput(String action, String value) {
        @JsonAnySetter public void rejectUnknown(String key, Object value) { throw invalid(); }
        @Override public String toString() { return "SecretInput[redacted]"; }
    }
    @Schema(description = "显式恢复部署初始值；不影响账号目标或历史资源")
    public record Restore(Long version) {
        @JsonAnySetter public void rejectUnknown(String key, Object value) { throw invalid(); }
    }
    @Schema(description = "设置分组目录")
    public record Directory(List<Summary> groups) {}
    @Schema(description = "设置分组及真实生效方式")
    public record Summary(String id, String label, String applyMode, boolean writable) {}
    @Schema(description = "字段元数据；敏感字段仅返回配置状态")
    public record Field(String key, String label, String type, boolean writable, boolean secret) {}
    @Schema(description = "保存值、有效值、逐字段来源与版本；凭据不回显")
    public record Group(String id, String label, long version,
                        @Schema(description = "最近保存时间；未保存时为 null", nullable = true) String updatedAt,
                        String applyMode, String status, List<String> restartServices,
                        String effectiveFingerprint, List<Field> fields, Map<String, Object> saved,
                        Map<String, Object> effective, Map<String, String> sources, Map<String, Object> constraints) {}
    @Schema(description = "只读检查结果；不代表获得写权限或播放验收通过")
    public record Probe(String status, String message, String checkedAt, String source, List<Resource> resources) {}
    @Schema(description = "经过白名单脱敏的上游资源")
    public record Resource(String id, String name, Map<String, Object> attributes) {}

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.BAD_REQUEST, "配置请求包含未知字段");
    }
}
