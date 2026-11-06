package com.medianexus.orchestrator.service.settings.targets;

import java.util.List;
import java.util.Map;

/** 已接通目标的不可变路径登记；计划和执行记录不属于这份注册载荷。 */
public record IngestTargetState(
        int schemaVersion,
        long version,
        boolean managed,
        List<Target> targets,
        Map<String, String> categoryDefaults
) {
    public IngestTargetState {
        targets = List.copyOf(targets);
        categoryDefaults = Map.copyOf(categoryDefaults);
    }

    public record Target(
            String id,
            String displayName,
            String ingestPrefix,
            String cloudDrivePrefix,
            String mediaSourcePrefix,
            boolean acceptingNewTasks,
            List<Binding> bindings
    ) {
        public Target {
            bindings = List.copyOf(bindings);
        }
    }

    /** 仅保存经当前手动运行协议白名单审查的完整请求体，不存创建表单或认证。 */
    public record Binding(
            String category,
            String rootPath,
            String animePathTemplate,
            String asUuid,
            String asRequestBodyJson,
            String strmRootPath,
            String embyLibraryId,
            List<LibraryBinding> embyLibraries
    ) {
        public Binding {
            embyLibraries = embyLibraries == null ? List.of(new LibraryBinding(embyLibraryId, "")) : List.copyOf(embyLibraries);
            if (embyLibraryId == null && !embyLibraries.isEmpty()) { embyLibraryId = embyLibraries.get(0).libraryId(); }
        }
        public Binding(String category, String rootPath, String animePathTemplate, String asUuid,
                       String asRequestBodyJson, String strmRootPath, String embyLibraryId) {
            this(category, rootPath, animePathTemplate, asUuid, asRequestBodyJson, strmRootPath, embyLibraryId, null);
        }
    }

    /** 相对路径归属本绑定的 STRM 根；一个 AS 任务可关联多个既有 Emby 库。 */
    public record LibraryBinding(String libraryId, String relativePath) {}
}
