package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.config.SubtitleUploadProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
public class SubtitleArchiveExtractor {

    private static final int BUFFER_SIZE = 8192;
    private static final Pattern WINDOWS_DRIVE_PATTERN = Pattern.compile("^[A-Za-z]:.*");
    private static final Set<String> NESTED_ARCHIVE_EXTENSIONS = Set.of("zip", "rar", "7z");

    private final SubtitleUploadProperties properties;
    private final long operationDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);

    public SubtitleArchiveExtractor(SubtitleUploadProperties properties) {
        this.properties = properties;
    }

    public ExtractedSubtitlePackage extract(MultipartFile file) {
        return extract(stage(file));
    }

    public StagedSubtitleUpload stage(MultipartFile file) {
        return new SubtitleArchiveExtractor(properties.snapshot()).stageCaptured(file);
    }

    private StagedSubtitleUpload stageCaptured(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw badRequest("请上传字幕文件");
        }
        String sourceFileName = safeOriginalFilename(file.getOriginalFilename());
        Path workDir = createWorkDir();
        Path sourcePath = workDir.resolve("source-upload.bin");
        try {
            CopyResult sourceCopy = copyWithLimit(
                    file.getInputStream(),
                    sourcePath,
                    properties.getMaxUploadSize().toBytes(),
                    "上传文件超过大小限制"
            );
            return new StagedSubtitleUpload(
                    workDir,
                    sourcePath,
                    sourceFileName,
                    sourceCopy.size(),
                    sourceCopy.sha256(),
                    properties
            );
        } catch (BusinessException exception) {
            deleteQuietly(workDir);
            throw exception;
        } catch (IOException exception) {
            deleteQuietly(workDir);
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "上传文件读取失败",
                    HttpStatus.BAD_REQUEST
            );
        }
    }

    public ExtractedSubtitlePackage extract(StagedSubtitleUpload stagedUpload) {
        SubtitleUploadProperties limits = stagedUpload.limits() == null ? properties.snapshot() : stagedUpload.limits();
        return new SubtitleArchiveExtractor(limits).extractCaptured(stagedUpload);
    }

    /** 仅按内容识别压缩格式；所有层级共享预算，条目只写入生成的本地文件名。 */
    private ExtractedSubtitlePackage extractCaptured(StagedSubtitleUpload staged) {
        Budget budget = new Budget();
        try {
            String kind = archiveKind(staged.sourcePath());
            if (kind != null) {
                unpack(staged.sourcePath(), staged.workDir(), "", 0, kind, budget);
            } else {
                accept(staged.sourcePath(), staged.sourceFileName(), staged.workDir(), budget);
            }
            if (budget.accepted.isEmpty()) {
                throw badRequest("压缩包中没有可接受的字幕文件");
            }
            return new ExtractedSubtitlePackage(staged.workDir(), staged.sourceFileName(), kind != null,
                    staged.sourceSize(), staged.sourceSha256(), List.copyOf(budget.accepted), List.copyOf(budget.ignored));
        } catch (IOException | IllegalArgumentException exception) {
            deleteQuietly(staged.workDir());
            throw badRequest("字幕或压缩包无法读取: " + exception.getMessage());
        } catch (RuntimeException exception) {
            deleteQuietly(staged.workDir());
            throw exception;
        }
    }

    private void unpack(Path source, Path workDir, String prefix, int depth, String kind, Budget budget)
            throws IOException {
        if (depth > 2) {
            throw badRequest("嵌套压缩包超过最大层数 2");
        }
        if ("zip".equals(kind)) {
            // UTF-8 标志仍优先；非 UTF-8 文件名使用 GB18030（兼容常见 GBK ZIP）。
            try (ZipFile zip = new ZipFile(source.toFile(), "GB18030", true)) {
                Enumeration<ZipArchiveEntry> entries = zip.getEntries();
                while (entries.hasMoreElements()) {
                    ZipArchiveEntry entry = entries.nextElement();
                    validateEntryName(entry.getName());
                    if (entry.isUnixSymlink() || entry.getGeneralPurposeBit().usesEncryption()) {
                        throw badRequest("压缩包含链接或加密条目: " + entry.getName());
                    }
                    budget.count();
                    if (entry.isDirectory()) continue;
                    if (entry.getSize() < 0 || (entry.getCompressedSize() <= 0 && entry.getSize() > 0)
                            || (entry.getCompressedSize() > 0 && (double) entry.getSize() / entry.getCompressedSize()
                            > properties.getMaxCompressionRatio())) {
                        throw badRequest("压缩包条目大小或压缩比异常: " + entry.getName());
                    }
                    try (var input = new java.util.zip.CheckedInputStream(zip.getInputStream(entry), new java.util.zip.CRC32())) {
                        inspectEntry(input, entry.getSize(), prefix + entry.getName(), workDir, depth, budget);
                        // 已读取的字幕/嵌套包必须匹配 ZIP CRC；跳过附件不额外强制读取。
                        if (input.getChecksum().getValue() != entry.getCrc()
                                && (allowedExtensions().contains(extension(entry.getName()))
                                || NESTED_ARCHIVE_EXTENSIONS.contains(extension(entry.getName()))
                                && !budget.ignored.contains(prefix + entry.getName()))) {
                            throw badRequest("ZIP 条目校验失败: " + entry.getName());
                        }
                    }
                }
            }
            return;
        }
        // bsdtar 将 RAR/7z 转成 stdout TAR；不让外部工具根据不可信路径在磁盘解压。
        Process process;
        try {
            process = new ProcessBuilder("bsdtar", "-c", "-P", "--format=pax", "-f", "-", "@" + source.toAbsolutePath())
                    .start();
        } catch (IOException exception) {
            throw badRequest("RAR/7z 需要安装 bsdtar 并放入 PATH；当前不可用");
        }
        java.util.concurrent.atomic.AtomicBoolean timedOut = new java.util.concurrent.atomic.AtomicBoolean();
        Thread watchdog = new Thread(() -> {
            try {
                if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                    timedOut.set(true);
                    process.destroyForcibly();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }, "subtitle-archive-timeout");
        watchdog.setDaemon(true);
        watchdog.start();
        Thread errors = new Thread(() -> {
            try (InputStream input = process.getErrorStream()) {
                byte[] discard = new byte[4096];
                while (input.read(discard) >= 0) { /* drain without retaining archive-controlled diagnostics */ }
            } catch (IOException ignored) { }
        }, "subtitle-archive-stderr");
        errors.setDaemon(true);
        errors.start();
        try (var tar = new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(process.getInputStream())) {
            org.apache.commons.compress.archivers.tar.TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                validateEntryName(entry.getName());
                budget.count();
                if (!entry.isDirectory() && !entry.isFile() || entry.isSymbolicLink() || entry.isLink()) {
                    throw badRequest("压缩包含链接或特殊条目: " + entry.getName());
                }
                if (entry.isDirectory()) continue;
                inspectEntry(new java.io.FilterInputStream(tar) {
                    @Override public void close() { }
                }, entry.getSize(), prefix + entry.getName(), workDir, depth, budget);
            }
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0 || timedOut.get()) {
                throw badRequest("RAR/7z 解压失败、加密、不受支持或超过 30 秒限制");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw badRequest("压缩包解压已中断");
        } finally {
            process.destroyForcibly();
            watchdog.interrupt();
        }
    }

    private void inspectEntry(InputStream input, long size, String name, Path workDir, int depth, Budget budget)
            throws IOException {
        String ext = extension(name);
        if (size < 0) throw badRequest("压缩包条目大小未知: " + name);
        budget.reserve(size);
        String lower = name.toLowerCase(Locale.ROOT);
        if (NESTED_ARCHIVE_EXTENSIONS.contains(ext)
                && (lower.matches(".*(?:fonts?|字体|字體|attachments?|附件).*"))) {
            budget.ignored.add(name);
            return;
        }
        Path local = Files.createTempFile(workDir, "entry-", ".bin");
        CopyResult copied = copyWithLimit(input, local, Math.min(size, properties.getMaxTotalExtractedSize().toBytes()),
                "压缩包条目超过大小限制: " + name);
        if (copied.size() != size) throw badRequest("压缩包条目大小不符: " + name);
        String nestedKind = archiveKind(local);
        if (nestedKind != null) {
            unpack(local, workDir, name + "/", depth + 1, nestedKind, budget);
            Files.deleteIfExists(local);
        } else if (allowedExtensions().contains(ext) || !subtitleKind(local).isEmpty()) {
            accept(local, name, workDir, budget);
        } else if (NESTED_ARCHIVE_EXTENSIONS.contains(ext)) {
            throw badRequest("压缩包内容与格式不符: " + name);
        } else {
            budget.ignored.add(name);
            Files.deleteIfExists(local);
        }
    }

    private void accept(Path source, String name, Path workDir, Budget budget) throws IOException {
        String ext = subtitleKind(source);
        if (!allowedExtensions().contains(ext)) throw badRequest("不支持或无效的字幕内容: " + name);
        long size = Files.size(source);
        if (size > perSubtitleLimit(ext)) throw badRequest("字幕文件超过大小限制: " + name);
        Path local = Files.createTempFile(workDir, "subtitle-", "." + ext);
        CopyResult copied = copyWithLimit(Files.newInputStream(source), local, perSubtitleLimit(ext), "字幕超过大小限制");
        Files.deleteIfExists(source);
        budget.accepted.add(new ExtractedSubtitleEntry(name, pathName(name), ext, copied.size(), copied.sha256(), local));
    }

    private String subtitleKind(Path path) throws IOException {
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path)) { bytes = input.readNBytes(65536); }
        if (bytes.length >= 2 && bytes[0] == 'P' && bytes[1] == 'G') return "sup";
        String text;
        if (bytes.length >= 2 && ((bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe)
                || (bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff))) {
            text = new String(bytes, java.nio.charset.StandardCharsets.UTF_16);
        } else {
            text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("<html") || lower.contains("<!doctype html")) return "";
        if (lower.contains("[script info]") && lower.contains("[events]") && lower.contains("dialogue:")) {
            return lower.contains("[v4 styles]") ? "ssa" : "ass";
        }
        if (text.stripLeading().replace("\uFEFF", "").startsWith("WEBVTT") && text.contains("-->")) return "vtt";
        if (Pattern.compile("(?m)\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{3}\\s*-->\\s*\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{3}")
                .matcher(text).find()) return "srt";
        return "";
    }

    private String archiveKind(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) { return archiveKind(input.readNBytes(8)); }
    }

    private String archiveKind(byte[] bytes) {
        if (bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K'
                && (bytes[2] == 3 && bytes[3] == 4 || bytes[2] == 5 && bytes[3] == 6)) return "zip";
        if (bytes.length >= 7 && bytes[0] == 'R' && bytes[1] == 'a' && bytes[2] == 'r'
                && bytes[3] == '!' && bytes[4] == 0x1a && bytes[5] == 7) return "rar";
        if (bytes.length >= 6 && bytes[0] == '7' && bytes[1] == 'z' && bytes[2] == (byte) 0xbc
                && bytes[3] == (byte) 0xaf && bytes[4] == 0x27 && bytes[5] == 0x1c) return "7z";
        return null;
    }

    private void validateEntryName(String name) {
        if (!StringUtils.hasText(name) || name.chars().anyMatch(Character::isISOControl)) {
            throw badRequest("压缩包中存在非法路径");
        }
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("/") || WINDOWS_DRIVE_PATTERN.matcher(normalized).matches()
                || Arrays.asList(normalized.split("/")).contains("..")) {
            throw badRequest("压缩包中存在绝对路径或路径穿越: " + name);
        }
    }

    private class Budget {
        private int count;
        private long bytes;
        private final List<ExtractedSubtitleEntry> accepted = new ArrayList<>();
        private final List<String> ignored = new ArrayList<>();

        void count() {
            if (System.nanoTime() > operationDeadline) throw badRequest("压缩包解析超过 60 秒限制");
            if (++count > properties.getMaxEntryCount()) throw badRequest("压缩包条目总数量超过限制");
        }

        void reserve(long size) {
            if (size > properties.getMaxTotalExtractedSize().toBytes() - bytes) {
                throw badRequest("压缩包解压总大小超过限制");
            }
            bytes += size;
        }
    }

    private CopyResult copyWithLimit(InputStream inputStream, Path target, long maxBytes, String limitMessage)
            throws IOException {
        MessageDigest digest = sha256Digest();
        long total = 0L;
        try (
                DigestInputStream digestInputStream = new DigestInputStream(inputStream, digest);
                OutputStream outputStream = Files.newOutputStream(target)
        ) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = digestInputStream.read(buffer)) >= 0) {
                if (System.nanoTime() > operationDeadline) throw badRequest("字幕文件解析超过 60 秒限制");
                total += read;
                if (total > maxBytes) {
                    throw badRequest(limitMessage);
                }
                outputStream.write(buffer, 0, read);
            }
        }
        return new CopyResult(total, HexFormat.of().formatHex(digest.digest()));
    }

    private List<String> allowedExtensions() {
        return properties.getAllowedExtensions().stream()
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(value -> value.startsWith(".") ? value.substring(1) : value)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private long perSubtitleLimit(String extension) {
        if ("sup".equals(extension)) {
            return properties.getMaxSupSize().toBytes();
        }
        return properties.getMaxTextSubtitleSize().toBytes();
    }

    private Path createWorkDir() {
        try {
            return Files.createTempDirectory("medianexus-subtitles-");
        } catch (IOException exception) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "字幕临时目录创建失败",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    public void deleteQuietly(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.comparingInt(Path::getNameCount).reversed())
                    .forEach(current -> {
                        try {
                            Files.deleteIfExists(current);
                        } catch (IOException ignored) {
                            // best-effort cleanup only
                        }
                    });
        } catch (IOException ignored) {
            // best-effort cleanup only
        }
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is unavailable", exception);
        }
    }

    private String safeOriginalFilename(String originalFilename) {
        String filename = pathName(StringUtils.hasText(originalFilename) ? originalFilename.trim() : "subtitle-upload");
        return StringUtils.hasText(filename) ? filename : "subtitle-upload";
    }

    private String pathName(String value) {
        String normalized = value == null ? "" : value.replace('\\', '/');
        int index = normalized.lastIndexOf('/');
        return index >= 0 ? normalized.substring(index + 1) : normalized;
    }

    private String extension(String value) {
        String name = pathName(value);
        int index = name.lastIndexOf('.');
        if (index < 0 || index == name.length() - 1) {
            return "";
        }
        return name.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, HttpStatus.BAD_REQUEST);
    }

    private record CopyResult(long size, String sha256) {
    }

    public record StagedSubtitleUpload(
            Path workDir,
            Path sourcePath,
            String sourceFileName,
            long sourceSize,
            String sourceSha256,
            SubtitleUploadProperties limits
    ) {
        public StagedSubtitleUpload(Path workDir, Path sourcePath, String sourceFileName, long sourceSize, String sourceSha256) {
            this(workDir, sourcePath, sourceFileName, sourceSize, sourceSha256, null);
        }
    }

    public record ExtractedSubtitlePackage(
            Path workDir,
            String sourceFileName,
            boolean archive,
            long sourceSize,
            String sourceSha256,
            List<ExtractedSubtitleEntry> entries,
            List<String> ignoredEntries
    ) {
    }

    public record ExtractedSubtitleEntry(
            String originalPath,
            String originalName,
            String extension,
            long size,
            String sha256,
            Path localPath
    ) {
    }
}
