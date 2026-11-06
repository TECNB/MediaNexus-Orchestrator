package com.medianexus.orchestrator.service.settings.targets;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.mapper.SystemSettingMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 新任务从类别默认选目标，旧任务只按稳定路径定位；未接管才允许部署配置回退。
 * 路径和类别绑定登记后不可重绑，故任务已有 savePath 能证明原目标，无需重写历史任务。
 */
@Service
public class IngestTargetRegistry {

    public static final String SETTING_KEY = "ingest_target_registry_v1";
    private static final Set<String> CATEGORIES = Set.of("MOVIE", "TV", "ANIME", "ADULT");
    private static final Set<String> AS_BOOLEANS = Set.of(
            "symlink_creator", "metadata_copyer", "symlink_dir_checker", "symlink_checker", "metadata_checker");
    private static final Set<String> AS_STRINGS = Set.of(
            "task_name", "media_dir", "num_threads", "symlink_size", "symlink_ext", "metadata_ext");
    private final SystemSettingMapper mapper;
    private final ObjectMapper objectMapper;

    public IngestTargetRegistry(SystemSettingMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public String creationRoot(String category, String legacyRoot) {
        requireCategory(category);
        IngestTargetState state = readState();
        return state.managed() ? creationBinding(state, category).rootPath() : requiredLegacy(legacyRoot);
    }

    public String creationAnimeTemplate(String legacyTemplate) {
        IngestTargetState state = readState();
        return state.managed() ? creationBinding(state, "ANIME").animePathTemplate() : requiredLegacy(legacyTemplate);
    }

    public String rootForExistingPath(String category, String savePath, String legacyRoot) {
        requireCategory(category);
        String path = normalizePath(savePath);
        IngestTargetState state = readState();
        if (!state.managed()) {
            String root = normalizePath(legacyRoot);
            requireWithin(path, root);
            return root;
        }
        return bindingForPath(state, category, path).rootPath();
    }

    /** 字幕查找覆盖所有历史目标，停止接收新任务不影响候选媒体根。 */
    public List<String> rootsForExistingMedia(String category, String legacyRoot) {
        requireCategory(category);
        IngestTargetState state = readState();
        if (!state.managed()) {
            return StringUtils.hasText(legacyRoot) ? List.of(normalizePath(legacyRoot)) : List.of();
        }
        return state.targets().stream().sorted(Comparator.comparing(IngestTargetState.Target::id))
                .flatMap(target -> target.bindings().stream())
                .filter(binding -> binding.category().equals(category))
                .map(IngestTargetState.Binding::rootPath).toList();
    }

    public String toCloudDrivePath(String ingestPath, String legacyIngest, String legacyCloud) {
        String path = normalizePath(ingestPath);
        IngestTargetState state = readState();
        if (!state.managed()) {
            return mappedPath(path, normalizePath(legacyIngest), normalizePath(legacyCloud));
        }
        IngestTargetState.Target target = targetForPath(state, path, IngestTargetState.Target::ingestPrefix);
        return mappedPath(path, target.ingestPrefix(), target.cloudDrivePrefix());
    }

    /** CD2 祖先恢复只能刷新到实际所属目标根，不能套用当前默认或旧部署前缀。 */
    public String cloudDriveRootForIngestPath(String ingestPath, String legacyIngest, String legacyCloud) {
        String path = normalizePath(ingestPath);
        IngestTargetState state = readState();
        if (!state.managed()) {
            requireWithin(path, normalizePath(legacyIngest));
            return normalizePath(legacyCloud);
        }
        return targetForPath(state, path, IngestTargetState.Target::ingestPrefix).cloudDrivePrefix();
    }

    public String toCloudDriveMediaPath(String sourcePath, String legacySource, String legacyCloud) {
        String path = normalizePath(sourcePath);
        IngestTargetState state = readState();
        String source;
        String cloud;
        if (state.managed()) {
            IngestTargetState.Target target = targetForPath(state, path, IngestTargetState.Target::mediaSourcePrefix);
            source = target.mediaSourcePrefix();
            cloud = target.cloudDrivePrefix();
        } else {
            source = normalizePath(legacySource);
            cloud = normalizePath(legacyCloud);
        }
        if (path.equals(source)) {
            throw badRequest("媒体对象不能是目标挂载根目录");
        }
        return mappedPath(path, source, cloud);
    }

    public boolean ownsMediaSourcePath(String sourcePath, String legacySource) {
        IngestTargetState state = readState();
        if (!StringUtils.hasText(sourcePath)) {
            return false;
        }
        String path = normalizePath(sourcePath);
        if (!state.managed()) {
            if (!StringUtils.hasText(legacySource)) {
                return false;
            }
            String root = normalizePath(legacySource);
            return !path.equals(root) && within(path, root);
        }
        return state.targets().stream().anyMatch(target -> !path.equals(target.mediaSourcePrefix())
                && within(path, target.mediaSourcePrefix()));
    }

    public Optional<CategoryBinding> bindingForPath(String category, String savePath) {
        requireCategory(category);
        String path = normalizePath(savePath);
        IngestTargetState state = readState();
        if (!state.managed()) {
            return Optional.empty();
        }
        IngestTargetState.Target target = targetForPath(state, path, IngestTargetState.Target::ingestPrefix);
        IngestTargetState.Binding binding = bindingForPath(state, category, path);
        return Optional.of(new CategoryBinding(target.id(), target.displayName(), category, binding.rootPath(),
                binding.asUuid(), binding.asRequestBodyJson(), binding.strmRootPath(), binding.embyLibraryId()));
    }

    /** 服务端消费类型；管理 GET 必须单独使用展示白名单 DTO，禁止直接序列化本记录。 */
    public record CategoryBinding(String targetId, String targetName, String category, String rootPath,
                                  String asUuid, String asRequestBodyJson, String strmRootPath, String embyLibraryId) {}

    IngestTargetState readState() {
        return decodeState(mapper.selectSettingValue(SETTING_KEY));
    }

    IngestTargetState decodeState(String stored) {
        if (stored == null) {
            return new IngestTargetState(1, 0, false, List.of(), Map.of());
        }
        try {
            IngestTargetState state = objectMapper.readValue(stored, IngestTargetState.class);
            validateState(state);
            return state;
        } catch (JsonProcessingException | IllegalArgumentException | NullPointerException exception) {
            // 原始载荷可能包含受保护字段，不能加入异常 message 或 cause。
            throw conflict("目标注册数据无效，请重新核对已保存配置");
        }
    }

    void validateState(IngestTargetState state) {
        if (state == null || state.schemaVersion() != 1 || state.version() < 0
                || (!state.managed() && (!state.targets().isEmpty() || !state.categoryDefaults().isEmpty()))
                || (state.managed() && state.targets().isEmpty())) {
            throw conflict("目标注册版本或接管状态无效");
        }
        Set<String> ids = new HashSet<>();
        List<String> ingestRoots = new ArrayList<>();
        List<String> cloudRoots = new ArrayList<>();
        List<String> sourceRoots = new ArrayList<>();
        List<String> strmRoots = new ArrayList<>();
        for (IngestTargetState.Target target : state.targets()) {
            if (!StringUtils.hasText(target.id()) || !ids.add(target.id()) || !StringUtils.hasText(target.displayName())
                    || target.bindings().isEmpty()) {
                throw conflict("目标身份或类别绑定无效");
            }
            addDisjoint(ingestRoots, target.ingestPrefix());
            addDisjoint(cloudRoots, target.cloudDrivePrefix());
            addDisjoint(sourceRoots, target.mediaSourcePrefix());
            Set<String> categories = new HashSet<>();
            List<String> categoryRoots = new ArrayList<>();
            for (IngestTargetState.Binding binding : target.bindings()) {
                requireCategory(binding.category());
                if (!categories.add(binding.category()) || !StringUtils.hasText(binding.asUuid())
                        || !StringUtils.hasText(binding.embyLibraryId())) {
                    throw conflict("目标类别重复或外部绑定缺失");
                }
                addDisjoint(categoryRoots, binding.rootPath());
                requireWithin(binding.rootPath(), target.ingestPrefix());
                addDisjoint(strmRoots, binding.strmRootPath());
                if (binding.embyLibraries().isEmpty()
                        || !binding.embyLibraryId().equals(binding.embyLibraries().get(0).libraryId())) {
                    throw conflict("Emby 关联列表缺失或首库身份不一致");
                }
                Set<String> libraryPaths = new HashSet<>();
                for (IngestTargetState.LibraryBinding library : binding.embyLibraries()) {
                    String relative = library.relativePath();
                    if (!StringUtils.hasText(library.libraryId()) || relative == null || relative.startsWith("/")
                            || relative.indexOf('\\') >= 0 || (!relative.isEmpty() && !normalizePath("/" + relative).equals("/" + relative))
                            || !libraryPaths.add(library.libraryId() + "\n" + relative)) {
                        throw conflict("Emby 库相对目录不安全或关联重复");
                    }
                    requireWithin(binding.strmRootPath() + (relative.isEmpty() ? "" : "/" + relative), binding.strmRootPath());
                }
                if ("ANIME".equals(binding.category())) {
                    String template = normalizePath(binding.animePathTemplate());
                    requireWithin(template, binding.rootPath());
                } else if (binding.animePathTemplate() != null) {
                    throw conflict("非动漫类别不能保存动漫路径模板");
                }
                validateAsForm(binding.asRequestBodyJson(), mappedPath(binding.rootPath(),
                        target.ingestPrefix(), target.mediaSourcePrefix()));
            }
        }
        for (Map.Entry<String, String> entry : state.categoryDefaults().entrySet()) {
            requireCategory(entry.getKey());
            creationBinding(state, entry.getKey());
        }
    }

    private void validateAsForm(String body, String expectedSource) {
        try {
            JsonNode form = objectMapper.readTree(body);
            if (form == null || !form.isObject() || form.size() != AS_BOOLEANS.size() + AS_STRINGS.size()) {
                throw conflict("AS 运行表单必须符合已核实的完整字段白名单");
            }
            for (String key : AS_BOOLEANS) {
                if (!form.path(key).isBoolean()) {
                    throw conflict("AS 运行表单布尔字段缺失");
                }
            }
            for (String key : AS_STRINGS) {
                if (!form.path(key).isTextual()) {
                    throw conflict("AS 运行表单字符串字段缺失");
                }
            }
            if (!expectedSource.equals(normalizePath(form.path("media_dir").textValue()))) {
                throw conflict("AS 扫描来源与登记目标不一致");
            }
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw conflict("AS 运行表单无效");
        }
    }

    private IngestTargetState.Binding creationBinding(IngestTargetState state, String category) {
        String targetId = state.categoryDefaults().get(category);
        IngestTargetState.Target target = state.targets().stream()
                .filter(candidate -> candidate.id().equals(targetId)).findFirst()
                .orElseThrow(() -> conflict("类别尚未登记有效默认目标: " + category));
        if (!target.acceptingNewTasks()) {
            throw conflict("类别默认目标已停止接收新任务: " + category);
        }
        return categoryBinding(target, category);
    }

    private IngestTargetState.Binding bindingForPath(IngestTargetState state, String category, String path) {
        IngestTargetState.Target target = targetForPath(state, path, IngestTargetState.Target::ingestPrefix);
        IngestTargetState.Binding binding = categoryBinding(target, category);
        requireWithin(path, binding.rootPath());
        return binding;
    }

    private IngestTargetState.Binding categoryBinding(IngestTargetState.Target target, String category) {
        return target.bindings().stream().filter(binding -> binding.category().equals(category))
                .findFirst().orElseThrow(() -> conflict("目标缺少指定类别绑定: " + category));
    }

    private IngestTargetState.Target targetForPath(IngestTargetState state, String path,
                                                  Function<IngestTargetState.Target, String> prefix) {
        List<IngestTargetState.Target> matches = state.targets().stream()
                .filter(target -> within(path, prefix.apply(target))).toList();
        if (matches.size() != 1) {
            throw conflict("路径无法唯一确定已登记目标");
        }
        return matches.get(0);
    }

    private static void addDisjoint(List<String> roots, String value) {
        String root = normalizePath(value);
        if (!root.equals(value) || roots.stream().anyMatch(existing -> within(root, existing) || within(existing, root))) {
            throw conflict("目标路径未规范化或存在重叠");
        }
        roots.add(root);
    }

    private static String mappedPath(String path, String source, String destination) {
        requireWithin(path, source);
        if (path.equals(source)) {
            return destination;
        }
        String suffix = source.equals("/") ? path : path.substring(source.length());
        return normalizePath((destination.equals("/") ? "" : destination) + suffix);
    }

    private static boolean within(String path, String root) {
        return path.equals(root) || path.startsWith(root.equals("/") ? "/" : root + "/");
    }

    private static void requireWithin(String path, String root) {
        if (!within(path, root)) {
            throw conflict("路径不属于已确认目标范围");
        }
    }

    static String normalizePath(String path) {
        if (!StringUtils.hasText(path) || !path.startsWith("/") || path.indexOf('\0') >= 0) {
            throw badRequest("目标路径必须是有效绝对路径");
        }
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/+")) {
            if (segment.equals(".") || segment.equals("..")) {
                throw badRequest("目标路径不能包含相对路径段");
            }
            if (!segment.isEmpty()) {
                segments.add(segment);
            }
        }
        return "/" + String.join("/", segments);
    }

    private static void requireCategory(String category) {
        if (!CATEGORIES.contains(category == null ? "" : category)) {
            throw badRequest("不支持的目标类别");
        }
    }

    private static String requiredLegacy(String value) {
        if (!StringUtils.hasText(value)) {
            throw badRequest("部署初始目标配置尚未设置");
        }
        return value;
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, HttpStatus.BAD_REQUEST);
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message, HttpStatus.CONFLICT);
    }
}
