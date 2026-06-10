package com.medianexus.orchestrator.config;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
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
    private static final Duration SSH_PROCESS_STOP_TIMEOUT = Duration.ofSeconds(5);

    private final DatabaseSshTunnelProperties properties;

    private Process sshProcess;
    private Path askpassScript;

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
            startOpenSshTunnel();
        } catch (RuntimeException exception) {
            stopSshProcess();
            throw exception;
        }
    }

    @Override
    public synchronized void stop() {
        stopSshProcess();
        running = false;
        log.info("Database SSH tunnel stopped");
    }

    @Override
    public boolean isRunning() {
        return running && isTunnelHealthy();
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

    private boolean isTunnelHealthy() {
        return isOpenSshProcessRunning() && isLocalPortOpen(LOCAL_PORT_QUICK_CHECK_TIMEOUT);
    }

    private boolean isOpenSshProcessRunning() {
        return sshProcess != null && sshProcess.isAlive();
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

    private void startOpenSshTunnel() {
        try {
            stopSshProcess();
            askpassScript = createAskpassScript();
            ProcessBuilder processBuilder = new ProcessBuilder(createOpenSshCommand());
            processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
            processBuilder.environment().put("SSH_ASKPASS", askpassScript.toString());
            processBuilder.environment().put("SSH_ASKPASS_REQUIRE", "force");
            processBuilder.environment().put("DISPLAY", "medianexus");
            processBuilder.environment().put("MEDIANEXUS_DB_SSH_PASSWORD", properties.getPassword());
            sshProcess = processBuilder.start();

            waitForLocalPort();
            if (!sshProcess.isAlive()) {
                throw new IllegalStateException("OpenSSH tunnel process exited before local port became reachable");
            }
            running = true;
            log.info(
                    "Database SSH tunnel started with OpenSSH: {}:{} -> {}:{} through {}@{}:{}",
                    properties.getLocalHost(),
                    properties.getLocalPort(),
                    properties.getRemoteHost(),
                    properties.getRemotePort(),
                    properties.getUsername(),
                    properties.getHost(),
                    properties.getPort()
            );
        } catch (IOException | RuntimeException exception) {
            stopSshProcess();
            throw new IllegalStateException("Failed to start database SSH tunnel", exception);
        }
    }

    private List<String> createOpenSshCommand() {
        return List.of(
                "ssh",
                "-o", "ExitOnForwardFailure=yes",
                "-o", "BatchMode=no",
                "-o", "PreferredAuthentications=password",
                "-o", "PubkeyAuthentication=no",
                "-o", "NumberOfPasswordPrompts=1",
                "-o", "StrictHostKeyChecking=" + (properties.isStrictHostKeyChecking() ? "yes" : "no"),
                "-o", "ServerAliveInterval=" + (SSH_SERVER_ALIVE_INTERVAL_MILLIS / 1000),
                "-o", "ServerAliveCountMax=" + SSH_SERVER_ALIVE_COUNT_MAX,
                "-N",
                "-L", createOpenSshForwardSpec(),
                "-p", Integer.toString(properties.getPort()),
                properties.getUsername() + "@" + properties.getHost()
        );
    }

    private String createOpenSshForwardSpec() {
        return properties.getLocalHost()
                + ":" + properties.getLocalPort()
                + ":" + properties.getRemoteHost()
                + ":" + properties.getRemotePort();
    }

    private Path createAskpassScript() throws IOException {
        Path script = Files.createTempFile("medianexus-ssh-askpass-", ".sh");
        Files.writeString(
                script,
                "#!/bin/sh\nprintf '%s\\n' \"$MEDIANEXUS_DB_SSH_PASSWORD\"\n",
                StandardCharsets.UTF_8
        );
        setOwnerOnlyExecutable(script);
        return script;
    }

    private void setOwnerOnlyExecutable(Path script) throws IOException {
        try {
            Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rwx------");
            Files.setPosixFilePermissions(script, permissions);
        } catch (UnsupportedOperationException exception) {
            script.toFile().setReadable(false, false);
            script.toFile().setWritable(false, false);
            script.toFile().setExecutable(false, false);
            script.toFile().setReadable(true, true);
            script.toFile().setWritable(true, true);
            script.toFile().setExecutable(true, true);
        }
    }

    private void stopSshProcess() {
        if (sshProcess != null) {
            sshProcess.destroy();
            try {
                if (!sshProcess.waitFor(SSH_PROCESS_STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    sshProcess.destroyForcibly();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                sshProcess.destroyForcibly();
            }
            sshProcess = null;
        }
        if (askpassScript != null) {
            try {
                Files.deleteIfExists(askpassScript);
            } catch (IOException exception) {
                log.warn("Failed to delete temporary SSH askpass helper: {}", askpassScript);
            }
            askpassScript = null;
        }
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
