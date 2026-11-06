package com.medianexus.orchestrator.service.settings.targets;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.mapper.SystemSettingMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 注册、类别默认与接收资格共享一行锁，网络读写必须在调用本模块之前完成。 */
@Service
public class TargetRegistration {
    private final SystemSettingMapper mapper;
    private final ObjectMapper json;
    private final IngestTargetRegistry registry;
    private final TransactionTemplate transactions;

    public TargetRegistration(SystemSettingMapper mapper, ObjectMapper json, IngestTargetRegistry registry,
                              PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.json = json;
        this.registry = registry;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public IngestTargetState switchDefaults(long expectedVersion, Map<String, String> defaults) {
        if (defaults == null || defaults.isEmpty()) {
            throw invalid("请选择需要切换的类别");
        }
        return transactions.execute(status -> {
            IngestTargetState current = lock(expectedVersion);
            if (!current.managed()) {
                throw conflict("请先完成主号接管");
            }
            Map<String, String> selected = new LinkedHashMap<>(current.categoryDefaults());
            selected.putAll(defaults);
            return save(new IngestTargetState(1, current.version() + 1, true, current.targets(), selected));
        });
    }

    public IngestTargetState setAcceptingNewTasks(long expectedVersion, String targetId, boolean accepting) {
        return transactions.execute(status -> {
            IngestTargetState current = lock(expectedVersion);
            List<String> categories = current.categoryDefaults().entrySet().stream()
                    .filter(entry -> entry.getValue().equals(targetId)).map(Map.Entry::getKey).sorted().toList();
            if (!accepting && !categories.isEmpty()) {
                throw conflict("请先替换这些类别的默认目标: " + String.join(",", categories));
            }
            List<IngestTargetState.Target> targets = new ArrayList<>();
            boolean found = false;
            for (IngestTargetState.Target target : current.targets()) {
                if (target.id().equals(targetId)) {
                    found = true;
                    targets.add(new IngestTargetState.Target(target.id(), target.displayName(), target.ingestPrefix(),
                            target.cloudDrivePrefix(), target.mediaSourcePrefix(), accepting, target.bindings()));
                } else {
                    targets.add(target);
                }
            }
            if (!found) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "下载目标不存在", HttpStatus.NOT_FOUND);
            }
            return save(new IngestTargetState(1, current.version() + 1, current.managed(), targets, current.categoryDefaults()));
        });
    }

    // 仅供同包的证据核对/接入最终事务调用；不作为接受客户端绑定的管理 API。
    IngestTargetState registerVerifiedTarget(long expectedVersion, IngestTargetState.Target verified,
                                            Map<String, String> defaults) {
        IngestTargetState current = lock(expectedVersion);
        if (current.targets().stream().anyMatch(target -> target.id().equals(verified.id()))) {
            throw conflict("目标已登记，不允许覆盖其路径与绑定");
        }
        List<IngestTargetState.Target> targets = new ArrayList<>(current.targets());
        targets.add(verified);
        Map<String, String> selected = new LinkedHashMap<>(current.categoryDefaults());
        selected.putAll(defaults);
        return save(new IngestTargetState(1, current.version() + 1, true, targets, selected));
    }

    private IngestTargetState lock(long expectedVersion) {
        if (expectedVersion < 0) {
            throw invalid("必须提供非负注册版本");
        }
        mapper.insertSettingIfAbsent(IngestTargetRegistry.SETTING_KEY,
                encode(new IngestTargetState(1, 0, false, List.of(), Map.of())));
        IngestTargetState current = registry.decodeState(mapper.selectSettingValueForUpdate(IngestTargetRegistry.SETTING_KEY));
        if (current.version() != expectedVersion) {
            throw conflict("目标注册版本已变化，请重新读取后预览");
        }
        return current;
    }

    private IngestTargetState save(IngestTargetState state) {
        registry.validateState(state);
        mapper.upsertSetting(IngestTargetRegistry.SETTING_KEY, encode(state));
        return state;
    }

    private String encode(Object value) {
        try {
            String encoded = json.writeValueAsString(value);
            if (encoded.getBytes(StandardCharsets.UTF_8).length > 65535) {
                throw conflict("注册载荷超过当前存储容量，未保存");
            }
            return encoded;
        } catch (JsonProcessingException exception) {
            throw invalid("目标注册载荷无效");
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, HttpStatus.BAD_REQUEST);
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message, HttpStatus.CONFLICT);
    }
}
