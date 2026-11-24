package com.medianexus.orchestrator.service;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** A separate read-only process bounds FUSE reads; timeout never interrupts a media mutation. */
@Component
public class OrganizationMediaReadability {
    public void requireReadable(String source, long expectedBytes) {
        if (source == null || expectedBytes <= 0) throw new IllegalStateException("MEDIA_READ_EVIDENCE_INVALID");
        Path path = Path.of(source);
        if (!path.isAbsolute() || !path.equals(path.normalize())) throw new IllegalStateException("MEDIA_READ_PATH_INVALID");
        Process probe = null;
        try {
            probe = new ProcessBuilder("sh", "-c",
                    "s=$(stat -c %s -- \"$1\" 2>/dev/null || stat -f %z \"$1\") || exit 2; n=$(head -c 16 -- \"$1\" | wc -c); printf '%s %s' \"$s\" \"$n\"", 
                    "sh", source).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!probe.waitFor(20, TimeUnit.SECONDS)) throw new IllegalStateException("MEDIA_READ_TIMEOUT");
            String[] observed = new String(probe.getInputStream().readNBytes(128), StandardCharsets.UTF_8).trim().split("\\s+");
            if (probe.exitValue() != 0 || observed.length != 2 || Long.parseLong(observed[0]) != expectedBytes
                    || Integer.parseInt(observed[1]) != 16) throw new IllegalStateException("MEDIA_READ_NOT_READY");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MEDIA_READ_INTERRUPTED");
        } catch (java.io.IOException | NumberFormatException unavailable) {
            throw new IllegalStateException("MEDIA_READ_NOT_READY");
        } finally {
            if (probe != null && probe.isAlive()) {
                probe.descendants().forEach(ProcessHandle::destroyForcibly);
                probe.destroyForcibly();
            }
        }
    }
}
