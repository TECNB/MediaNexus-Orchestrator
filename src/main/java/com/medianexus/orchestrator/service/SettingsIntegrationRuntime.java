package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.config.CloudDrive2Properties;
import com.medianexus.orchestrator.config.WeComProperties;
import com.medianexus.orchestrator.dto.settings.SettingsDtos.*;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.PropertyAccessorFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 保存组的启动快照和探针归属；在途调用始终使用原客户端，不发布半套连接。 */
@Component
public class SettingsIntegrationRuntime {
    private final SettingsIntegrationBootstrap bootstrap;
    private final SettingsConnectionProbe probes;
    private final ObjectMapper json;
    private final Environment environment;
    private final Map<String, Object> beans = new LinkedHashMap<>();

    public SettingsIntegrationRuntime(SettingsIntegrationBootstrap bootstrap, SettingsConnectionProbe probes,
                                      ObjectMapper json, Environment environment, ApplicationContext context) {
        this.bootstrap = bootstrap; this.probes = probes; this.json = json; this.environment = environment;
        for (var group : SettingsIntegrationCatalog.SOFTWARE) beans.put(group.id(), context.getBean(group.type()));
    }

    public Map<String, Object> deployment(String id) { return bootstrap.deployment(id); }
    public Map<String, Object> active(String id) { return bootstrap.active(id); }
    public long activeVersion(String id) { return bootstrap.activeVersion(id); }
    public Probe probe(String id, Object candidate, String source) { return probes.check(id, candidate, source); }
    public Probe resources(String id) { return probes.check(id, beans.get(id), "EFFECTIVE"); }

    public <T> T activeSnapshot(String id, Class<T> type) {
        Object bean = beans.get(id);
        if (bean == null || !type.isInstance(bean)) throw invalid("配置组类型不匹配");
        return json.convertValue(bean, type);
    }

    public void validate(String id, Object bean) {
        if (bean instanceof com.medianexus.orchestrator.config.AutoSymlinkProperties as) {
            AutoSymlinkScanParameters.apply(json, as.getNewAccountScanParametersJson(), json.createObjectNode());
        }
        var properties = PropertyAccessorFactory.forBeanPropertyAccess(bean);
        var group = SettingsIntegrationCatalog.SOFTWARE.stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
        for (String key : group.writable()) {
            Object value = properties.getPropertyValue(key);
            if (value instanceof Duration duration && (duration.isNegative() || duration.isZero())) throw invalid("等待与超时必须为正数");
            if (value instanceof Number number && !key.equals("retryLimit") && number.longValue() <= 0) throw invalid("数量、容量和端口必须为正数");
            if (value instanceof String text && !group.secrets().contains(key)
                    && (key.endsWith("Url") || key.equals("baseUrl")) && StringUtils.hasText(text)) validateUrl(text, false);
        }
        if (id.equals("smartstrm")) {
            Object callback = properties.getPropertyValue("smartstrmWebhook");
            if (callback instanceof String text && StringUtils.hasText(text)) validateUrl(text, true);
        }
        if (bean instanceof CloudDrive2Properties cd && cd.isOrganizationEnabled()) {
            if (!StringUtils.hasText(cd.getHost()) || cd.getHost().contains("://") || !StringUtils.hasText(cd.getApiToken())) throw invalid("启用 CloudDrive2 整理需要有效的 gRPC 主机、端口和 Token");
        }
        if (bean instanceof WeComProperties wecom && wecom.isEnabled()) {
            if (!StringUtils.hasText(wecom.getCorpId()) || !StringUtils.hasText(wecom.getAppSecret())
                    || wecom.getAgentId() == null || wecom.getAgentId() <= 0
                    || !StringUtils.hasText(wecom.getFrontendBaseUrl())) throw invalid("启用企业微信需要完整的应用身份、凭据和消息跳转地址");
        }
    }

    public Group deploymentInformation() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("serverPort", environment.getProperty("server.port", "8080"));
        values.put("databaseConfigured", StringUtils.hasText(environment.getProperty("spring.datasource.url")));
        values.put("sshTunnelEnabled", environment.getProperty("medianexus.datasource.ssh-tunnel.enabled", Boolean.class, false));
        values.put("settingsEncryptionKeyConfigured", StringUtils.hasText(environment.getProperty("MEDIANEXUS_SETTINGS_ENCRYPTION_KEY")));
        values.put("registrationPasswordSecretConfigured", StringUtils.hasText(environment.getProperty("medianexus.emby.registration-password-secret")));
        values.put("frontendApiAddress", "由前端构建环境管理，后端不能读取当前浏览器构建值");
        values.put("containerVolumes", "由部署环境管理；账号接入会另外核对所需数据路径可读性");
        Map<String, String> labels = Map.of("serverPort", "服务端口（部署配置）", "databaseConfigured", "数据库连接已配置",
                "sshTunnelEnabled", "数据库 SSH 隧道", "settingsEncryptionKeyConfigured", "设置加密主密钥已配置",
                "registrationPasswordSecretConfigured", "托管账号派生密钥已配置", "frontendApiAddress", "前端 API 构建地址", "containerVolumes", "容器卷");
        List<Field> fields = new ArrayList<>(); Map<String, String> sources = new LinkedHashMap<>();
        values.forEach((key, value) -> { fields.add(new Field(key, labels.get(key), value instanceof Boolean ? "boolean" : "string", false, false)); sources.put(key, "DEPLOYMENT"); });
        return new Group("deployment", "部署信息", 0, null, "RESTART", "EFFECTIVE", List.of("MediaNexus-Orchestrator"),
                "deployment", fields, values, values, sources, Map.of());
    }

    private void validateUrl(String value, boolean secretCallback) {
        try {
            URI uri = URI.create(value);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                    || (!secretCallback && (uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getFragment() != null))) throw invalid("服务地址必须为有效的 HTTP(S) 地址；认证回调须在敏感字段中维护");
        } catch (IllegalArgumentException exception) { throw invalid("服务地址格式无效"); }
    }
    private BusinessException invalid(String message) { return new BusinessException(ErrorCode.BAD_REQUEST, message); }
}
