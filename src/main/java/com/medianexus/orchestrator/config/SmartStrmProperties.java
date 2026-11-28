package com.medianexus.orchestrator.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 可选 SmartStrm 后端；默认值统一由 application.yml 提供，启用后在调用边界验证。 */
@Component
@ConfigurationProperties(prefix = "medianexus.smartstrm")
public class SmartStrmProperties {
    private boolean enabled;
    private String baseUrl;
    private String username;
    private String password;
    private Duration timeout;
    private String outputAliasRoot;
    private String permittedOutputRoot;
    private Duration completionTimeout;
    private Duration pollInterval;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String value) { baseUrl = value; }
    public String getUsername() { return username; }
    public void setUsername(String value) { username = value; }
    public String getPassword() { return password; }
    public void setPassword(String value) { password = value; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration value) { timeout = value; }
    public String getOutputAliasRoot() { return outputAliasRoot; }
    public void setOutputAliasRoot(String value) { outputAliasRoot = value; }
    public String getPermittedOutputRoot() { return permittedOutputRoot; }
    public void setPermittedOutputRoot(String value) { permittedOutputRoot = value; }
    public Duration getCompletionTimeout() { return completionTimeout; }
    public void setCompletionTimeout(Duration value) { completionTimeout = value; }
    public Duration getPollInterval() { return pollInterval; }
    public void setPollInterval(Duration value) { pollInterval = value; }
}
