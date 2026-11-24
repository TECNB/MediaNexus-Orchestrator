package com.medianexus.orchestrator.service;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class OrganizationMediaReadabilityTest {
    @TempDir Path root;
    @Test void requiresExactBytesAndSixteenReadableBytes() throws Exception {
        var probe = new OrganizationMediaReadability();
        Path file = root.resolve("sample ' name.mkv");
        Files.write(file, new byte[64]);
        probe.requireReadable(file.toString(), 64);
        assertThatThrownBy(() -> probe.requireReadable(file.toString(), 65)).hasMessage("MEDIA_READ_NOT_READY");
        Files.write(file, new byte[4]);
        assertThatThrownBy(() -> probe.requireReadable(file.toString(), 4)).hasMessage("MEDIA_READ_NOT_READY");
        Files.write(file, new byte[0]);
        assertThatThrownBy(() -> probe.requireReadable(file.toString(), 64)).hasMessage("MEDIA_READ_NOT_READY");
        assertThatThrownBy(() -> probe.requireReadable(root.resolve("absent.mkv").toString(), 64)).hasMessage("MEDIA_READ_NOT_READY");
    }
}
