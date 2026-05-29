package com.medianexus.orchestrator.config;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class DatabaseSshTunnelLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSshTunnelLifecycle.class);

    private final DatabaseSshTunnelProperties properties;

    private Session session;

    private volatile boolean running;

    public DatabaseSshTunnelLifecycle(DatabaseSshTunnelProperties properties) {
        this.properties = properties;
    }

    @Override
    public void start() {
        if (!properties.isEnabled()) {
            return;
        }

        validateProperties();

        try {
            JSch jsch = new JSch();
            session = jsch.getSession(properties.getUsername(), properties.getHost(), properties.getPort());
            session.setPassword(properties.getPassword());
            session.setConfig(createSessionConfig());
            session.connect((int) properties.getConnectTimeout().toMillis());

            int assignedPort = session.setPortForwardingL(
                    properties.getLocalHost(),
                    properties.getLocalPort(),
                    properties.getRemoteHost(),
                    properties.getRemotePort()
            );
            running = true;
            log.info(
                    "Database SSH tunnel started: {}:{} -> {}:{} through {}@{}:{}",
                    properties.getLocalHost(),
                    assignedPort,
                    properties.getRemoteHost(),
                    properties.getRemotePort(),
                    properties.getUsername(),
                    properties.getHost(),
                    properties.getPort()
            );
        } catch (JSchException exception) {
            throw new IllegalStateException("Failed to start database SSH tunnel", exception);
        }
    }

    @Override
    public void stop() {
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
        running = false;
        log.info("Database SSH tunnel stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MIN_VALUE;
    }

    private Properties createSessionConfig() {
        Properties config = new Properties();
        config.put(
                "StrictHostKeyChecking",
                properties.isStrictHostKeyChecking() ? "yes" : "no"
        );
        return config;
    }

    private void validateProperties() {
        if (!StringUtils.hasText(properties.getHost())) {
            throw new IllegalStateException("MEDIANEXUS_DB_SSH_HOST is required when SSH tunnel is enabled");
        }
        if (!StringUtils.hasText(properties.getUsername())) {
            throw new IllegalStateException("MEDIANEXUS_DB_SSH_USERNAME is required when SSH tunnel is enabled");
        }
        if (!StringUtils.hasText(properties.getPassword())) {
            throw new IllegalStateException("MEDIANEXUS_DB_SSH_PASSWORD is required when SSH tunnel is enabled");
        }
    }
}
