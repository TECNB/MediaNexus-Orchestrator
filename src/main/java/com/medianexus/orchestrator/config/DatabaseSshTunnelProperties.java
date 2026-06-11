package com.medianexus.orchestrator.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 数据库 SSH 隧道配置，可用于开发或数据库仅暴露在服务器本机回环地址的部署方式。
 */
@ConfigurationProperties(prefix = "medianexus.datasource.ssh-tunnel")
public class DatabaseSshTunnelProperties {

    /**
     * 是否在应用启动时创建 SSH 本地端口转发。
     */
    private boolean enabled;

    /**
     * SSH 服务器地址。
     */
    private String host;

    /**
     * SSH 端口。
     */
    private int port;

    /**
     * SSH 用户名。
     */
    private String username;

    /**
     * SSH 密码。
     */
    private String password;

    /**
     * 本地监听地址。
     */
    private String localHost;

    /**
     * 本地监听端口。
     */
    private int localPort;

    /**
     * 从 SSH 服务器视角访问的远端数据库地址。
     */
    private String remoteHost;

    /**
     * 从 SSH 服务器视角访问的远端数据库端口。
     */
    private int remotePort;

    /**
     * 是否启用严格主机密钥检查；默认关闭以降低个人开发环境启动成本。
     */
    private boolean strictHostKeyChecking;

    /**
     * SSH 连接超时时间。
     */
    private Duration connectTimeout;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getLocalHost() {
        return localHost;
    }

    public void setLocalHost(String localHost) {
        this.localHost = localHost;
    }

    public int getLocalPort() {
        return localPort;
    }

    public void setLocalPort(int localPort) {
        this.localPort = localPort;
    }

    public String getRemoteHost() {
        return remoteHost;
    }

    public void setRemoteHost(String remoteHost) {
        this.remoteHost = remoteHost;
    }

    public int getRemotePort() {
        return remotePort;
    }

    public void setRemotePort(int remotePort) {
        this.remotePort = remotePort;
    }

    public boolean isStrictHostKeyChecking() {
        return strictHostKeyChecking;
    }

    public void setStrictHostKeyChecking(boolean strictHostKeyChecking) {
        this.strictHostKeyChecking = strictHostKeyChecking;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }
}
