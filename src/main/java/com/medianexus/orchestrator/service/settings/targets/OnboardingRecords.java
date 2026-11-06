package com.medianexus.orchestrator.service.settings.targets;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.dto.accounts.AccountDtos.*;
import com.medianexus.orchestrator.mapper.SystemSettingMapper;
import com.medianexus.orchestrator.model.SystemSetting;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 每份计划/执行独立 setting key；短事务 claim 和版本 fencing 不依赖进程锁。 */
@Service
public class OnboardingRecords {
    private static final String PLAN = "target_plan_";
    private static final String ADOPT = "target_adoption_";
    private static final String EXECUTION = "target_execution_";
    private static final String OBSERVATION = "target_observation_";
    private final SystemSettingMapper mapper;
    private final ObjectMapper json;
    private final IngestTargetRegistry registry;
    private final TransactionTemplate transactions;

    public OnboardingRecords(SystemSettingMapper mapper, ObjectMapper json, IngestTargetRegistry registry,
                             PlatformTransactionManager manager) {
        this.mapper = mapper; this.json = json; this.registry = registry;
        this.transactions = new TransactionTemplate(manager);
    }

    public record PlanRecord(OnboardingPlan view, String protectedPayload, Map<String, String> fingerprints) {
        @Override public String toString() { return "PlanRecord[protected]"; }
    }
    public record AdoptionRecord(AdoptionPlan view, Map<String, String> fingerprints) {}
    public record ExecutionRecord(Execution view, String claimOwner, String claimUntil) {}
    public record Observation(long openListStorageId, String openListMountPath, String cloudName,
                              String cloudPath, String mountPoint, Map<String, String> libraryNames,
                              Capacity capacity, List<Check> checks, String createdAt, String updatedAt) {}

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60_000)
    public void cleanupExpired() { cleanupExpired(Instant.now()); }

    /** 每份计划使用短事务；有效租约持有的授权在执行结束或租约过期后再清理。 */
    public int cleanupExpired(Instant at) {
        int cleared = 0;
        for (SystemSetting row : mapper.selectList(new LambdaQueryWrapper<SystemSetting>()
                .likeRight(SystemSetting::getSettingKey, PLAN))) {
            PlanRecord candidate = decode(row.getSettingValue(), PlanRecord.class);
            if (candidate.protectedPayload() != null && expired(candidate.view(), at)
                    && expirePlan(candidate.view().id(), at).protectedPayload() == null) cleared++;
        }
        return cleared;
    }

    private boolean expired(OnboardingPlan plan, Instant at) {
        return !Instant.parse(plan.createdAt()).plusSeconds(86_400).isAfter(at);
    }

    private PlanRecord expirePlan(String planId, Instant at) {
        return transactions.execute(status -> {
            String executionKey = EXECUTION + id(planId);
            String rawExecution = mapper.selectSettingValueForUpdate(executionKey);
            ExecutionRecord execution = rawExecution == null ? null : decode(rawExecution, ExecutionRecord.class);
            String planKey = PLAN + id(planId);
            PlanRecord current = decode(mapper.selectSettingValueForUpdate(planKey), PlanRecord.class);
            if (current.protectedPayload() == null || !expired(current.view(), at)
                    || execution != null && execution.claimOwner() != null && execution.claimUntil() != null
                    && Instant.parse(execution.claimUntil()).isAfter(at)) return current;
            var issues = new java.util.ArrayList<>(current.view().issues());
            issues.add(new Issue("AUTHORIZATION_EXPIRED", null, null, "临时授权已到期清除，请重新提供授权并预览"));
            PlanRecord cleared = new PlanRecord(withoutAuthorization(current.view(), issues, at), null, current.fingerprints());
            mapper.upsertSetting(planKey, encode(cleared));
            if (execution != null) {
                Execution v = execution.view();
                Execution stopped = new Execution(v.id(), v.executionVersion() + 1, v.planId(), v.planVersion(), v.targetId(),
                        v.displayName(), v.categories(), v.status().equals("SUCCEEDED") ? "SUCCEEDED" : "NEEDS_ACTION", v.currentStage(), v.steps(), v.defaultChanges(),
                        v.defaultsCommitted(), v.configurationVerified(), v.playbackVerified(),
                        v.status().equals("SUCCEEDED") ? v.issue() : new Issue("AUTHORIZATION_EXPIRED", v.currentStage(), null,
                                "临时授权已到期清除，请重新提供授权并预览"), false,
                        v.createdAt(), at.toString(), v.finishedAt());
                mapper.upsertSetting(executionKey, encode(new ExecutionRecord(stopped, null, null)));
            }
            return cleared;
        });
    }

    private OnboardingPlan withoutAuthorization(OnboardingPlan p, List<Issue> issues, Instant at) {
        return new OnboardingPlan(p.id(), p.planVersion(), p.registryVersion(), p.targetId(), p.displayName(), p.categories(),
                p.ingestPrefix(), p.cloudDrivePrefix(), p.mediaSourcePrefix(), p.bindings(), p.connectionVersions(),
                p.defaultChanges(), p.resources(), false, false, issues, p.createdAt(), at.toString());
    }

    public void createPlan(PlanRecord plan) { create(PLAN + id(plan.view().id()), plan); }
    public PlanRecord plan(String id) {
        PlanRecord record = required(PLAN + id(id), PlanRecord.class);
        if (record.protectedPayload() != null && expired(record.view(), Instant.now())) record = expirePlan(id, Instant.now());
        return record.protectedPayload() == null && record.view().authorizationConfigured()
                ? new PlanRecord(withoutAuthorization(record.view(), record.view().issues(), Instant.now()), null, record.fingerprints()) : record;
    }
    public void createAdoption(AdoptionRecord plan) { create(ADOPT + id(plan.view().id()), plan); }
    public AdoptionRecord adoption(String id) { return required(ADOPT + id(id), AdoptionRecord.class); }
    /** 全部只读证据由调用者重新核对；这里将登记、初始默认与观测一次提交。 */
    public void completeAdoption(String planId, long expectedPlanVersion, long expectedRegistryVersion,
                                 IngestTargetState.Target verified, Observation observation, TargetRegistration registration) {
        transactions.executeWithoutResult(status -> {
            AdoptionRecord record = decode(mapper.selectSettingValueForUpdate(ADOPT + id(planId)), AdoptionRecord.class);
            AdoptionPlan plan = record.view();
            IngestTargetState current = registry.readState();
            var existing = current.targets().stream().filter(t -> t.id().equals(verified.id())).findFirst();
            if (existing.isPresent()) {
                // 接收资格与展示名可变；重复确认只能核对不可变归属，不能恢复初始默认。
                var old = existing.get();
                if (!old.ingestPrefix().equals(verified.ingestPrefix()) || !old.cloudDrivePrefix().equals(verified.cloudDrivePrefix())
                        || !old.mediaSourcePrefix().equals(verified.mediaSourcePrefix()) || !old.bindings().equals(verified.bindings())
                        || !plan.targetId().equals(verified.id())) { throw conflict("已登记主号身份不符，不允许重新绑定"); }
                return;
            }
            if (!plan.canConfirm() || !plan.issues().isEmpty() || plan.planVersion() != expectedPlanVersion
                    || plan.registryVersion() != expectedRegistryVersion || !plan.targetId().equals(verified.id())
                    || current.managed() || observation.openListStorageId() <= 0
                    || !within(observation.openListMountPath(), verified.ingestPrefix())
                    || verified.bindings().stream().anyMatch(binding -> !within(binding.rootPath(), observation.openListMountPath()))
                    || !observation.cloudPath().equals(verified.cloudDrivePrefix())
                    || !observation.mountPoint().equals(verified.mediaSourcePrefix())) {
                throw conflict("主号接管计划、资源身份或版本已变化");
            }
            Map<String,String> defaults = new java.util.LinkedHashMap<>();
            verified.bindings().forEach(binding -> defaults.put(binding.category(), verified.id()));
            lockConnectionVersions(plan.connectionVersions());
            registration.registerVerifiedTarget(expectedRegistryVersion, verified, defaults);
            mapper.upsertSetting(OBSERVATION + id(verified.id()), encode(observation));
        });
    }
    public Observation observation(String targetId) { return required(OBSERVATION + id(targetId), Observation.class); }
    public ExecutionRecord execution(String id) {
        ExecutionRecord record = required(EXECUTION + id(id), ExecutionRecord.class);
        Instant now = Instant.now();
        if (record.claimOwner() == null || Instant.parse(record.claimUntil()).isAfter(now)) { return record; }
        return transactions.execute(status -> {
            String key = EXECUTION + id(id);
            ExecutionRecord current = decode(mapper.selectSettingValueForUpdate(key), ExecutionRecord.class);
            if (current.claimOwner() == null || Instant.parse(current.claimUntil()).isAfter(now)) { return current; }
            Execution v = current.view();
            Execution interrupted = new Execution(v.id(), v.executionVersion() + 1, v.planId(), v.planVersion(), v.targetId(),
                    v.displayName(), v.categories(), "INTERRUPTED", v.currentStage(), v.steps(), v.defaultChanges(),
                    v.defaultsCommitted(), false, false, new Issue("CLAIM_EXPIRED", v.currentStage(), null,
                    "执行租约已过期，继续前须读回核对外部结果"), plan(v.planId()).view().canExecute(),
                    v.createdAt(), now.toString(), null);
            ExecutionRecord saved = new ExecutionRecord(interrupted, null, null);
            mapper.upsertSetting(key, encode(saved)); return saved;
        });
    }

    public List<Execution> executions(String planId) {
        if (planId != null) { id(planId); }
        return mapper.selectList(new LambdaQueryWrapper<SystemSetting>()
                        .likeRight(SystemSetting::getSettingKey, EXECUTION).orderByDesc(SystemSetting::getId)).stream()
                .map(row -> decode(row.getSettingValue(), ExecutionRecord.class).view())
                .filter(execution -> planId == null || execution.planId().equals(planId)).toList();
    }

    public ExecutionRecord begin(String planId, long expectedPlanVersion, long expectedRegistryVersion) {
        return transactions.execute(status -> {
            String key = PLAN + id(planId);
            PlanRecord plan = decode(mapper.selectSettingValueForUpdate(key), PlanRecord.class);
            String executionKey = EXECUTION + planId;
            String existing = mapper.selectSettingValue(executionKey);
            // 已完成的重复请求只返回历史结果，不重新应用后来被修改的默认。
            if (existing != null) { return decode(existing, ExecutionRecord.class); }
            OnboardingPlan view = plan.view();
            if (view.planVersion() != expectedPlanVersion || view.registryVersion() != expectedRegistryVersion
                    || registry.readState().version() != expectedRegistryVersion) { throw conflict("计划或目标版本已变化"); }
            if (!view.canExecute() || !view.issues().isEmpty()) { throw conflict("接入前提尚未满足，请先处理计划缺项"); }
            String now = Instant.now().toString();
            Execution execution = new Execution(planId, 0, planId, view.planVersion(), view.targetId(), view.displayName(),
                    view.categories(), "PENDING", null, List.of(), view.defaultChanges(), false, false, false,
                    null, false, now, now, null);
            ExecutionRecord record = new ExecutionRecord(execution, null, null);
            mapper.insertSettingIfAbsent(executionKey, encode(record));
            return decode(mapper.selectSettingValueForUpdate(executionKey), ExecutionRecord.class);
        });
    }

    /** 租约未过期不会接管；过期恢复必须由编排层先读回未决外部结果，不能盲目重放写操作。 */
    public ExecutionRecord claim(String executionId, long expectedVersion, String owner, Instant now) {
        return transactions.execute(status -> {
            String key = EXECUTION + id(executionId);
            ExecutionRecord current = decode(mapper.selectSettingValueForUpdate(key), ExecutionRecord.class);
            if (current.view().status().equals("SUCCEEDED")) { return current; }
            if (current.view().executionVersion() != expectedVersion) { throw conflict("执行版本已变化"); }
            if (current.claimOwner() != null && Instant.parse(current.claimUntil()).isAfter(now)) {
                throw conflict("执行正在运行，暂不能并发继续");
            }
            Execution v = current.view();
            Execution running = new Execution(v.id(), v.executionVersion() + 1, v.planId(), v.planVersion(), v.targetId(),
                    v.displayName(), v.categories(), "RUNNING", v.currentStage(), v.steps(), v.defaultChanges(),
                    v.defaultsCommitted(), v.configurationVerified(), false, null, false,
                    v.createdAt(), now.toString(), null);
            ExecutionRecord claimed = new ExecutionRecord(running, owner, now.plusSeconds(600).toString());
            mapper.upsertSetting(key, encode(claimed));
            return claimed;
        });
    }

    public ExecutionRecord checkpoint(String executionId, long expectedVersion, String owner, Execution next, Instant now) {
        return transactions.execute(status -> {
            String key = EXECUTION + id(executionId);
            ExecutionRecord current = decode(mapper.selectSettingValueForUpdate(key), ExecutionRecord.class);
            if (current.view().executionVersion() != expectedVersion || !Objects.equals(current.claimOwner(), owner)
                    || current.claimUntil() == null || !Instant.parse(current.claimUntil()).isAfter(now)) {
                throw conflict("执行所有权或版本已变化，请读回核对后继续");
            }
            if (!next.id().equals(current.view().id()) || !next.planId().equals(current.view().planId())
                    || !next.targetId().equals(current.view().targetId()) || next.executionVersion() != expectedVersion + 1
                    || next.playbackVerified()) { throw conflict("执行状态转换不符合当前计划"); }
            boolean running = next.status().equals("RUNNING");
            ExecutionRecord saved = new ExecutionRecord(next, running ? owner : null, running ? now.plusSeconds(600).toString() : null);
            mapper.upsertSetting(key, encode(saved));
            return saved;
        });
    }

    /** 心跳不改变业务版本；过期 owner 不得复活。网络操作始终在此事务外。 */
    public void renewLease(String executionId, String owner, Instant now) {
        transactions.executeWithoutResult(status -> {
            String key = EXECUTION + id(executionId);
            ExecutionRecord current = decode(mapper.selectSettingValueForUpdate(key), ExecutionRecord.class);
            if (!Objects.equals(current.claimOwner(), owner) || current.claimUntil() == null
                    || !Instant.parse(current.claimUntil()).isAfter(now)) { throw conflict("执行租约已失效"); }
            mapper.upsertSetting(key, encode(new ExecutionRecord(current.view(), owner, now.plusSeconds(600).toString())));
        });
    }

    public void saveExecutionPayload(String executionId, long executionVersion, String owner, String protectedPayload) {
        transactions.executeWithoutResult(status -> {
            ExecutionRecord execution = decode(mapper.selectSettingValueForUpdate(EXECUTION + id(executionId)), ExecutionRecord.class);
            if (execution.view().executionVersion() != executionVersion || !Objects.equals(execution.claimOwner(), owner)
                    || execution.claimUntil() == null || !Instant.parse(execution.claimUntil()).isAfter(Instant.now())) {
                throw conflict("执行所有权已变化，不能保存操作基线");
            }
            String key = PLAN + id(execution.view().planId());
            PlanRecord plan = decode(mapper.selectSettingValueForUpdate(key), PlanRecord.class);
            mapper.upsertSetting(key, encode(new PlanRecord(plan.view(), protectedPayload, plan.fingerprints())));
        });
    }

    /** 云盘名由服务端产生；仅将已读回身份填入原本未指定的槽位，禁止改写高级覆盖。 */
    public PlanRecord resolveCloudPath(String executionId, long executionVersion, String owner, String cloudPath) {
        return transactions.execute(status -> {
            ExecutionRecord execution = decode(mapper.selectSettingValueForUpdate(EXECUTION + id(executionId)), ExecutionRecord.class);
            if (execution.view().executionVersion() != executionVersion || !Objects.equals(execution.claimOwner(), owner)
                    || !Instant.parse(execution.claimUntil()).isAfter(Instant.now())) { throw conflict("执行所有权已变化"); }
            String key = PLAN + id(execution.view().planId());
            PlanRecord record = decode(mapper.selectSettingValueForUpdate(key), PlanRecord.class);
            OnboardingPlan p = record.view();
            if (p.cloudDrivePrefix() != null && !p.cloudDrivePrefix().equals(cloudPath)) { throw conflict("真实云盘根与计划不一致"); }
            OnboardingPlan resolved = new OnboardingPlan(p.id(), p.planVersion(), p.registryVersion(), p.targetId(), p.displayName(),
                    p.categories(), p.ingestPrefix(), IngestTargetRegistry.normalizePath(cloudPath), p.mediaSourcePrefix(), p.bindings(),
                    p.connectionVersions(), p.defaultChanges(), p.resources(), p.authorizationConfigured(), p.canExecute(), p.issues(),
                    p.createdAt(), Instant.now().toString());
            PlanRecord saved = new PlanRecord(resolved, record.protectedPayload(), record.fingerprints());
            mapper.upsertSetting(key, encode(saved)); return saved;
        });
    }

    public void refreshObservation(String targetId, Observation observation) {
        String key = OBSERVATION + id(targetId);
        transactions.executeWithoutResult(status -> {
            Observation old = decode(mapper.selectSettingValueForUpdate(key), Observation.class);
            if (old.openListStorageId() != observation.openListStorageId()
                    || !old.openListMountPath().equals(observation.openListMountPath())
                    || !old.cloudPath().equals(observation.cloudPath()) || !old.mountPoint().equals(observation.mountPoint())) {
                throw conflict("观测不能改写已登记资源身份");
            }
            mapper.upsertSetting(key, encode(observation));
        });
    }

    /** 调用者完成真实读回后，登记、默认变化、成功记录和临时密钥清除共用一个事务。 */
    Execution commitVerifiedExecution(String executionId, long expectedVersion, String owner,
                                      IngestTargetState.Target verified, Observation observation,
                                      TargetRegistration registration, Instant now) {
        return transactions.execute(status -> {
            String key = EXECUTION + id(executionId);
            ExecutionRecord record = decode(mapper.selectSettingValueForUpdate(key), ExecutionRecord.class);
            Execution v = record.view();
            if (v.status().equals("SUCCEEDED")) { return v; }
            if (v.executionVersion() != expectedVersion || !Objects.equals(record.claimOwner(), owner)
                    || record.claimUntil() == null || !Instant.parse(record.claimUntil()).isAfter(now)) {
                throw conflict("执行所有权已变化，不能提交默认");
            }
            PlanRecord storedPlan = plan(v.planId()); OnboardingPlan plan = storedPlan.view();
            if (!plan.canExecute() || !plan.issues().isEmpty() || !verified.id().equals(plan.targetId())
                    || !verified.ingestPrefix().equals(plan.ingestPrefix())
                    || !verified.cloudDrivePrefix().equals(plan.cloudDrivePrefix())
                    || !verified.mediaSourcePrefix().equals(plan.mediaSourcePrefix())
                    || verified.bindings().size() != plan.bindings().size()) { throw conflict("登记资源与原计划不一致"); }
            for (PlannedBinding binding : plan.bindings()) {
                boolean matches = verified.bindings().stream().anyMatch(b -> b.category().equals(binding.category())
                        && b.rootPath().equals(binding.rootPath()) && b.strmRootPath().equals(binding.strmRootPath())
                        && Objects.equals(b.animePathTemplate(), binding.animePathTemplate())
                        && b.embyLibraryId().equals(binding.embyLibraryId())
                        && b.embyLibraries().equals(binding.embyLibraries().stream()
                                .map(path -> new IngestTargetState.LibraryBinding(path.libraryId(),path.relativePath())).toList())
                        && binding.embyLibraries().stream().allMatch(path -> path.path().equals(binding.strmRootPath()
                                + (path.relativePath().isEmpty() ? "" : "/" + path.relativePath()))));
                if (!matches) { throw conflict("类别绑定与不可变计划不一致"); }
            }
            for (String stage : List.of("OPENLIST_STORAGE", "MEDIA_DIRECTORIES", "CD2_CLOUD", "CD2_MOUNT",
                    "MEDIA_READABLE", "AS_BINDINGS", "EMBY_PATHS", "READBACK")) {
                if (v.steps().stream().noneMatch(step -> step.stage().equals(stage) && step.status().equals("SUCCEEDED"))) {
                    throw conflict("必要阶段尚未全部读回，不能登记或切默认");
                }
            }
            for (String category : plan.categories()) {
                for (String stage : List.of("AS_BINDINGS", "EMBY_PATHS")) {
                    if (v.steps().stream().noneMatch(step -> step.stage().equals(stage)
                            && category.equals(step.category()) && step.status().equals("SUCCEEDED"))) {
                        throw conflict("所选类别仍缺少真实外部绑定核对");
                    }
                }
            }
            if (!verified.acceptingNewTasks() || !verified.displayName().equals(plan.displayName())
                    || observation.openListStorageId() <= 0 || !observation.openListMountPath().equals(verified.ingestPrefix())
                    || !observation.cloudPath().equals(verified.cloudDrivePrefix())
                    || !observation.mountPoint().equals(verified.mediaSourcePrefix())) {
                throw conflict("最终资源身份与已核实目标不一致");
            }
            if (v.steps().stream().anyMatch(step -> !step.status().equals("SUCCEEDED"))) { throw conflict("仍有未完成阶段"); }
            Map<String, String> defaults = new java.util.LinkedHashMap<>();
            plan.categories().forEach(category -> defaults.put(category, verified.id()));
            lockConnectionVersions(plan.connectionVersions());
            registration.registerVerifiedTarget(plan.registryVersion(), verified, defaults);
            mapper.upsertSetting(OBSERVATION + id(verified.id()), encode(observation));
            Execution done = new Execution(v.id(), v.executionVersion() + 1, v.planId(), v.planVersion(), v.targetId(),
                    v.displayName(), v.categories(), "SUCCEEDED", "LOCAL_COMMIT", v.steps(), v.defaultChanges(), true,
                    true, false, null, false, v.createdAt(), now.toString(), now.toString());
            mapper.upsertSetting(key, encode(new ExecutionRecord(done, null, null)));
            mapper.upsertSetting(PLAN + id(plan.id()), encode(new PlanRecord(withoutAuthorization(plan, plan.issues(), now), null, storedPlan.fingerprints())));
            return done;
        });
    }

    /** 与设置保存锁住同一行；锁持有到登记与默认切换提交，关闭锁外读回后的保存竞态。 */
    private void lockConnectionVersions(List<ConnectionVersion> versions) {
        Map<String, String> groups = Map.of("OPENLIST", "openlist", "CD2", "clouddrive2", "AUTOSYMLINK", "autosymlink", "AS", "autosymlink", "EMBY", "emby");
        if (versions.stream().anyMatch(version -> !groups.containsKey(version.integration()))) {
            throw conflict("计划包含未知软件版本，请重新预览");
        }
        for (ConnectionVersion version : versions.stream()
                .sorted(java.util.Comparator.comparing(v -> groups.get(v.integration()))).toList()) {
            String key = "managed_settings_" + groups.get(version.integration());
            mapper.insertSettingIfAbsent(key, "{\"version\":0,\"updatedAt\":null,\"overrides\":{},\"restored\":false}");
            try {
                var document = json.readTree(mapper.selectSettingValueForUpdate(key));
                if (!document.path("version").isIntegralNumber()
                        || document.path("version").asLong() != version.effectiveVersion()) {
                    throw conflict("连接配置版本已变化，请重新预览接入");
                }
            } catch (JsonProcessingException exception) { throw conflict("连接配置版本不可读取"); }
        }
    }

    private void create(String key, Object value) {
        transactions.executeWithoutResult(status -> {
            mapper.insertSettingIfAbsent(key, encode(value));
            if (!Objects.equals(mapper.selectSettingValueForUpdate(key), encode(value))) { throw conflict("记录身份已存在"); }
        });
    }
    private <T> T required(String key, Class<T> type) { return decode(mapper.selectSettingValue(key), type); }
    private <T> T decode(String raw, Class<T> type) {
        if (raw == null) { throw new BusinessException(ErrorCode.NOT_FOUND, "接入记录不存在", HttpStatus.NOT_FOUND); }
        try { return json.readValue(raw, type); }
        catch (JsonProcessingException exception) { throw conflict("接入持久化记录不可读取"); }
    }
    private String encode(Object value) {
        try {
            String result = json.writeValueAsString(value);
            if (result.getBytes(StandardCharsets.UTF_8).length > 65535) { throw conflict("接入单条记录超过当前存储容量，未保存"); }
            return result;
        } catch (JsonProcessingException exception) { throw conflict("接入持久化记录无效"); }
    }
    private String id(String id) {
        if (id == null || !id.matches("[a-zA-Z0-9-]{1,64}")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "记录身份格式无效", HttpStatus.BAD_REQUEST);
        }
        return id;
    }
    private static boolean within(String path, String root) {
        return path.equals(root) || path.startsWith(root.equals("/") ? "/" : root + "/");
    }
    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message, HttpStatus.CONFLICT);
    }
}
