package com.medianexus.orchestrator.service.settings.targets;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.config.AutoSymlinkProperties;
import com.medianexus.orchestrator.config.CloudDrive2Properties;
import com.medianexus.orchestrator.config.EmbyProperties;
import com.medianexus.orchestrator.config.OpenListProperties;
import com.medianexus.orchestrator.dto.accounts.AccountDtos.*;
import com.medianexus.orchestrator.integration.onboarding.AutoSymlinkAccountClient;
import com.medianexus.orchestrator.integration.onboarding.CloudDriveAccountClient;
import com.medianexus.orchestrator.integration.onboarding.EmbyAccountLibraryClient;
import com.medianexus.orchestrator.integration.onboarding.OpenListAccountClient;
import com.medianexus.orchestrator.service.AuthService;
import com.medianexus.orchestrator.service.SettingsCipher;
import com.medianexus.orchestrator.service.SettingsService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 管理端完整操作；对未获证明的云盘命名与 upstream 关系明确拒绝，不合成“接通”结果。 */
@Service
public class DownloadAccountService {
    private static final List<String> CATEGORIES = List.of("MOVIE", "TV", "ANIME", "ADULT");
    private static final Map<String, String> GROUPS = Map.of("OPENLIST", "openlist", "CD2", "clouddrive2",
            "AUTOSYMLINK", "autosymlink", "EMBY", "emby");
    private final AuthService auth;
    private final SettingsService settings;
    private final SettingsCipher cipher;
    private final IngestTargetRegistry registry;
    private final TargetRegistration registration;
    private final OnboardingRecords records;
    private final ObjectMapper json;
    private final OpenListAccountClient openList;
    private final CloudDriveAccountClient cd2;
    private final AutoSymlinkAccountClient as;
    private final EmbyAccountLibraryClient emby;
    private final OnboardingExecutor executor;

    public DownloadAccountService(AuthService auth, SettingsService settings, SettingsCipher cipher,
                                  IngestTargetRegistry registry, TargetRegistration registration, OnboardingRecords records,
                                  ObjectMapper json, OpenListAccountClient openList, CloudDriveAccountClient cd2,
                                  AutoSymlinkAccountClient as, EmbyAccountLibraryClient emby, OnboardingExecutor executor) {
        this.auth = auth; this.settings = settings; this.cipher = cipher; this.registry = registry;
        this.registration = registration; this.records = records; this.json = json;
        this.openList = openList; this.cd2 = cd2; this.as = as; this.emby = emby;
        this.executor = executor;
    }

    public AccountList list() {
        auth.requireAdminUser();
        IngestTargetState state = registry.readState();
        return new AccountList(state.version(), state.managed(), state.targets().stream().map(t -> account(state, t)).toList(),
                CATEGORIES.stream().map(category -> new CategoryDefault(category, state.categoryDefaults().get(category))).toList());
    }
    public AccountDetail detail(String id) {
        auth.requireAdminUser(); IngestTargetState state = registry.readState();
        return new AccountDetail(state.version(), state.managed(), account(state, target(state, id)));
    }
    public AccountList switchDefaults(UpdateDefaultsRequest request) {
        auth.requireAdminUser(); Map<String, String> defaults = new LinkedHashMap<>();
        for (DefaultSelection selection : request.defaults()) {
            category(selection.category());
            if (defaults.putIfAbsent(selection.category(), selection.targetId()) != null) { throw invalid("类别选择重复"); }
        }
        registration.switchDefaults(request.expectedRegistryVersion(), defaults);
        return list();
    }
    public AccountDetail setAccepting(String id, UpdateAcceptingRequest request) {
        auth.requireAdminUser();
        registration.setAcceptingNewTasks(request.expectedRegistryVersion(), id, request.acceptingNewTasks());
        return detail(id);
    }

    public AdoptionPlan previewAdoption(AdoptionRequest request) {
        auth.requireAdminUser();
        IngestTargetState state = expectedRegistry(request.expectedRegistryVersion());
        if (state.managed()) throw conflict("主号已接管，不允许重新绑定旧路径");
        String id = UUID.randomUUID().toString();
        String displayName = request.displayName() == null || request.displayName().isBlank() ? "已有主号" : request.displayName().trim();
        List<ConnectionVersion> versions = new ArrayList<>();
        Map<String, String> fingerprints = new LinkedHashMap<>();
        List<Issue> issues = new ArrayList<>(); List<Evidence> evidence = new ArrayList<>();
        Account proposed = null;
        try {
            Connections connections = connections(versions, fingerprints);
            AdoptionInspection inspection = inspectExistingTarget(id, displayName, connections);
            proposed = present(new IngestTargetState(1, 0, false, List.of(), Map.of()), inspection.target(), inspection.observation());
            evidence.addAll(inspection.evidence());
            verifyFingerprints(fingerprints);
        } catch (BusinessException exception) {
            issues.add(new Issue("PRIMARY_READBACK_INCOMPLETE", "READBACK", null, exception.getMessage()));
        }
        AdoptionPlan plan = new AdoptionPlan(id, 0, state.version(), id, displayName, issues.isEmpty(), proposed,
                List.copyOf(versions), List.copyOf(evidence), List.copyOf(issues), Instant.now().toString());
        records.createAdoption(new OnboardingRecords.AdoptionRecord(plan, Map.copyOf(fingerprints)));
        return plan;
    }

    public AccountDetail confirmAdoption(String id, AdoptionConfirmRequest request) {
        auth.requireAdminUser();
        var record = records.adoption(id); AdoptionPlan plan = record.view();
        if (plan.planVersion() != request.expectedPlanVersion() || plan.registryVersion() != request.expectedRegistryVersion()
                || !plan.canConfirm()) throw conflict("接管计划已变化或证据不完整，请重新预览");
        IngestTargetState state = registry.readState();
        if (state.managed()) {
            if (state.targets().stream().anyMatch(target -> target.id().equals(plan.targetId()))) return detail(plan.targetId());
            throw conflict("主号已由另一计划接管，请重新读取");
        }
        expectedRegistry(request.expectedRegistryVersion()); verifyFingerprints(record.fingerprints());
        Connections connections = connections(new ArrayList<>(), new LinkedHashMap<>());
        AdoptionInspection inspection = inspectExistingTarget(plan.targetId(), plan.displayName(), connections);
        List<String> expected = plan.evidence().stream().map(Evidence::fingerprint).toList();
        if (!expected.equals(inspection.evidence().stream().map(Evidence::fingerprint).toList())) {
            throw conflict("外部资源或已有绑定在预览后变化，请重新预览");
        }
        verifyFingerprints(record.fingerprints());
        records.completeAdoption(id, request.expectedPlanVersion(), request.expectedRegistryVersion(), inspection.target(), inspection.observation(), registration);
        return detail(plan.targetId());
    }

    /** 显式部署映射是既有职责契约；读回只确认该映射与现有资源一致，不按显示名推断。 */
    private AdoptionInspection inspectExistingTarget(String id, String name, Connections c) {
        if (c.as().isDisabled()) throw conflict("AutoSymlink 已停用，无法核对主号任务");
        String ingest = IngestTargetRegistry.normalizePath(c.cd2().getIngestPathPrefix());
        String cloud = IngestTargetRegistry.normalizePath(c.cd2().getCloudDrivePathPrefix());
        String source = IngestTargetRegistry.normalizePath(c.cd2().getMediaSourcePathPrefix());
        List<IngestTargetState.Binding> bindings = new ArrayList<>();
        List<Evidence> evidence = new ArrayList<>(); Map<String, String> libraryNames = new LinkedHashMap<>();
        String now = Instant.now().toString();
        var cloudResources = cd2.readResources(c.cd2());
        var clouds = cloudResources.clouds().stream().filter(value -> cloud.equals(value.path()) && !value.locked()).toList();
        if (clouds.size() != 1) throw conflict("部署中的主号 CD2 云盘无法唯一读回或已锁定");
        var mounts = cloudResources.mounts().stream().filter(value -> source.equals(value.mountPoint())
                && cloud.equals(value.sourceDir()) && value.mounted() && !value.readOnly()).toList();
        if (mounts.size() != 1) throw conflict("部署中的主号系统挂载、来源或可读写状态不一致");
        var libraries = emby.readLibraries(c.emby());
        for (String category : CATEGORIES) {
            String root = legacyRoot(category, c.openList());
            if (root == null || root.isBlank()) continue;
            root = IngestTargetRegistry.normalizePath(root);
            String media = source + suffix(root, ingest);
            String uuid = syncTask(c.as(), category).getUuid();
            if (uuid == null || uuid.isBlank()) throw conflict("已启用类别缺少既有 AS 任务: " + category);
            ObjectNode configuration = as.readConfiguration(c.as(), uuid);
            if (!media.equals(configuration.path("media_dir").asText())
                    || !source.equals(configuration.path("clouddrive2_path").asText())) {
                throw conflict("主号 AS 来源与明确的部署映射不一致: " + category);
            }
            String strm = IngestTargetRegistry.normalizePath(configuration.path("symlink_dir").asText());
            String allowedStrm = IngestTargetRegistry.normalizePath(c.cd2().getStrmPathPrefix());
            requireChild(strm, allowedStrm);
            List<LibraryPath> linked = linkedLibraries(libraries, strm, strm);
            linked.forEach(value -> libraryNames.put(value.libraryId(), value.libraryName()));
            String anime = category.equals("ANIME") ? c.openList().getAnimePathTemplate() : null;
            bindings.add(new IngestTargetState.Binding(category, root, anime, uuid, as.manualRequest(configuration).toString(), strm,
                    linked.get(0).libraryId(), linked.stream().map(value -> new IngestTargetState.LibraryBinding(value.libraryId(), value.relativePath())).toList()));
            openList.verifyDirectory(c.openList(), root);
            cd2.verifyDirectory(c.cd2(), cloud + suffix(root, ingest));
            evidence.add(new Evidence("AS_BINDINGS", "AutoSymlinkTask", uuid, now,
                    fingerprint(as.manualRequest(configuration).toString() + ":" + strm + ":" + source
                            + ":" + configuration.path("cloud_type").asText() + ":" + configuration.path("strm_mode").asText()),
                    category + ": " + media + " → " + strm));
            for (LibraryPath path : linked) evidence.add(new Evidence("EMBY_PATHS", "EmbyLibrary", path.libraryId(), now,
                    fingerprint(path.libraryId() + ":" + path.relativePath()), path.libraryName() + ": " + path.path()));
        }
        if (bindings.isEmpty()) throw conflict("当前部署没有可接管的已启用类别");
        var storages = openList.readStorages(c.openList()).stream().filter(storage -> storage.healthy()
                && storage.driver().equals("PikPak") && bindings.stream().allMatch(binding -> within(binding.rootPath(), storage.mountPath()))).toList();
        if (storages.size() != 1) throw conflict("主号类别路径不能唯一对应健康的 PikPak 存储");
        var storage = storages.get(0);
        var target = new IngestTargetState.Target(id, name, ingest, cloud, source, true, bindings);
        registry.validateState(new IngestTargetState(1, 1, true, List.of(target), bindings.stream()
                .collect(java.util.stream.Collectors.toMap(IngestTargetState.Binding::category, ignored -> id))));
        evidence.add(new Evidence("OPENLIST_STORAGE", "OpenListStorage", Long.toString(storage.id()), now,
                fingerprint(storage.id() + ":" + storage.mountPath()), storage.mountPath()));
        evidence.add(new Evidence("CD2_MOUNT", "CloudDriveMount", source, now,
                fingerprint(clouds.get(0).name() + ":" + cloud + ":" + source + ":" + mounts.get(0).toString()), cloud + " → " + source));
        var observation = new OnboardingRecords.Observation(storage.id(), storage.mountPath(), clouds.get(0).name(), cloud, source,
                Map.copyOf(libraryNames), capacity(storage, now), List.of(new Check("READBACK", "HEALTHY", now,
                "已有部署映射与存储、云盘、挂载、AS及Emby目录一致；未创建或迁移资源")), now, now);
        return new AdoptionInspection(target, observation, List.copyOf(evidence));
    }

    private String legacyRoot(String category, OpenListProperties properties) {
        if (!category.equals("ANIME")) return switch (category) {
            case "MOVIE" -> properties.getMovieRootPath(); case "TV" -> properties.getTvRootPath(); default -> properties.getAdultRootPath();
        };
        String template = properties.getAnimePathTemplate();
        if (template == null || template.isBlank()) return null;
        int placeholder = template.indexOf('{');
        if (placeholder < 0) throw conflict("动漫模板没有可核实的标题/季数占位符");
        int slash = template.lastIndexOf('/', placeholder);
        if (slash <= 0) throw conflict("动漫模板缺少独立媒体根目录");
        return template.substring(0, slash);
    }

    private List<LibraryPath> linkedLibraries(List<EmbyAccountLibraryClient.LibraryView> libraries, String oldStrm, String newStrm) {
        List<LibraryPath> result = new ArrayList<>(); Set<String> locations = new java.util.HashSet<>();
        for (var library : libraries) for (String location : library.locations()) {
            if (!within(location, oldStrm)) continue;
            String relative = location.substring(oldStrm.length());
            if (relative.startsWith("/")) relative = relative.substring(1);
            if (!locations.add(relative)) throw conflict("同一 STRM 目录被多个库重复关联，请先核对");
            result.add(new LibraryPath(library.id(), library.name(), relative, newStrm + (relative.isEmpty() ? "" : "/" + relative)));
        }
        if (result.isEmpty()) throw conflict("AS 输出未关联到可核实的既有 Emby 库");
        result.sort(java.util.Comparator.comparing(LibraryPath::libraryId).thenComparing(LibraryPath::relativePath));
        return List.copyOf(result);
    }

    private boolean within(String path, String root) { return path != null && root != null && (path.equals(root) || path.startsWith(root + "/")); }
    private Capacity capacity(OpenListAccountClient.StorageView storage, String now) {
        Long total = storage.totalBytes(), used = storage.usedBytes(), free = storage.freeBytes();
        boolean valid = total != null && used != null && free != null && total > 0 && used >= 0 && used <= total
                && free == total - used && total <= 9007199254740991L;
        return new Capacity(valid ? total : null, valid ? used : null, valid ? free : null, valid ? now : null,
                valid ? "AVAILABLE" : "UNAVAILABLE", valid ? null : "驱动尚未返回可核实容量");
    }
    private record AdoptionInspection(IngestTargetState.Target target, OnboardingRecords.Observation observation, List<Evidence> evidence) {}

    public OnboardingPlan previewOnboarding(OnboardingPlanRequest request) {
        auth.requireAdminUser(); IngestTargetState state = expectedRegistry(request.expectedRegistryVersion());
        validateAuthorization(request.authorization());
        if (request.displayName() == null || request.displayName().isBlank() || request.categories() == null || request.categories().isEmpty()) {
            throw invalid("显示名称和接入类别不能为空");
        }
        Set<String> unique = new java.util.LinkedHashSet<>();
        request.categories().forEach(value -> { category(value); if (!unique.add(value)) { throw invalid("接入类别重复"); } });
        if (request.pathOverrides() != null && request.pathOverrides().categories() != null) {
            request.pathOverrides().categories().keySet().forEach(value -> {
                category(value); if (!unique.contains(value)) { throw invalid("路径覆盖只能指定本次所选类别"); }
            });
        }
        String id = UUID.randomUUID().toString(); String now = Instant.now().toString();
        List<Issue> issues = new ArrayList<>(); List<ConnectionVersion> versions = new ArrayList<>();
        Map<String, String> fingerprints = new LinkedHashMap<>();
        List<PlannedBinding> bindings = new ArrayList<>(); List<PlannedResource> resources = new ArrayList<>();
        String ingest = null; String cloud = null; String source = null;
        ObjectNode protectedPayload = json.createObjectNode();
        protectedPayload.set("authorization", json.valueToTree(request.authorization()));
        if (!state.managed()) { issues.add(new Issue("MASTER_NOT_ADOPTED", null, null, "请先完成已有主号的真实资源接管")); }
        try {
            Connections c = connections(versions, fingerprints);
            ingest = override(request.pathOverrides() == null ? null : request.pathOverrides().ingestPrefix(), c.cd2().getIngestPathPrefix(), id);
            cloud = override(request.pathOverrides() == null ? null : request.pathOverrides().cloudDrivePrefix(), c.cd2().getCloudDrivePathPrefix(), id);
            if (cloud.substring(1).contains("/")) {
                throw invalid("CD2 云盘映射必须是直接根目录；媒体子目录请在类别路径中设置");
            }
            if (c.as().isDisabled()) { issues.add(new Issue("AS_DISABLED", "AS_BINDINGS", null, "AutoSymlink 已停用，请启用并生效后重新预览")); }
            if (!org.springframework.util.StringUtils.hasText(c.openList().getDavUsername())
                    || !org.springframework.util.StringUtils.hasText(c.openList().getDavPassword())) {
                issues.add(new Issue("DAV_AUTHENTICATION_REQUIRED", "CD2_CLOUD", null, "请配置独立 DAV 用户名和密码；不使用管理 Token 代替"));
            }
            source = override(request.pathOverrides() == null ? null : request.pathOverrides().mediaSourcePrefix(), c.cd2().getMediaSourcePathPrefix(), id);
            validateDisjoint(state, ingest, cloud, source);
            for (var storage : openList.readStorages(c.openList())) {
                if (overlaps(ingest, storage.mountPath())) throw invalid("新号路径与现有存储重叠，未覆盖任何资源");
            }
            var libraries = emby.readLibraries(c.emby());
            var cloudResources = cd2.readResources(c.cd2());
            for (var existing : cloudResources.clouds()) if (existing.path() != null && overlaps(cloud, existing.path())) throw invalid("计划云盘路径已被占用");
            for (var existing : cloudResources.mounts()) if (overlaps(source, existing.mountPoint())) throw invalid("计划系统挂载路径已被占用");
            if (!cloudResources.canAddCloud()) { issues.add(new Issue("CD2_CLOUD_LICENSE_DENIED", "CD2_CLOUD", null, "当前许可不允许新增云盘")); }
            if (!cloudResources.canAddMount()) { issues.add(new Issue("CD2_MOUNT_LICENSE_DENIED", "CD2_MOUNT", null, "当前许可不允许新增系统挂载")); }
            protectedPayload.set("cloudsBefore", json.valueToTree(cloudResources.clouds()));
            if (state.managed()) {
                var templateTarget = target(state, state.categoryDefaults().get(unique.iterator().next()));
                var storageTemplate = openList.readStorageTemplate(c.openList(), records.observation(templateTarget.id()).openListStorageId());
                ObjectNode addition = json.createObjectNode().put("platform", request.authorization().platform())
                        .put("root_folder_id", "").put("disable_media_link", true);
                String[] keys = {"username", "password", "refresh_token", "captcha_token", "device_id"};
                String[] values = {request.authorization().username(), request.authorization().password(), request.authorization().refreshToken(),
                        request.authorization().captchaToken(), request.authorization().deviceId()};
                for (int i = 0; i < keys.length; i++) { addition.put(keys[i], values[i] == null ? "" : values[i]); }
                storageTemplate.put("mount_path", ingest).put("addition", addition.toString()).put("disabled", false);
                protectedPayload.set("openListStorage", storageTemplate);
                var mount = cd2.readMountTemplate(c.cd2(), templateTarget.mediaSourcePrefix());
                if (mount.getReadOnly()) { issues.add(new Issue("MOUNT_TEMPLATE_READ_ONLY", "CD2_MOUNT", null, "现有挂载模板为只读，不能用于接入")); }
                protectedPayload.putObject("mountTemplate").put("localMount", mount.getLocalMount()).put("readOnly", mount.getReadOnly())
                        .put("autoMount", mount.getAutoMount()).put("uid", mount.getUid()).put("gid", mount.getGid()).put("permissions", mount.getPermissions());
            }
            try {
                java.net.URI base = java.net.URI.create(c.openList().getBaseUrl());
                protectedPayload.put("davUrl", new java.net.URI(base.getScheme(), null, base.getHost(), base.getPort(),
                        base.getPath().replaceAll("/+$", "") + "/dav" + ingest, null, null).toASCIIString());
            } catch (java.net.URISyntaxException | IllegalArgumentException exception) { throw invalid("OpenList DAV 地址不能由当前有效连接构造"); }
            resources.add(new PlannedResource("OPENLIST_STORAGE", "CREATE", "OpenListStorage", null, ingest, "PikPak 存储及独立授权"));
            resources.add(new PlannedResource("MEDIA_DIRECTORIES", "CREATE", "AccountIdentityDirectory", null,
                    ingest + "/MediaNexus-account-" + id, "空身份目录：用于证明 CD2 新云盘确实指向本次账号，保留供续接读回"));
            resources.add(new PlannedResource("CD2_CLOUD", "CREATE", "CloudDriveCloud", null, cloud, "仅对本次新建云盘重命名并读回计划中的准确路径"));
            resources.add(new PlannedResource("CD2_MOUNT", "CREATE", "CloudDriveMount", null, source, "继承实际挂载权限模板"));
            ObjectNode templates = protectedPayload.putObject("asConfigurations");
            for (String category : unique) {
                IngestTargetState.Binding registered = state.managed() ? registeredBinding(state, category) : null;
                IngestTargetState.Target templateTarget = state.managed() ? target(state, state.categoryDefaults().get(category)) : null;
                String originalIngest = templateTarget == null ? c.cd2().getIngestPathPrefix() : templateTarget.ingestPrefix();
                String originalSource = templateTarget == null ? c.cd2().getMediaSourcePathPrefix() : templateTarget.mediaSourcePrefix();
                String oldRoot = oldRoot(category, c.openList(), state);
                String suffix = suffix(oldRoot, originalIngest);
                String root = ingest + suffix;
                String strm = c.cd2().getStrmPathPrefix() + "/accounts/" + id + "/" + category;
                CategoryPathOverride paths = request.pathOverrides() == null || request.pathOverrides().categories() == null
                        ? null : request.pathOverrides().categories().get(category);
                if (paths != null && paths.rootPath() != null) { root = IngestTargetRegistry.normalizePath(paths.rootPath()); requireChild(root, ingest); }
                if (paths != null && paths.strmRootPath() != null) { strm = IngestTargetRegistry.normalizePath(paths.strmRootPath()); requireChild(strm, c.cd2().getStrmPathPrefix()); }
                String anime = category.equals("ANIME") ? (registered == null ? c.openList().getAnimePathTemplate() : registered.animePathTemplate())
                        .replace(oldRoot, root) : null;
                if (paths != null && paths.animePathTemplate() != null) {
                    if (!category.equals("ANIME")) { throw invalid("仅动漫类别支持动漫模板覆盖"); }
                    anime = IngestTargetRegistry.normalizePath(paths.animePathTemplate()); requireChild(anime, root);
                }
                String uuid = registered != null ? registered.asUuid() : syncTask(c.as(), category).getUuid();
                ObjectNode form = as.readConfiguration(c.as(), uuid);
                String oldStrm = IngestTargetRegistry.normalizePath(form.path("symlink_dir").asText());
                for (var existingTarget : state.targets()) for (var existingBinding : existingTarget.bindings()) {
                    if (overlaps(strm, existingBinding.strmRootPath())) throw invalid("计划 STRM 输出与旧账号重叠");
                }
                for (var planned : bindings) if (overlaps(strm, planned.strmRootPath()) || overlaps(root, planned.rootPath())) throw invalid("所选类别的计划目录重叠");
                List<LibraryPath> linked = linkedLibraries(libraries, oldStrm, strm);
                String libraryId = linked.get(0).libraryId();
                String mediaRoot = source + suffix(root, ingest);
                String expectedMediaRoot = originalSource + suffix(oldRoot, originalIngest);
                if (!form.path("media_dir").asText().equals(expectedMediaRoot)
                        || !form.path("clouddrive2_path").asText().equals(originalSource)) {
                    issues.add(new Issue("AS_SOURCE_CONFLICT", "AS_BINDINGS", category, "真实 AS 来源/挂载根与当前目标不一致"));
                } else {
                    try {
                        templates.set(category, as.configurationForNewTarget(com.medianexus.orchestrator.service.AutoSymlinkScanParameters.apply(
                                json, c.as().getNewAccountScanParametersJson(), form), new AutoSymlinkAccountClient.TaskPaths(
                                "medianexus-" + id + "-" + category, mediaRoot, strm, source)));
                    } catch (BusinessException exception) {
                        issues.add(new Issue("AS_TEMPLATE_UNSUPPORTED", "AS_BINDINGS", category, "模板包含未核实的 STRM 模式或需另行准备的掉盘探针"));
                    }
                }
                bindings.add(new PlannedBinding(category, root, anime, mediaRoot, strm, libraryId, linked));
            }
            protectedPayload.set("openListConnection", json.valueToTree(c.openList()));
            protectedPayload.set("cloudDriveConnection", json.valueToTree(c.cd2()));
            protectedPayload.set("asConnection", json.valueToTree(c.as()));
            protectedPayload.set("embyConnection", json.valueToTree(c.emby()));
            verifyFingerprints(fingerprints);
        } catch (BusinessException exception) {
            if (exception.getCode() == 400) { throw exception; }
            issues.add(new Issue("EFFECTIVE_PREREQUISITES_UNAVAILABLE", null, null, "有效连接、模板或类别绑定尚不可读取，请先补齐核心设置"));
        }
        for (PlannedBinding binding : bindings) {
            resources.add(new PlannedResource("MEDIA_DIRECTORIES", "CREATE", "MediaDirectory", null, binding.rootPath(), binding.category()));
            resources.add(new PlannedResource("AS_BINDINGS", "CREATE", "StrmOutputDirectory", null, binding.strmRootPath(),
                    "准备本账号的空 STRM 输出目录；不创建或改写媒体文件"));
            resources.add(new PlannedResource("AS_BINDINGS", "CREATE", "AutoSymlinkTask", null, binding.strmRootPath(), binding.category()));
            for (LibraryPath library : binding.embyLibraries()) resources.add(new PlannedResource("EMBY_PATHS", "APPEND", "EmbyLibrary", library.libraryId(), library.path(), binding.category()));
        }
        List<DefaultChange> defaults = unique.stream().map(category -> new DefaultChange(category, state.categoryDefaults().get(category), id)).toList();
        OnboardingPlan plan = new OnboardingPlan(id, 0, state.version(), id, request.displayName(), List.copyOf(unique), ingest, cloud, source,
                List.copyOf(bindings), List.copyOf(versions), defaults, List.copyOf(resources), true, issues.isEmpty(), List.copyOf(issues), now, now);
        records.createPlan(new OnboardingRecords.PlanRecord(plan, cipher.encrypt("onboarding-plan/" + id, protectedPayload.toString()), Map.copyOf(fingerprints)));
        return plan;
    }
    public OnboardingPlan plan(String id) { auth.requireAdminUser(); return records.plan(id).view(); }
    public Execution execute(String id, ExecuteRequest request) {
        auth.requireAdminUser();
        var execution = records.begin(id, request.expectedPlanVersion(), request.expectedRegistryVersion());
        if (execution.view().status().equals("SUCCEEDED") || execution.view().status().equals("RUNNING")) return execution.view();
        verifyFingerprints(records.plan(id).fingerprints());
        return executor.submit(execution);
    }
    public ExecutionList executions(String planId) { auth.requireAdminUser(); var items = records.executions(planId); return new ExecutionList(items, items.size()); }
    public Execution execution(String id) { auth.requireAdminUser(); return records.execution(id).view(); }
    public Execution resume(String id, ContinueRequest request) {
        auth.requireAdminUser(); var current = records.execution(id);
        if (current.view().status().equals("SUCCEEDED")) { return current.view(); }
        if (current.view().executionVersion() != request.expectedExecutionVersion()) { throw conflict("执行版本已变化"); }
        var plan = records.plan(current.view().planId()); verifyFingerprints(plan.fingerprints());
        return executor.submit(current);
    }

    public AccountDetail check(String id) {
        auth.requireAdminUser(); IngestTargetState state = registry.readState(); var target = target(state, id);
        List<ConnectionVersion> versions = new ArrayList<>(); Map<String, String> fingerprints = new LinkedHashMap<>();
        Connections c = connections(versions, fingerprints);
        var observation = records.observation(id);
        var storage = openList.readStorage(c.openList(), observation.openListStorageId());
        if (!storage.healthy()) { throw conflict("已登记 OpenList 存储当前未就绪"); }
        var resources = cd2.readResources(c.cd2());
        if (resources.mounts().stream().noneMatch(mount -> mount.mounted() && mount.mountPoint().equals(observation.mountPoint())
                && mount.sourceDir().equals(target.cloudDrivePrefix()))) { throw conflict("已登记系统挂载当前无法确认"); }
        for (var binding : target.bindings()) {
            var config = as.readConfiguration(c.as(), binding.asUuid());
            if (!config.path("symlink_dir").asText().equals(binding.strmRootPath())
                    || !config.path("media_dir").asText().equals(target.mediaSourcePrefix() + suffix(binding.rootPath(), target.ingestPrefix()))) {
                throw conflict("AS 来源或输出与登记绑定不一致");
            }
            cd2.verifyDirectory(c.cd2(), target.cloudDrivePrefix() + suffix(binding.rootPath(), target.ingestPrefix()));
            var libraries = emby.readLibraries(c.emby());
            for (var linked : binding.embyLibraries()) {
                String path = binding.strmRootPath() + (linked.relativePath().isEmpty() ? "" : "/" + linked.relativePath());
                if (libraries.stream().noneMatch(library -> library.id().equals(linked.libraryId()) && library.locations().contains(path))) {
                    throw conflict("Emby 库目录与登记绑定不一致");
                }
            }
        }
        verifyFingerprints(fingerprints);
        String now = Instant.now().toString();
        Long total = storage.totalBytes(); Long used = storage.usedBytes(); Long free = storage.freeBytes();
        boolean validCapacity = total != null && used != null && free != null && total >= 0 && used >= 0 && free >= 0
                && total <= 9007199254740991L && used <= total && free == total - used;
        Capacity capacity = new Capacity(validCapacity ? total : null, validCapacity ? used : null, validCapacity ? free : null,
                validCapacity ? now : null, validCapacity ? "AVAILABLE" : "UNAVAILABLE", validCapacity ? null : "当前驱动未返回有效容量");
        records.refreshObservation(id, new OnboardingRecords.Observation(observation.openListStorageId(), observation.openListMountPath(),
                observation.cloudName(), observation.cloudPath(), observation.mountPoint(), observation.libraryNames(), capacity,
                List.of(new Check("READBACK", "HEALTHY", now, "配置与云内目录读回通过；未验证容器间传播或真实播放")), observation.createdAt(), now));
        return detail(id);
    }

    private Account account(IngestTargetState state, IngestTargetState.Target target) {
        return present(state, target, records.observation(target.id()));
    }
    private Account present(IngestTargetState state, IngestTargetState.Target target, OnboardingRecords.Observation o) {
        List<Binding> bindings = target.bindings().stream().map(b -> new Binding(b.category(), b.rootPath(), b.animePathTemplate(),
                b.asUuid(), true, target.mediaSourcePrefix() + suffix(b.rootPath(), target.ingestPrefix()), b.strmRootPath(),
                b.embyLibraryId(), o.libraryNames().getOrDefault(b.embyLibraryId(), b.embyLibraryId()),
                b.embyLibraries().stream().map(library -> new LibraryPath(library.libraryId(), o.libraryNames().getOrDefault(library.libraryId(), library.libraryId()),
                        library.relativePath(), b.strmRootPath() + (library.relativePath().isEmpty() ? "" : "/" + library.relativePath()))).toList())).toList();
        return new Account(target.id(), target.displayName(), "CONNECTED", target.acceptingNewTasks(), state.categoryDefaults().entrySet().stream()
                .filter(e -> e.getValue().equals(target.id())).map(Map.Entry::getKey).sorted().toList(), target.ingestPrefix(), target.cloudDrivePrefix(),
                target.mediaSourcePrefix(), o.openListStorageId(), o.openListMountPath(), o.cloudName(), o.cloudPath(), o.mountPoint(), bindings,
                o.capacity(), o.checks(), o.createdAt(), o.updatedAt());
    }
    private Connections connections(List<ConnectionVersion> versions, Map<String, String> fingerprints) {
        for (var entry : GROUPS.entrySet()) {
            fingerprints.put(entry.getValue(), settings.effectiveFingerprint(entry.getValue()));
            versions.add(new ConnectionVersion(entry.getKey(), settings.get(entry.getValue()).version(), null));
        }
        Connections c = new Connections(settings.effectiveSnapshot("openlist", OpenListProperties.class),
                settings.effectiveSnapshot("clouddrive2", CloudDrive2Properties.class),
                settings.effectiveSnapshot("autosymlink", AutoSymlinkProperties.class), settings.effectiveSnapshot("emby", EmbyProperties.class));
        verifyFingerprints(fingerprints); return c;
    }
    private void verifyFingerprints(Map<String, String> fingerprints) { fingerprints.forEach(settings::requireEffectiveFingerprint); }
    private IngestTargetState expectedRegistry(Long version) {
        if (version == null || version < 0) { throw invalid("必须提供非负目标版本"); }
        IngestTargetState state = registry.readState(); if (state.version() != version) { throw conflict("目标版本已变化，请重新读取"); } return state;
    }
    private IngestTargetState.Target target(IngestTargetState state, String id) {
        return state.targets().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "下载账号不存在", HttpStatus.NOT_FOUND));
    }
    private String oldRoot(String category, OpenListProperties p, IngestTargetState state) {
        if (state.managed()) { return registeredBinding(state, category).rootPath(); }
        return switch (category) {
            case "MOVIE" -> p.getMovieRootPath(); case "TV" -> p.getTvRootPath(); case "ADULT" -> p.getAdultRootPath();
            default -> throw conflict("动漫根目录需要先从真实主号绑定确认");
        };
    }
    private IngestTargetState.Binding registeredBinding(IngestTargetState state, String category) {
        return target(state, state.categoryDefaults().get(category)).bindings().stream().filter(binding -> binding.category().equals(category))
                .findFirst().orElseThrow(() -> conflict("当前类别默认缺少稳定绑定"));
    }
    private AutoSymlinkProperties.SyncTask syncTask(AutoSymlinkProperties p, String category) {
        return switch (category) { case "MOVIE" -> p.getMovie(); case "TV" -> p.getTv(); case "ANIME" -> p.getAnime(); default -> p.getAdult(); };
    }
    private void validateAuthorization(PikPakAuthorization authorization) {
        if (authorization == null || !Set.of("web", "android", "pc").contains(authorization.platform() == null ? "" : authorization.platform())) { throw invalid("PikPak 平台不受支持"); }
        if ((authorization.refreshToken() == null || authorization.refreshToken().isBlank())
                && (authorization.username() == null || authorization.username().isBlank() || authorization.password() == null || authorization.password().isBlank())) {
            throw invalid("请提供 refresh token 或完整账号密码");
        }
    }
    private String override(String value, String legacy, String id) {
        String original = IngestTargetRegistry.normalizePath(legacy);
        String candidate = value == null ? original + "-" + id : IngestTargetRegistry.normalizePath(value);
        String parent = original.substring(0, original.lastIndexOf('/')); requireChild(candidate, parent.isEmpty() ? "/" : parent); return candidate;
    }
    private void validateDisjoint(IngestTargetState state, String ingest, String cloud, String source) {
        for (var t : state.targets()) {
            if (overlaps(ingest, t.ingestPrefix()) || overlaps(cloud, t.cloudDrivePrefix()) || overlaps(source, t.mediaSourcePrefix())) { throw invalid("计划路径与已登记目标重叠"); }
        }
    }
    private boolean overlaps(String a, String b) { return a != null && (a.equals(b) || a.startsWith(b + "/") || b.startsWith(a + "/")); }
    private static void requireChild(String path, String parent) { if (path.equals(parent) || !path.startsWith(parent.equals("/") ? "/" : parent + "/")) { throw invalid("路径不在已允许的父目录下"); } }
    private static String suffix(String path, String prefix) { if (!path.equals(prefix) && !path.startsWith(prefix + "/")) { throw conflict("路径映射缺少真实前缀依据"); } return path.substring(prefix.length()); }
    private static void category(String category) { if (!CATEGORIES.contains(category == null ? "" : category)) { throw invalid("不支持的媒体类别"); } }
    private static String fingerprint(String text) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); } }
    private record Connections(OpenListProperties openList, CloudDrive2Properties cd2, AutoSymlinkProperties as, EmbyProperties emby) {}
    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.BAD_REQUEST, message, HttpStatus.BAD_REQUEST); }
    private static BusinessException conflict(String message) { return new BusinessException(ErrorCode.CONFLICT, message, HttpStatus.CONFLICT); }
}
