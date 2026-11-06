package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.config.SubtitleUploadProperties;
import com.medianexus.orchestrator.config.TmdbProperties;
import com.medianexus.orchestrator.config.UserQuotaProperties;
import com.medianexus.orchestrator.dto.settings.SettingsDtos.*;
import com.medianexus.orchestrator.mapper.SystemSettingMapper;
import jakarta.validation.Validator;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import jakarta.servlet.MultipartConfigElement;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.PropertyAccessorFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;

/**
 * 已接管字段持久化在独立的 system_settings 组行；部署值只在启动时捕获，不写回环境文件。
 * 每次业务操作读取已提交文档并产生独立快照，避免在途请求跨版本混用凭据与地址。
 */
@Service
public class SettingsService {
    private static final String LEGACY_QUOTA_KEY = "daily_content_create_limit";
    private final SystemSettingMapper mapper;
    private final ObjectMapper json;
    private final Validator validator;
    private final TransactionTemplate transactions;
    private final SettingsCipher cipher;
    private final Map<String, Definition> definitions = new LinkedHashMap<>();
    private final TmdbProperties tmdb;
    private final SubtitleUploadProperties subtitles;
    private MultipartConfigElement multipart = new MultipartProperties().createMultipartConfig();
    private SettingsIntegrationRuntime integrations;

    public SettingsService(SystemSettingMapper mapper, ObjectMapper json, Validator validator,
                           PlatformTransactionManager transactions, TmdbProperties tmdb,
                           SubtitleUploadProperties subtitles, UserQuotaProperties quota,
                           SettingsCipher cipher) {
        this.mapper = mapper; this.json = json; this.validator = validator; this.tmdb = tmdb; this.subtitles = subtitles;
        this.transactions = new TransactionTemplate(transactions);
        this.cipher = cipher;
        register("tmdb", "TMDB", tmdb, List.of("baseUrl", "imageBaseUrl", "apiToken",
                "defaultLanguage", "fallbackLanguage", "timeout"), Set.of("apiToken"));
        register("subtitle-upload", "字幕限制", subtitles, List.of("allowedExtensions", "maxUploadSize",
                "maxEntryCount", "maxTextSubtitleSize", "maxSupSize", "maxTotalExtractedSize", "maxCompressionRatio"), Set.of());
        register("quota", "每日创建额度", quota, List.of("dailyContentCreateLimit"), Set.of());
    }

    /** 表初始化与启动校验完成后接管消费者，避免启动阶段访问尚未创建的配置表。 */
    @EventListener(ApplicationReadyEvent.class)
    public void activateConsumers() {
        tmdb.setSettingsResolver(() -> snapshot(definition("tmdb"), load("tmdb"), TmdbProperties.class));
        subtitles.setSettingsResolver(() -> effectiveSnapshot("subtitle-upload", SubtitleUploadProperties.class));
    }

    @Autowired
    public void configureMultipartLimits(MultipartConfigElement multipart) { this.multipart = multipart; }

    @Autowired
    public void configureSoftware(SettingsIntegrationRuntime integrations) {
        this.integrations = integrations;
        for (var software : SettingsIntegrationCatalog.SOFTWARE) {
            BeanWrapper bean = PropertyAccessorFactory.forBeanPropertyAccess(integrations.activeSnapshot(software.id(), software.type()));
            List<Field> fields = software.keys().stream().map(key -> new Field(key, SettingsIntegrationCatalog.label(key),
                    typeName(bean.getPropertyType(key)), software.writable().contains(key), software.secrets().contains(key))).toList();
            definitions.put(software.id(), new Definition(software.id(), software.label(), software.type(),
                    integrations.deployment(software.id()), fields, true, !software.writable().isEmpty()));
        }
    }

    public Directory directory() {
        List<Summary> groups = new ArrayList<>(definitions.values().stream()
                .map(d -> new Summary(d.id, d.label, d.restart ? "RESTART" : "HOT", d.writable)).toList());
        if (integrations != null) groups.add(new Summary("deployment", "部署信息", "RESTART", false));
        return new Directory(groups);
    }

    public Group get(String id) {
        if (id.equals("deployment") && integrations != null) return integrations.deploymentInformation();
        return view(definition(id), load(id));
    }

    /** 外部候选检查不持数据库锁；短事务再次核对原版本再发布，竞争者不会被检查过程覆盖。 */
    public Group save(String id, Update update) {
        Definition definition = definition(id);
        requireWritable(definition);
        if (update == null) throw invalid("配置更新不能为空");
        Document current = load(id); requireVersion(current, update.version());
        Document candidate = candidate(definition, current, update);
        validateCandidate(definition, current, candidate);
        return persist(definition, current, candidate);
    }

    public Group restore(String id, Long version) {
        Definition definition = definition(id);
        requireWritable(definition);
        Document current = load(id); requireVersion(current, version);
        Document candidate = new Document(current.version + 1, Instant.now().toString(), Map.of(), true);
        validateCandidate(definition, current, candidate);
        return persist(definition, current, candidate);
    }

    private void validateCandidate(Definition definition, Document current, Document candidate) {
        validate(definition, candidate);
        if (definition.id.equals("tmdb") && connectionChanged(definition, current, candidate)) {
            TmdbProperties properties = snapshot(definition, candidate, TmdbProperties.class);
            if (StringUtils.hasText(properties.getApiToken())) requireSuccessfulCheck(probeTmdb(properties, "CANDIDATE"));
        }
        checkSoftwareCandidate(definition, current, candidate);
    }

    private Group persist(Definition definition, Document observed, Document candidate) {
        return transactions.execute(status -> {
            String key = storageKey(definition.id);
            mapper.insertSettingIfAbsent(key, encode(empty()));
            String previous = mapper.selectSettingValueForUpdate(key);
            Document current = decode(previous);
            if (!current.equals(observed)) throw conflict();
            if (mapper.compareAndSetSetting(key, previous, encode(candidate)) != 1) throw conflict();
            if (definition.id.equals("quota")) mapper.upsertSetting(LEGACY_QUOTA_KEY,
                    resolved(definition, candidate).get("dailyContentCreateLimit").toString());
            return view(definition, candidate);
        });
    }

    public Probe check(String id, Update update) {
        if (id.equals("deployment") && integrations != null) {
            if (update != null) throw invalid("部署信息不可通过普通设置写入");
            return new Probe("UNSUPPORTED", "部署信息只读；服务端口、数据库、卷和主密钥由部署环境管理", Instant.now().toString(), "EFFECTIVE", List.of());
        }
        Definition definition = definition(id);
        Document current = load(id);
        Document candidate = current;
        if (update != null) {
            requireWritable(definition);
            requireVersion(current, update.version()); candidate = candidate(definition, current, update);
        }
        if (definition.restart && update == null) return integrations.resources(id);
        validate(definition, candidate);
        if (id.equals("tmdb")) { return probeTmdb(snapshot(definition, candidate, TmdbProperties.class), update == null ? "EFFECTIVE" : "CANDIDATE"); }
        if (definition.restart) return integrations.probe(id, snapshot(definition, candidate, definition.type), "CANDIDATE");
        return new Probe("OK", "配置校验通过；无需外部连接", Instant.now().toString(), update == null ? "EFFECTIVE" : "CANDIDATE", List.of());
    }

    public Probe resources(String id) {
        if (id.equals("deployment") && integrations != null) return check(id, null);
        Definition definition = definition(id);
        if (definition.restart) return integrations.resources(id);
        return new Probe("UNSUPPORTED", "此配置组没有可列出的外部资源", Instant.now().toString(), "EFFECTIVE", List.of());
    }

    /** 接入计划绑定已生效实际值与版本的认证摘要，可跨重启核对且不泄露凭据。 */
    public String effectiveFingerprint(String id) {
        Group group = get(id); requireApplied(group);
        if ("UNAVAILABLE".equals(group.effectiveFingerprint())) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "设置认证指纹不可用，请检查部署加密密钥后重新预览接入", HttpStatus.SERVICE_UNAVAILABLE);
        }
        return group.effectiveFingerprint();
    }
    public void requireEffectiveFingerprint(String id, String expected) {
        if (!Objects.equals(effectiveFingerprint(id), expected)) { throw conflict(); }
    }
    public <T> T effectiveSnapshot(String id, Class<T> type) {
        Definition definition = definition(id); Document document = load(id);
        requireApplied(view(definition, document));
        if (definition.restart) return integrations.activeSnapshot(id, type);
        T snapshot = snapshot(definition, document, type);
        if (snapshot instanceof SubtitleUploadProperties limits) {
            limits.setMaxUploadSize(DataSize.ofBytes(effectiveUploadLimit(limits.getMaxUploadSize().toBytes())));
        }
        return snapshot;
    }

    private void requireApplied(Group group) {
        if ("PENDING_RESTART".equals(group.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "配置尚未生效，请重启 MediaNexus-Orchestrator 后重新预览接入", HttpStatus.CONFLICT);
        }
    }

    /** 既有额度 API 与设置页共享同一持久化值和版本；原 API 无版本参数时按当前版本提交。 */
    public void saveQuotaFromLegacyApi(int quota) {
        save("quota", new Update(load("quota").version, Map.of("dailyContentCreateLimit", quota), Map.of()));
    }

    private long effectiveUploadLimit(long configured) {
        long limit = multipart.getMaxFileSize() < 0 ? configured : Math.min(configured, multipart.getMaxFileSize());
        return multipart.getMaxRequestSize() < 0 ? limit : Math.min(limit, multipart.getMaxRequestSize());
    }

    private void register(String id, String label, Object deploymentBean, List<String> keys, Set<String> secrets) {
        BeanWrapper bean = PropertyAccessorFactory.forBeanPropertyAccess(deploymentBean);
        Map<String, Object> deployment = new LinkedHashMap<>(); List<Field> fields = new ArrayList<>();
        for (String key : keys) {
            Class<?> type = bean.getPropertyType(key);
            deployment.put(key, external(bean.getPropertyValue(key)));
            fields.add(new Field(key, SettingsIntegrationCatalog.label(key), typeName(type), true, secrets.contains(key)));
        }
        definitions.put(id, new Definition(id, label, deploymentBean.getClass(), deployment, fields, false, true));
    }

    private Document candidate(Definition definition, Document current, Update update) {
        Map<String, Object> overrides = new LinkedHashMap<>(current.overrides);
        for (var entry : (update.values() == null ? Map.<String, Object>of() : update.values()).entrySet()) {
            Field field = field(definition, entry.getKey());
            if (field.secret() || !field.writable() || entry.getValue() == null) { throw invalid("只允许写入普通可写字段"); }
            overrides.put(field.key(), entry.getValue());
        }
        for (var entry : (update.secrets() == null ? Map.<String, SecretInput>of() : update.secrets()).entrySet()) {
            Field field = field(definition, entry.getKey()); SecretInput secret = entry.getValue();
            if (!field.secret() || !field.writable() || secret == null) { throw invalid("凭据字段无效"); }
            if ("KEEP".equals(secret.action()) && secret.value() == null) { continue; }
            if (!"REPLACE".equals(secret.action()) || !StringUtils.hasText(secret.value())
                    || secret.value().contains("\n") || secret.value().contains("\r") || secret.value().matches("[*•]+")) {
                throw invalid("凭据仅支持保持或替换为非空新值");
            }
            overrides.put(field.key(), cipher.encrypt(definition.id + ":" + field.key(), secret.value()));
        }
        return new Document(current.version + 1, Instant.now().toString(), overrides, current.restored);
    }

    private Group view(Definition definition, Document document) {
        Map<String, Object> values = configuredValues(definition, document);
        Map<String, Object> safe = safeValues(definition, values); Map<String, String> sources = new LinkedHashMap<>();
        for (Field field : definition.fields) {
            sources.put(field.key(), document.overrides.containsKey(field.key()) ? "DATABASE" :
                    definition.id.equals("quota") && !document.restored && legacyQuota() != null ? "LEGACY_DATABASE" : "DEPLOYMENT");
        }
        Map<String, Object> effective = new LinkedHashMap<>(safe);
        Map<String, Object> constraints = Map.of(); String state = "EFFECTIVE";
        long appliedVersion = document.version;
        if (definition.restart) {
            Map<String, Object> active = integrations.active(definition.id);
            effective = safeValues(definition, active);
            try {
                Map<String, Object> desired = SettingsIntegrationBootstrap.read(PropertyAccessorFactory.forBeanPropertyAccess(
                        snapshot(definition, document, definition.type)), definition.fields.stream().map(Field::key).toList());
                if (!Objects.equals(desired, active)) state = "PENDING_RESTART";
            } catch (BusinessException exception) {
                if (exception.getHttpStatus() != HttpStatus.SERVICE_UNAVAILABLE) throw exception;
                state = "UNAVAILABLE";
            }
            appliedVersion = integrations.activeVersion(definition.id);
        }
        if (definition.id.equals("subtitle-upload")) {
            long uploadLimit = exactLong(values.get("maxUploadSize"));
            if (uploadLimit > effectiveUploadLimit(uploadLimit)) {
                effective.put("maxUploadSize", effectiveUploadLimit(uploadLimit)); state = "LIMITED_BY_DEPLOYMENT";
            }
            constraints = Map.of("multipartMaxFileSizeBytes", multipart.getMaxFileSize(),
                    "multipartMaxRequestSizeBytes", multipart.getMaxRequestSize(),
                    "multipartLimitApplyMode", "RESTART", "multipartRestartService", "MediaNexus-Orchestrator",
                    "requestLimitIncludesMultipartEnvelope", true);
        }
        return new Group(definition.id, definition.label, document.version, document.updatedAt, definition.restart ? "RESTART" : "HOT", state,
                definition.restart && state.equals("PENDING_RESTART") ? List.of("MediaNexus-Orchestrator") : List.of(),
                state.equals("UNAVAILABLE") ? "UNAVAILABLE" : fingerprint(definition, document, appliedVersion),
                definition.fields, safe, effective, sources, constraints);
    }

    private String fingerprint(Definition definition, Document document, long appliedVersion) {
        try {
            Map<String, Object> actual = new LinkedHashMap<>(definition.restart
                    ? integrations.active(definition.id) : resolved(definition, document));
            if (definition.id.equals("subtitle-upload")) {
                actual.put("maxUploadSize", effectiveUploadLimit(exactLong(actual.get("maxUploadSize"))));
            }
            String canonical = json.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(Map.of("version", appliedVersion, "values", actual));
            return cipher.fingerprint("settings-effective:v1:" + definition.id, canonical);
        } catch (BusinessException exception) {
            if (exception.getHttpStatus() != HttpStatus.SERVICE_UNAVAILABLE) throw exception;
            return "UNAVAILABLE";
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "有效设置不可序列化", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private Map<String, Object> safeValues(Definition definition, Map<String, Object> values) {
        Map<String, Object> safe = new LinkedHashMap<>(values);
        for (Field field : definition.fields) {
            if (field.secret()) safe.put(field.key(), Map.of("configured", values.get(field.key()) instanceof String text && StringUtils.hasText(text)));
            else if (values.get(field.key()) instanceof String text) {
                try {
                    URI uri = URI.create(text.trim());
                    if (("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                            && (uri.getUserInfo() != null || uri.getRawQuery() != null)) {
                        safe.put(field.key(), new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null)
                                + "（认证参数已隐藏）");
                    }
                } catch (IllegalArgumentException | java.net.URISyntaxException ignored) { }
            }
        }
        return safe;
    }

    private Map<String, Object> configuredValues(Definition definition, Document document) {
        Map<String, Object> values = new LinkedHashMap<>(definition.deployment);
        if (definition.id.equals("quota") && !document.restored && legacyQuota() != null) {
            values.put("dailyContentCreateLimit", legacyQuota());
        }
        values.putAll(document.overrides);
        return values;
    }

    private Map<String, Object> resolved(Definition definition, Document document) {
        Map<String, Object> values = configuredValues(definition, document);
        for (var entry : document.overrides.entrySet()) {
            if (field(definition, entry.getKey()).secret()) {
                values.put(entry.getKey(), cipher.decrypt(definition.id + ":" + entry.getKey(), (String) entry.getValue()));
            }
        }
        return values;
    }

    private <T> T snapshot(Definition definition, Document document, Class<T> type) {
        if (!type.equals(definition.type)) { throw invalid("配置组类型不匹配"); }
        try {
            T target = definition.restart ? integrations.activeSnapshot(definition.id, type) : type.getDeclaredConstructor().newInstance();
            BeanWrapper bean = PropertyAccessorFactory.forBeanPropertyAccess(target);
            for (var entry : resolved(definition, document).entrySet()) {
                Class<?> fieldType = bean.getPropertyType(entry.getKey()); Object value = entry.getValue();
                if (fieldType.equals(Duration.class)) { value = Duration.parse((String) value); }
                else if (fieldType.equals(DataSize.class)) { value = DataSize.ofBytes(exactLong(value)); }
                else if (fieldType.equals(int.class)) { value = Math.toIntExact(exactLong(value)); }
                else if ((fieldType.equals(long.class) || fieldType.equals(Long.class)) && value != null) { value = exactLong(value); }
                else if ((fieldType.equals(boolean.class) || fieldType.equals(Boolean.class)) && !(value instanceof Boolean)) { throw invalid("配置字段必须为布尔值"); }
                else if (fieldType.equals(String.class) && value != null && !(value instanceof String)) { throw invalid("配置字段类型无效"); }
                else if (fieldType.equals(List.class) && (!(value instanceof List<?> list) || list.stream().anyMatch(v -> !(v instanceof String)))) { throw invalid("配置字段类型无效"); }
                bean.setPropertyValue(entry.getKey(), value);
            }
            return target;
        } catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw invalid("配置字段类型或格式无效"); }
    }

    private void validate(Definition definition, Document document) {
        Object bean = snapshot(definition, document, definition.type);
        if (!validator.validate(bean).isEmpty()) { throw invalid("配置字段不满足必填或范围约束"); }
        if (definition.restart) integrations.validate(definition.id, bean);
        Map<String, Object> values = resolved(definition, document);
        if (definition.id.equals("tmdb")) {
            validateUrl((String) values.get("baseUrl")); validateUrl((String) values.get("imageBaseUrl"));
        } else if (definition.id.equals("subtitle-upload")) {
            SubtitleUploadProperties limits = (SubtitleUploadProperties) bean;
            if (limits.getAllowedExtensions().stream().anyMatch(extension -> extension == null
                    || java.util.Arrays.stream(extension.split(",", -1)).anyMatch(part -> !part.trim().matches("\\.?[A-Za-z0-9]+")))) {
                throw invalid("字幕扩展名必须为非空文件后缀");
            }
            if (values.entrySet().stream().anyMatch(e -> field(definition, e.getKey()).type().equals("bytes") && exactLong(e.getValue()) <= 0)) { throw invalid("字幕大小限制必须为正整数"); }
            if (document.overrides.containsKey("maxUploadSize") &&
                    ((multipart.getMaxFileSize() >= 0 && limits.getMaxUploadSize().toBytes() > multipart.getMaxFileSize())
                    || (multipart.getMaxRequestSize() >= 0 && limits.getMaxUploadSize().toBytes() >= multipart.getMaxRequestSize()))) {
                throw invalid("上传限制超出 Servlet 部署上限；提高 spring.servlet.multipart 上限需重启 MediaNexus-Orchestrator，请为请求封装预留空间");
            }
        } else if (definition.id.equals("quota")) {
            long quota = exactLong(values.get("dailyContentCreateLimit"));
            if (quota < 0 || quota > 9) { throw invalid("额度必须为 0-9 的整数"); }
        }
    }

    private boolean connectionChanged(Definition definition, Document before, Document after) {
        Map<String, Object> old = resolved(definition, before); Map<String, Object> next = resolved(definition, after);
        return List.of("baseUrl", "apiToken", "timeout").stream().anyMatch(key -> !Objects.equals(old.get(key), next.get(key)));
    }

    private void requireWritable(Definition definition) {
        if (!definition.writable) throw invalid("此设置组仅提供只读状态，不允许保存或恢复");
    }

    private void checkSoftwareCandidate(Definition definition, Document before, Document after) {
        if (!definition.restart || !definition.writable || definition.id.equals("smartstrm")) return;
        Map<String, Object> old = resolved(definition, before), next = resolved(definition, after);
        boolean connectionChanged = definition.fields.stream().filter(Field::writable).anyMatch(field ->
                (field.secret() || List.of("baseUrl", "apiBaseUrl", "quarkApiBaseUrl", "username", "host", "port", "timeout", "operationTimeout", "enabled", "disabled", "organizationEnabled").contains(field.key()))
                        && !Objects.equals(old.get(field.key()), next.get(field.key())));
        if (!connectionChanged || Boolean.TRUE.equals(next.get("disabled")) || Boolean.FALSE.equals(next.get("enabled")) || Boolean.FALSE.equals(next.get("organizationEnabled"))) return;
        Probe probe = integrations.probe(definition.id, snapshot(definition, after, definition.type), "CANDIDATE");
        if (after.restored && after.overrides.isEmpty() && probe.status().equals("NOT_CONFIGURED")) return;
        if (!probe.status().equals("UNSUPPORTED")) requireSuccessfulCheck(probe);
    }

    private Probe probeTmdb(TmdbProperties properties, String source) {
        if (!StringUtils.hasText(properties.getApiToken())) { return probe("NOT_CONFIGURED", "TMDB 读取 Token 未配置", source); }
        try {
            String token = properties.getApiToken().trim().replaceFirst("(?i)^Bearer +", "");
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getBaseUrl().replaceAll("/+$", "") + "/configuration"))
                    .timeout(properties.getTimeout()).header("Authorization", "Bearer " + token).GET().build();
            HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(properties.getTimeout()).build()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) { return probe("UNAUTHORIZED", "TMDB 拒绝候选凭据", source); }
            if (response.statusCode() < 200 || response.statusCode() >= 300 || !json.readTree(response.body()).path("images").isObject()) {
                return probe("FAILED", "TMDB 只读配置检查失败或响应不支持", source);
            }
            return probe("OK", "TMDB 只读配置检查通过", source);
        } catch (InterruptedException exception) { Thread.currentThread().interrupt(); return probe("FAILED", "TMDB 检查中断", source); }
        catch (Exception exception) { return probe("FAILED", "TMDB 只读配置检查失败", source); }
    }
    private Probe probe(String status, String message, String source) { return new Probe(status, message, Instant.now().toString(), source, List.of()); }
    private void requireSuccessfulCheck(Probe probe) {
        if (!probe.status().equals("OK")) { throw new BusinessException(ErrorCode.BAD_GATEWAY, probe.message(), HttpStatus.BAD_GATEWAY); }
    }
    private void validateUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getFragment() != null) { throw invalid("服务地址必须为不含认证、查询或片段的 HTTP(S) URL"); }
        } catch (IllegalArgumentException exception) { throw invalid("服务地址格式无效"); }
    }
    private Long legacyQuota() {
        String raw = mapper.selectSettingValue(LEGACY_QUOTA_KEY);
        if (!StringUtils.hasText(raw)) { return null; }
        try { long value = Long.parseLong(raw.trim()); return value >= 0 && value <= 9 ? value : null; }
        catch (NumberFormatException exception) { return null; }
    }
    private static Object external(Object value) {
        if (value instanceof Duration duration) { return duration.toString(); }
        if (value instanceof DataSize size) { return size.toBytes(); }
        return value;
    }
    private static long exactLong(Object value) {
        if (!(value instanceof Number number) || value instanceof Float || value instanceof Double) { throw invalid("配置字段必须为整数"); }
        try { return new java.math.BigDecimal(number.toString()).longValueExact(); }
        catch (ArithmeticException exception) { throw invalid("配置字段超出整数范围"); }
    }
    private String typeName(Class<?> type) {
        if (type.equals(Duration.class)) { return "duration"; }
        if (type.equals(DataSize.class)) { return "bytes"; }
        if (type.equals(int.class) || type.equals(long.class) || type.equals(Integer.class) || type.equals(Long.class)) { return "integer"; }
        if (type.equals(boolean.class) || type.equals(Boolean.class)) { return "boolean"; }
        if (type.equals(List.class)) { return "string-list"; }
        return "string";
    }
    private Definition definition(String id) {
        Definition definition = definitions.get(id);
        if (definition == null) { throw new BusinessException(ErrorCode.NOT_FOUND, "设置组不存在", HttpStatus.NOT_FOUND); }
        return definition;
    }
    private Field field(Definition definition, String key) {
        return definition.fields.stream().filter(f -> f.key().equals(key)).findFirst().orElseThrow(() -> invalid("配置包含未知或只读字段"));
    }
    private void requireVersion(Document document, Long version) { if (version == null || version < 0) { throw invalid("必须提供非负配置版本"); } if (document.version != version) { throw conflict(); } }
    private String storageKey(String id) { return "managed_settings_" + id; }
    private Document load(String id) { return decode(mapper.selectSettingValue(storageKey(id))); }
    private Document empty() { return new Document(0, null, Map.of(), false); }
    private Document decode(String value) {
        if (value == null) { return empty(); }
        try { return json.readValue(value, Document.class); }
        catch (JsonProcessingException exception) { throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "持久化配置不可读取", HttpStatus.SERVICE_UNAVAILABLE); }
    }
    private String encode(Document document) {
        try { return json.writeValueAsString(document); }
        catch (JsonProcessingException exception) { throw invalid("配置不可序列化"); }
    }
    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.BAD_REQUEST, message); }
    private BusinessException conflict() { return new BusinessException(ErrorCode.CONFLICT, "配置版本已变化，请重新读取后重试", HttpStatus.CONFLICT); }
    private record Definition(String id, String label, Class<?> type, Map<String, Object> deployment, List<Field> fields, boolean restart, boolean writable) {}
    private record Document(long version, String updatedAt, Map<String, Object> overrides, boolean restored) {}
}
