package com.medianexus.orchestrator.dto.accounts;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/** HTTP 仅含展示白名单；运行 AS 表单、DAV 凭据和临时授权不属于响应类型。 */
public final class AccountDtos {
    private AccountDtos() {}
    @Schema(description = "接入缺项；信息不包含上游原始错误或认证值")
    public record Issue(String code, String stage, String field, String message) {}
    @Schema(description = "外部阶段只读检查")
    public record Check(String stage, String state, String checkedAt, String message) {}
    @Schema(description = "按实际存储观测的容量；未取得的数值为 null")
    public record Capacity(Long totalBytes, Long usedBytes, Long freeBytes, String observedAt, String state, String message) {}
    @Schema(description = "类别默认目标；未接管时目标为 null")
    public record CategoryDefault(String category, String targetId) {}
    @Schema(description = "计划中的类别默认变化")
    public record DefaultChange(String category, String previousTargetId, String targetId) {}
    @Schema(description = "类别绑定展示；不返回完整 AS JSON 或认证信息")
    public record LibraryPath(String libraryId, String libraryName, String relativePath, String path) {}
    public record Binding(String category, String rootPath, String animePathTemplate, String asUuid,
                          boolean asRequestBodyConfigured, String mediaSourceRoot, String strmRootPath,
                          String embyLibraryId, String embyLibraryName, List<LibraryPath> embyLibraries) {
        public Binding(String category, String rootPath, String animePathTemplate, String asUuid,
                       boolean asRequestBodyConfigured, String mediaSourceRoot, String strmRootPath,
                       String embyLibraryId, String embyLibraryName) {
            this(category, rootPath, animePathTemplate, asUuid, asRequestBodyConfigured, mediaSourceRoot, strmRootPath,
                    embyLibraryId, embyLibraryName, embyLibraryId == null ? List.of() : List.of(new LibraryPath(embyLibraryId, embyLibraryName, "", strmRootPath)));
        }
    }
    @Schema(description = "账号展示白名单，不包含外部认证或原始配置")
    public record Account(String id, String displayName, String status, boolean acceptingNewTasks,
                          List<String> defaultCategories, String ingestPrefix, String cloudDrivePrefix,
                          String mediaSourcePrefix, long openListStorageId, String openListMountPath,
                          String cloudName, String cloudPath, String mountPoint, List<Binding> bindings,
                          Capacity capacity, List<Check> checks, String createdAt, String updatedAt) {}
    @Schema(description = "账号列表、注册版本与四类默认")
    public record AccountList(long registryVersion, boolean managed, List<Account> accounts, List<CategoryDefault> categoryDefaults) {}
    @Schema(description = "账号详情及当前注册版本")
    public record AccountDetail(long registryVersion, boolean managed, Account account) {}
    @Schema(description = "不含密钥的外部读回依据")
    public record Evidence(String stage, String resourceType, String resourceId, String observedAt, String fingerprint, String summary) {}
    @Schema(description = "已生效连接版本；缺少模板版本时为 null")
    public record ConnectionVersion(String integration, long effectiveVersion, Long templateVersion) {}
    @Schema(description = "已有主号接管计划；证据不足时不可确认")
    public record AdoptionPlan(String id, long planVersion, long registryVersion, String targetId, String displayName,
                               boolean canConfirm, Account proposedAccount, List<ConnectionVersion> connectionVersions,
                               List<Evidence> evidence, List<Issue> issues, String createdAt) {}
    @Schema(description = "所选类别的计划路径与媒体库关联")
    public record PlannedBinding(String category, String rootPath, String animePathTemplate, String mediaSourceRoot,
                                 String strmRootPath, String embyLibraryId, List<LibraryPath> embyLibraries) {
        public PlannedBinding(String category, String rootPath, String animePathTemplate, String mediaSourceRoot,
                              String strmRootPath, String embyLibraryId) {
            this(category, rootPath, animePathTemplate, mediaSourceRoot, strmRootPath, embyLibraryId,
                    embyLibraryId == null ? List.of() : List.of(new LibraryPath(embyLibraryId, embyLibraryId, "", strmRootPath)));
        }
    }
    @Schema(description = "预计创建、复用、追加或核对的资源")
    public record PlannedResource(String stage, String action, String resourceType, String resourceId, String path, String summary) {}
    @Schema(description = "持久接入计划；缺少前提时路径可为 null 且禁止执行")
    public record OnboardingPlan(String id, long planVersion, long registryVersion, String targetId, String displayName,
                                 List<String> categories, String ingestPrefix, String cloudDrivePrefix, String mediaSourcePrefix,
                                 List<PlannedBinding> bindings, List<ConnectionVersion> connectionVersions,
                                 List<DefaultChange> defaultChanges, List<PlannedResource> resources,
                                 boolean authorizationConfigured, boolean canExecute, List<Issue> issues,
                                 String createdAt, String updatedAt) {}
    @Schema(description = "安全外部资源引用")
    public record ResourceRef(String resourceType, String resourceId, String path) {}
    @Schema(description = "持久阶段状态及安全读回依据")
    public record Step(String stage, String category, String status, int attempt, List<ResourceRef> resourceRefs,
                       List<Evidence> evidence, String message, Issue issue, String startedAt, String finishedAt) {}
    @Schema(description = "执行进度；配置验证与播放验证分开")
    public record Execution(String id, long executionVersion, String planId, long planVersion, String targetId,
                            String displayName, List<String> categories, String status, String currentStage, List<Step> steps,
                            List<DefaultChange> defaultChanges, boolean defaultsCommitted, boolean configurationVerified,
                            boolean playbackVerified, Issue issue, boolean canContinue, String createdAt, String updatedAt,
                            String finishedAt) {}
    @Schema(description = "保留的执行记录列表")
    public record ExecutionList(List<Execution> items, int total) {}

    @Schema(description = "主号接管预览请求")
    public record AdoptionRequest(@NotNull @Min(0) Long expectedRegistryVersion, String displayName) {
        @JsonAnySetter public void rejectUnknown(String key, Object value) { throw invalid(); }
    }
    @Schema(description = "带版本的接管确认请求")
    public record AdoptionConfirmRequest(@NotNull @Min(0) Long expectedRegistryVersion,
                                         @NotNull @Min(0) Long expectedPlanVersion) {
        @JsonAnySetter public void rejectUnknown(String key, Object value) { throw invalid(); }
    }
    @Schema(description = "PikPak 临时授权；仅服务端加密保存，响应和日志不回显")
    public record PikPakAuthorization(@NotBlank String platform, String username, String password, String refreshToken,
                                      String captchaToken, String deviceId) {
        @JsonAnySetter public void rejectUnknown(String key, Object value) { throw invalid(); }
        @Override public String toString() { return "PikPakAuthorization[protected]"; }
    }
    @Schema(description = "当前类别的高级路径覆盖")
    public record CategoryPathOverride(String rootPath, String animePathTemplate, String strmRootPath) {
        @JsonAnySetter public void rejectUnknown(String key, Object value) { throw invalid(); }
    }
    @Schema(description = "允许范围内的高级路径；省略表示使用模板")
    public record PathOverrides(String ingestPrefix, String cloudDrivePrefix, String mediaSourcePrefix,
                                 Map<String, CategoryPathOverride> categories) {
        @JsonAnySetter public void rejectUnknown(String key, Object value) { throw invalid(); }
    }
    @Schema(description = "新号计划输入；凭据只用于受保护的服务端流程")
    public record OnboardingPlanRequest(@NotNull @Min(0) Long expectedRegistryVersion, @NotBlank String displayName,
                                        @NotEmpty List<@NotBlank String> categories, @NotNull @Valid PikPakAuthorization authorization,
                                        @Valid PathOverrides pathOverrides) {
        @JsonAnySetter public void rejectUnknown(String key, Object value) { throw invalid(); }
        @Override public String toString() { return "OnboardingPlanRequest[protected]"; }
    }
    @Schema(description = "按计划和目标版本提交执行")
    public record ExecuteRequest(@NotNull @Min(0) Long expectedPlanVersion,
                                  @NotNull @Min(0) Long expectedRegistryVersion) {
        @JsonAnySetter public void rejectUnknown(String key, Object value) { throw invalid(); }
    }
    @Schema(description = "按执行版本请求继续")
    public record ContinueRequest(@NotNull @Min(0) Long expectedExecutionVersion) {
        @JsonAnySetter public void rejectUnknown(String key, Object value) { throw invalid(); }
    }
    @Schema(description = "指定类别的新默认目标")
    public record DefaultSelection(@NotBlank String category, @NotBlank String targetId) {
        @JsonAnySetter public void rejectUnknown(String key, Object value) { throw invalid(); }
    }
    @Schema(description = "仅更新选中类别并检查注册版本")
    public record UpdateDefaultsRequest(@NotNull @Min(0) Long expectedRegistryVersion, @NotEmpty List<@NotNull @Valid DefaultSelection> defaults) {
        @JsonAnySetter public void rejectUnknown(String key, Object value) { throw invalid(); }
    }
    @Schema(description = "按版本改变本站接收资格，不禁用外部存储")
    public record UpdateAcceptingRequest(@NotNull @Min(0) Long expectedRegistryVersion, @NotNull Boolean acceptingNewTasks) {
        @JsonAnySetter public void rejectUnknown(String key, Object value) { throw invalid(); }
    }
    private static BusinessException invalid() { return new BusinessException(ErrorCode.BAD_REQUEST, "账号请求包含未知字段"); }
}
