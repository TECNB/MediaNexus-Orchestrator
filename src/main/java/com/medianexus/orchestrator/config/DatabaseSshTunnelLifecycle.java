package com.medianexus.orchestrator.config;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class DatabaseSshTunnelLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSshTunnelLifecycle.class);
    private static final Duration LOCAL_PORT_CHECK_INTERVAL = Duration.ofMillis(250);
    private static final Duration LOCAL_PORT_QUICK_CHECK_TIMEOUT = Duration.ofSeconds(1);
    private static final int SSH_SERVER_ALIVE_INTERVAL_MILLIS = 30_000;
    private static final int SSH_SERVER_ALIVE_COUNT_MAX = 3;

    private final DatabaseSshTunnelProperties properties;

    private Session session;

    private volatile boolean running;

    public DatabaseSshTunnelLifecycle(DatabaseSshTunnelProperties properties) {
        this.properties = properties;
    }

    @Override
    public synchronized void start() {
        if (!properties.isEnabled()) {
            return;
        }
        if (isTunnelHealthy()) {
            running = true;
            return;
        }

        validateProperties();

        try {
            stopSession();
            JSch jsch = new JSch();
            session = jsch.getSession(properties.getUsername(), properties.getHost(), properties.getPort());
            session.setPassword(properties.getPassword());
            session.setConfig(createSessionConfig());
            session.setTimeout((int) properties.getConnectTimeout().toMillis());
            session.setServerAliveInterval(SSH_SERVER_ALIVE_INTERVAL_MILLIS);
            session.setServerAliveCountMax(SSH_SERVER_ALIVE_COUNT_MAX);
            session.connect((int) properties.getConnectTimeout().toMillis());

            int assignedPort = session.setPortForwardingL(
                    properties.getLocalHost(),
                    properties.getLocalPort(),
                    properties.getRemoteHost(),
                    properties.getRemotePort()
            );
            waitForLocalPort();
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
            stopSession();
            throw new IllegalStateException("Failed to start database SSH tunnel", exception);
        } catch (RuntimeException exception) {
            stopSession();
            throw exception;
        }
    }

    @Override
    public synchronized void stop() {
        stopSession();
        running = false;
        log.info("Database SSH tunnel stopped");
    }

    @Override
    public boolean isRunning() {
        return running && session != null && session.isConnected();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MIN_VALUE;
    }

    public synchronized void ensureRunning() {
        if (!properties.isEnabled()) {
            return;
        }
        if (isTunnelHealthy()) {
            running = true;
            return;
        }
        log.warn("Database SSH tunnel is not ready; attempting to restart it");
        start();
    }

    private Properties createSessionConfig() {
        Properties config = new Properties();
        config.put(
                "StrictHostKeyChecking",
                properties.isStrictHostKeyChecking() ? "yes" : "no"
        );
        return config;
    }

    private boolean isTunnelHealthy() {
        return session != null
                && session.isConnected()
                && isLocalPortOpen(LOCAL_PORT_QUICK_CHECK_TIMEOUT);
    }

    private void waitForLocalPort() {
        long deadline = System.nanoTime() + properties.getConnectTimeout().toNanos();
        while (System.nanoTime() <= deadline) {
            if (isLocalPortOpen(LOCAL_PORT_QUICK_CHECK_TIMEOUT)) {
                return;
            }
            sleep(LOCAL_PORT_CHECK_INTERVAL);
        }
        throw new IllegalStateException(
                "Database SSH tunnel local port is not reachable: "
                        + properties.getLocalHost() + ":" + properties.getLocalPort()
        );
    }

    private boolean isLocalPortOpen(Duration timeout) {
        try (Socket socket = new Socket()) {
            socket.connect(
                    new InetSocketAddress(properties.getLocalHost(), properties.getLocalPort()),
                    (int) timeout.toMillis()
            );
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private void stopSession() {
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
        session = null;
        running = false;
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for database SSH tunnel", exception);
        }
    }

    private void validateProperties() {
        if (!StringUtils.hasText(properties.getHost())) {
            throw new IllegalStateException("MEDIANEXUS_DB_SSH_HOST is required when SSH tunnel is enabled");
        }
        if (!isValidPort(properties.getPort())) {
            throw new IllegalStateException("MEDIANEXUS_DB_SSH_PORT must be between 1 and 65535 when SSH tunnel is enabled");
        }
        if (!StringUtils.hasText(properties.getUsername())) {
            throw new IllegalStateException("MEDIANEXUS_DB_SSH_USERNAME is required when SSH tunnel is enabled");
        }
        if (!StringUtils.hasText(properties.getPassword())) {
            throw new IllegalStateException("MEDIANEXUS_DB_SSH_PASSWORD is required when SSH tunnel is enabled");
        }
        if (!StringUtils.hasText(properties.getLocalHost())) {
            throw new IllegalStateException("MEDIANEXUS_DB_SSH_LOCAL_HOST is required when SSH tunnel is enabled");
        }
        if (!isValidPort(properties.getLocalPort())) {
            throw new IllegalStateException("MEDIANEXUS_DB_LOCAL_PORT must be between 1 and 65535 when SSH tunnel is enabled");
        }
        if (!StringUtils.hasText(properties.getRemoteHost())) {
            throw new IllegalStateException("MEDIANEXUS_DB_SSH_REMOTE_HOST is required when SSH tunnel is enabled");
        }
        if (!isValidPort(properties.getRemotePort())) {
            throw new IllegalStateException("MEDIANEXUS_DB_SSH_REMOTE_PORT must be between 1 and 65535 when SSH tunnel is enabled");
        }
        if (properties.getConnectTimeout() == null
                || properties.getConnectTimeout().isZero()
                || properties.getConnectTimeout().isNegative()) {
            throw new IllegalStateException("MEDIANEXUS_DB_SSH_CONNECT_TIMEOUT must be positive when SSH tunnel is enabled");
        }
    }

    private boolean isValidPort(int port) {
        return port > 0 && port <= 65535;
    }
}
