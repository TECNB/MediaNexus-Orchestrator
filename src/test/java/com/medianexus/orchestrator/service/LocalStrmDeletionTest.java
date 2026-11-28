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

    @Test
    void neverReadsVideoAsTextAndRejectsOversizedOrLinkedStrm() throws Exception {
        Path root = temporaryDirectory.resolve("STRM");
        Files.createDirectories(root);
        CloudDrive2Properties properties = new CloudDrive2Properties();
        properties.setStrmPathPrefix(root.toString());
        LocalStrmDeletion deletion = new LocalStrmDeletion(properties);
        Path video = root.resolve("movie.mp4");
        Files.writeString(video, "not a media-source pointer");
        assertThat(deletion.resolveMediaSourcePath(video.toString())).isEqualTo(video.toString());
        Path oversized = root.resolve("oversized.strm");
        Files.writeString(oversized, "/media/movie.mp4\n" + "x".repeat(32_768));
        assertThatThrownBy(() -> deletion.resolveMediaSourcePath(oversized.toString()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("STRM");
        Path link = root.resolve("linked.strm");
        Files.createSymbolicLink(link, video);
        assertThat(deletion.resolveMediaSourcePath(link.toString())).isNull();
        Path real = Files.createDirectories(root.resolve("real"));
        Files.writeString(real.resolve("movie.strm"), "/media/movie.mp4");
        Files.createSymbolicLink(root.resolve("alias"), real);
        assertThat(deletion.resolveMediaSourcePath(root.resolve("alias/movie.strm").toString())).isNull();
    }
    @Test void organizationReadAcceptsOwnedAccountAliasButRejectsOutsideAndLinkedPointers() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("owned")).toRealPath();
        Path real = Files.createDirectories(root.resolve("accounts/media-b/MOVIE"));
        Files.writeString(real.resolve("movie.strm"), "/media/movie.mp4");
        Path alias = Files.createSymbolicLink(root.resolve("accounts/uuid"), real.getParent());
        var properties = new CloudDrive2Properties(); properties.setStrmPathPrefix(root.toString());
        var links = new LocalStrmDeletion(properties);
        assertThat(links.resolveOwnedMediaSourcePath(alias.resolve("MOVIE/movie.strm").toString())).isEqualTo("/media/movie.mp4");
        Path outside = Files.createDirectory(temporaryDirectory.resolve("outside"));
        Files.writeString(outside.resolve("movie.strm"), "/media/other.mp4");
        Path escape = Files.createSymbolicLink(root.resolve("escape"), outside);
        assertThat(links.resolveOwnedMediaSourcePath(escape.resolve("movie.strm").toString())).isNull();
        Path linked = Files.createSymbolicLink(real.resolve("linked.strm"), real.resolve("movie.strm"));
        assertThat(links.resolveOwnedMediaSourcePath(linked.toString())).isNull();
    }
}
