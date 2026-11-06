package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import java.util.Set;

/** 可继承扫描参数的协议白名单；已绑定的路径、UUID、认证和掉盘探针不属于模板。 */
public final class AutoSymlinkScanParameters {
    private AutoSymlinkScanParameters() {}
    private static final Set<String> BOOLEANS = Set.of("sync_enabled", "restart_sync_enabled", "sync_scheduled",
            "symlink_creator", "metadata_copyer", "metadata_covered", "metadata_skipped", "symlink_dir_checker",
            "symlink_checker", "metadata_checker", "observer_enabled", "observer_symlink_creator", "observer_metadata_copyer",
            "observer_symlink_checker", "observer_metadata_checker", "backup_scheduled");
    private static final Set<String> STRINGS = Set.of("exclude_folder", "sync_time", "metadata_copyer_mode", "num_threads",
            "observer_time", "backup_time", "backup_ext", "symlink_size", "symlink_ext", "metadata_ext");

    public static ObjectNode apply(ObjectMapper json, String overrides, ObjectNode existing) {
        ObjectNode result = existing.deepCopy();
        if (overrides == null || overrides.isBlank()) return result;
        try {
            var node = json.readTree(overrides);
            if (!node.isObject()) throw invalid();
            var entries = node.fields();
            while (entries.hasNext()) {
                var entry = entries.next();
                boolean valid = BOOLEANS.contains(entry.getKey()) ? entry.getValue().isBoolean()
                        : STRINGS.contains(entry.getKey()) && entry.getValue().isTextual();
                if (!valid) throw invalid();
                if (Set.of("observer_time", "symlink_size").contains(entry.getKey()) && !entry.getValue().asText().matches("[0-9]+")) throw invalid();
                if (entry.getKey().equals("num_threads") && !entry.getValue().asText().matches("[1-9][0-9]*")) throw invalid();
                result.set(entry.getKey(), entry.getValue());
            }
            return result;
        } catch (java.io.IOException exception) { throw invalid(); }
    }
    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.BAD_REQUEST, "AS 通用扫描参数必须符合已核实字段和类型；不允许路径、UUID、认证或掉盘探针");
    }
}
