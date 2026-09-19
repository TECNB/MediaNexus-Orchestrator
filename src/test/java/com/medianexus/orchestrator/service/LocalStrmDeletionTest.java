package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.medianexus.orchestrator.config.CloudDrive2Properties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalStrmDeletionTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void deletesOnlyPathsBelowTheConfiguredStrmRoot() throws Exception {
        Path root = temporaryDirectory.resolve("STRM");
        Path season = root.resolve("TV/Series/Season 1");
        Files.createDirectories(season);
        Files.writeString(season.resolve("Episode.strm"), "video");
        CloudDrive2Properties properties = new CloudDrive2Properties();
        properties.setStrmPathPrefix(root.toString());
        LocalStrmDeletion deletion = new LocalStrmDeletion(properties);

        deletion.delete(List.of(season.toString()));

        assertThat(season).doesNotExist();
        assertThat(root).exists();
        assertThatThrownBy(() -> deletion.delete(List.of(root.toString())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolvesRemoteSourceFromLocalStrmFile() throws Exception {
        Path root = temporaryDirectory.resolve("STRM");
        Path strm = root.resolve("Anime/Title/movie.strm");
        Files.createDirectories(strm.getParent());
        Files.writeString(strm, "\nhttp://example.test/video.mp4\n");
        CloudDrive2Properties properties = new CloudDrive2Properties();
        properties.setStrmPathPrefix(root.toString());
        LocalStrmDeletion deletion = new LocalStrmDeletion(properties);

        assertThat(deletion.resolveMediaSourcePath(strm.toString()))
                .isEqualTo("http://example.test/video.mp4");
        assertThat(deletion.resolveMediaSourcePath(root.resolve("missing.strm").toString())).isNull();
    }
}
