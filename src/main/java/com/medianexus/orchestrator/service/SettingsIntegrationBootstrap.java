package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.config.DatabaseSshTunnelLifecycle;
import com.medianexus.orchestrator.mapper.SystemSettingMapper;
import jakarta.validation.Validator;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.PropertyAccessorFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/**
 * 在配置绑定完成、客户端构造之前应用重启组。启动数据库隧道沿用原有生命周期，
 * 不以 ApplicationReady 后改属性冒充已有 channel/cache 已更新。
 */
@Component
public class SettingsIntegrationBootstrap implements BeanPostProcessor {
    private final ObjectProvider<SystemSettingMapper> mappers;
    private final ObjectProvider<ObjectMapper> json;
    private final ObjectProvider<SettingsCipher> ciphers;
    private final ObjectProvider<DatabaseSshTunnelLifecycle> tunnels;
    private final ObjectProvider<Validator> validators;
    private final Map<String, Map<String, Object>> deployment = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> active = new ConcurrentHashMap<>();
    private final Map<String, Long> versions = new ConcurrentHashMap<>();
    private boolean tableReady;

    public SettingsIntegrationBootstrap(ObjectProvider<SystemSettingMapper> mappers, ObjectProvider<ObjectMapper> json,
                                        ObjectProvider<SettingsCipher> ciphers, ObjectProvider<DatabaseSshTunnelLifecycle> tunnels,
                                        ObjectProvider<Validator> validators) {
        this.mappers = mappers; this.json = json; this.ciphers = ciphers; this.tunnels = tunnels; this.validators = validators;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String name) {
        List<SettingsIntegrationCatalog.Software> groups = SettingsIntegrationCatalog.SOFTWARE.stream()
                .filter(group -> group.type().isInstance(bean)).toList();
        if (groups.isEmpty()) return bean;
        BeanWrapper properties = PropertyAccessorFactory.forBeanPropertyAccess(bean);
        for (var group : groups) deployment.put(group.id(), read(properties, group.keys()));
        ensureTable();
        for (var group : groups) {
            String raw = mappers.getObject().selectSettingValue("managed_settings_" + group.id());
            long version = 0;
            if (raw != null) {
                try {
                    JsonNode document = json.getObject().readTree(raw);
                    if (!document.path("version").isIntegralNumber() || document.path("version").asLong() < 0
                            || !document.path("overrides").isObject()) throw new IllegalArgumentException();
                    version = document.path("version").asLong();
                    var entries = document.path("overrides").fields();
                    while (entries.hasNext()) {
                        var entry = entries.next();
                        if (!group.writable().contains(entry.getKey())) throw new IllegalArgumentException();
                        Object value = json.getObject().convertValue(entry.getValue(), Object.class);
                        if (group.secrets().contains(entry.getKey())) value = ciphers.getObject().decrypt(group.id() + ":" + entry.getKey(), (String) value);
                        write(properties, entry.getKey(), value);
                    }
                    if (!validators.getObject().validate(bean).isEmpty()) throw new IllegalArgumentException();
                } catch (BusinessException exception) { throw exception; }
                catch (Exception exception) { throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "软件持久化配置不可应用，请检查设置组 " + group.id()); }
            }
            active.put(group.id(), read(properties, group.keys()));
            versions.put(group.id(), version);
        }
        return bean;
    }

    private synchronized void ensureTable() {
        if (tableReady) return;
        tunnels.getObject().ensureRunning();
        mappers.getObject().createTableIfNotExists();
        tableReady = true;
    }

    public Map<String, Object> deployment(String id) { return require(deployment, id); }
    public Map<String, Object> active(String id) { return require(active, id); }
    public long activeVersion(String id) { return versions.getOrDefault(id, 0L); }

    private Map<String, Object> require(Map<String, Map<String, Object>> values, String id) {
        Map<String, Object> result = values.get(id);
        if (result == null) throw new IllegalStateException("软件配置尚未完成启动绑定: " + id);
        return result;
    }

    public static Map<String, Object> read(BeanWrapper bean, List<String> keys) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : keys) {
            Object value = bean.getPropertyValue(key);
            if (value instanceof Duration duration) value = duration.toString();
            else if (value instanceof DataSize size) value = size.toBytes();
            else if (value instanceof List<?> list) value = List.copyOf(list);
            result.put(key, value);
        }
        return Collections.unmodifiableMap(result);
    }

    /** 相同的边界转换用于启动应用和候选快照，拒绝有损数字、隐式布尔和不匹配类型。 */
    public static void write(BeanWrapper bean, String key, Object value) {
        Class<?> type = bean.getPropertyType(key);
        if (value == null) {
            if (type.isPrimitive()) throw new IllegalArgumentException();
        } else if (type == Duration.class) value = Duration.parse((String) value);
        else if (type == DataSize.class) value = DataSize.ofBytes(integer(value));
        else if (type == int.class || type == Integer.class) value = Math.toIntExact(integer(value));
        else if (type == long.class || type == Long.class) value = integer(value);
        else if ((type == boolean.class || type == Boolean.class) && !(value instanceof Boolean)) throw new IllegalArgumentException();
        else if (type == String.class && !(value instanceof String)) throw new IllegalArgumentException();
        bean.setPropertyValue(key, value);
    }

    private static long integer(Object value) {
        if (!(value instanceof Number number) || value instanceof Double || value instanceof Float) throw new IllegalArgumentException();
        return new java.math.BigDecimal(number.toString()).longValueExact();
    }
}
