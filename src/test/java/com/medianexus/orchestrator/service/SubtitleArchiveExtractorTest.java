package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.config.SubtitleUploadProperties;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

class SubtitleArchiveExtractorTest {
    static final byte[] SRT = "1\n00:00:01,000 --> 00:00:02,000\n字幕\n".getBytes(StandardCharsets.UTF_8);
    private final SubtitleUploadProperties properties = new SubtitleUploadProperties();
    private final SubtitleArchiveExtractor extractor = new SubtitleArchiveExtractor(properties);
    @TempDir Path temporary;

    @Test
    void identifiesContentsInsteadOfUploadExtensionAndRejectsHtml() {
        var extracted = extractor.extract(upload("download.bin", SRT));
        try {
            assertThat(extracted.entries().get(0).extension()).isEqualTo("srt");
            assertThat(extracted.sourceFileName()).isEqualTo("download.bin");
            assertThat(extracted.entries().get(0).sha256()).hasSize(64);
        } finally { extractor.deleteQuietly(extracted.workDir()); }
        assertThatThrownBy(() -> extractor.extract(upload("fake.srt", "<html>error</html>".getBytes())))
                .isInstanceOf(BusinessException.class).hasMessageContaining("无效");
    }

    @Test
    void decodesGbkAndRecursesZipWithSharedBudgetAndSkipsFonts() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("简体/inner.zip", zip(Map.of("01.srt", SRT), StandardCharsets.UTF_8));
        entries.put("字体.rar", new byte[]{'R', 'a', 'r', '!', 0x1a, 7, 0});
        entries.put("readme.txt", "readme".getBytes());
        var extracted = extractor.extract(upload("package.data", zip(entries, Charset.forName("GB18030"))));
        try {
            assertThat(extracted.entries()).extracting(SubtitleArchiveExtractor.ExtractedSubtitleEntry::originalPath)
                    .containsExactly("简体/inner.zip/01.srt");
            assertThat(extracted.ignoredEntries()).contains("字体.rar", "readme.txt");
        } finally { extractor.deleteQuietly(extracted.workDir()); }
        properties.setMaxEntryCount(1);
        assertThatThrownBy(() -> extractor.extract(upload("package.zip", zip(entries, StandardCharsets.UTF_8))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("数量超过");
    }

    @Test
    void rejectsTraversalLinksEncryptionAndDepth() throws Exception {
        for (String name : new String[]{"../escape.srt", "/absolute.srt", "C:/drive.srt", "a/../escape.srt"}) {
            assertThatThrownBy(() -> extractor.extract(upload("bad.zip", zip(Map.of(name, SRT), StandardCharsets.UTF_8))))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("路径");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var zip = new org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(bytes)) {
            var link = new org.apache.commons.compress.archivers.zip.ZipArchiveEntry("link.srt");
            link.setUnixMode(0120777);
            zip.putArchiveEntry(link);
            zip.write("outside".getBytes());
            zip.closeArchiveEntry();
        }
        byte[] linkZip = bytes.toByteArray();
        assertThatThrownBy(() -> extractor.extract(upload("link.zip", linkZip)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("链接");
        byte[] encrypted = zip(Map.of("01.srt", SRT), StandardCharsets.UTF_8);
        for (int index = 0; index < encrypted.length - 9; index++) {
            if (encrypted[index] == 'P' && encrypted[index + 1] == 'K') {
                if (encrypted[index + 2] == 3 && encrypted[index + 3] == 4) encrypted[index + 6] |= 1;
                if (encrypted[index + 2] == 1 && encrypted[index + 3] == 2) encrypted[index + 8] |= 1;
            }
        }
        assertThatThrownBy(() -> extractor.extract(upload("encrypted.zip", encrypted)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("加密");
        byte[] nested = zip(Map.of("01.srt", SRT), StandardCharsets.UTF_8);
        for (int depth = 0; depth < 3; depth++) nested = zip(Map.of("nested.zip", nested), StandardCharsets.UTF_8);
        byte[] tooDeep = nested;
        assertThatThrownBy(() -> extractor.extract(upload("deep.zip", tooDeep)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("层数");
    }

    @Test
    void enforcesTotalSizeAcrossNestedPackages() throws Exception {
        properties.setMaxTotalExtractedSize(DataSize.ofBytes(100));
        byte[] nested = zip(Map.of("inner.zip", zip(Map.of("01.srt", SRT), StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        assertThatThrownBy(() -> extractor.extract(upload("large.zip", nested)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("总大小");
    }

    @Test
    void extractsReal7zInsideZipAndRejects7zTraversalBeforeDiskWrite() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of("/usr/bin/bsdtar")), "需要 bsdtar");
        Files.write(temporary.resolve("01.srt"), SRT);
        Path seven = temporary.resolve("subtitles.7z");
        Process create = new ProcessBuilder("/usr/bin/bsdtar", "--format=7zip", "-cf", seven.toString(), "-C", temporary.toString(), "01.srt")
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        assertThat(create.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(create.exitValue()).isZero();
        var extracted = extractor.extract(upload("outer.zip", zip(Map.of("subtitles.7z", Files.readAllBytes(seven)), StandardCharsets.UTF_8)));
        try {
            assertThat(extracted.entries()).extracting(SubtitleArchiveExtractor.ExtractedSubtitleEntry::originalPath)
                    .containsExactly("subtitles.7z/01.srt");
            assertThat(Files.readAllBytes(extracted.entries().get(0).localPath())).isEqualTo(SRT);
        } finally { extractor.deleteQuietly(extracted.workDir()); }
        Path unsafe = temporary.resolve("unsafe.7z");
        Path unsafeTar = temporary.resolve("unsafe.tar");
        try (var output = new org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(Files.newOutputStream(unsafeTar))) {
            var entry = new org.apache.commons.compress.archivers.tar.TarArchiveEntry("../escape.srt");
            entry.setSize(SRT.length);
            output.putArchiveEntry(entry);
            output.write(SRT);
            output.closeArchiveEntry();
        }
        Process malicious = new ProcessBuilder("/usr/bin/bsdtar", "-c", "-P", "--format=7zip", "-f", unsafe.toString(), "@" + unsafeTar)
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        assertThat(malicious.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(malicious.exitValue()).isZero();
        assertThatThrownBy(() -> extractor.extract(upload("unsafe.7z", Files.readAllBytes(unsafe))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("路径");
    }

    @Test
    void extractsRealStoredRarInsideZip() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of("/usr/bin/bsdtar")), "需要 bsdtar");
        byte[] name = "01.srt".getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        archive.write(new byte[]{'R', 'a', 'r', '!', 0x1a, 7, 0});
        java.nio.ByteBuffer main = java.nio.ByteBuffer.allocate(11).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        main.put((byte) 0x73).putShort((short) 0).putShort((short) 13).putShort((short) 0).putInt(0);
        rarHeader(archive, main.array());
        var crc = new java.util.zip.CRC32(); crc.update(SRT);
        java.nio.ByteBuffer file = java.nio.ByteBuffer.allocate(30 + name.length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        file.put((byte) 0x74).putShort((short) 0x8000).putShort((short) (32 + name.length));
        file.putInt(SRT.length).putInt(SRT.length).put((byte) 3).putInt((int) crc.getValue()).putInt(0);
        file.put((byte) 20).put((byte) 0x30).putShort((short) name.length).putInt(0100644).put(name);
        rarHeader(archive, file.array());
        archive.write(SRT);
        rarHeader(archive, new byte[]{0x7b, 0, 0, 7, 0});
        var extracted = extractor.extract(upload("outer.zip", zip(Map.of("subtitles.rar", archive.toByteArray()), StandardCharsets.UTF_8)));
        try {
            assertThat(extracted.entries()).extracting(SubtitleArchiveExtractor.ExtractedSubtitleEntry::originalPath)
                    .containsExactly("subtitles.rar/01.srt");
            assertThat(Files.readAllBytes(extracted.entries().get(0).localPath())).isEqualTo(SRT);
        } finally { extractor.deleteQuietly(extracted.workDir()); }
    }

    private void rarHeader(ByteArrayOutputStream archive, byte[] header) throws Exception {
        var crc = new java.util.zip.CRC32(); crc.update(header);
        archive.write((int) crc.getValue() & 0xff);
        archive.write((int) (crc.getValue() >> 8) & 0xff);
        archive.write(header);
    }

    static MockMultipartFile upload(String name, byte[] content) {
        return new MockMultipartFile("file", name, "application/octet-stream", content);
    }

    static byte[] zip(Map<String, byte[]> entries, Charset charset) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, charset)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
