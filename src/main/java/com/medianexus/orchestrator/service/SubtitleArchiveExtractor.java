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

    public SubtitleArchiveExtractor(SubtitleUploadProperties properties) {
        this.properties = properties;
    }

    public ExtractedSubtitlePackage extract(MultipartFile file) {
        return extract(stage(file));
    }

    public StagedSubtitleUpload stage(MultipartFile file) {
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
                    sourceCopy.sha256()
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
        Path workDir = stagedUpload.workDir();
        String sourceFileName = stagedUpload.sourceFileName();
        try {
            String extension = extension(sourceFileName);
            if ("zip".equals(extension)) {
                return extractZip(workDir, stagedUpload.sourcePath(), sourceFileName, stagedUpload);
            }
            if (!allowedExtensions().contains(extension)) {
                throw badRequest("当前仅支持 " + allowedExtensionDescription() + " 或 .zip 文件");
            }
            long perFileLimit = perSubtitleLimit(extension);
            if (stagedUpload.sourceSize() > perFileLimit) {
                throw badRequest("字幕文件超过大小限制: " + sourceFileName);
            }
            ExtractedSubtitleEntry entry = new ExtractedSubtitleEntry(
                    sourceFileName,
                    sourceFileName,
                    extension,
                    stagedUpload.sourceSize(),
                    stagedUpload.sourceSha256(),
                    stagedUpload.sourcePath()
            );
            return new ExtractedSubtitlePackage(
                    workDir,
                    sourceFileName,
                    false,
                    stagedUpload.sourceSize(),
                    stagedUpload.sourceSha256(),
                    List.of(entry),
                    List.of()
            );
        } catch (BusinessException exception) {
            deleteQuietly(workDir);
            throw exception;
        }
    }

    private ExtractedSubtitlePackage extractZip(
            Path workDir,
            Path sourcePath,
            String sourceFileName,
            StagedSubtitleUpload stagedUpload
    ) {
        Path entriesDir = workDir.resolve("entries");
        try {
            Files.createDirectories(entriesDir);
            List<ExtractedSubtitleEntry> acceptedEntries = new ArrayList<>();
            List<String> ignoredEntries = new ArrayList<>();
            long totalExtractedSize = 0L;

            try (ZipFile zipFile = new ZipFile(sourcePath.toFile())) {
                Enumeration<ZipArchiveEntry> entries = zipFile.getEntries();
                while (entries.hasMoreElements()) {
                    ZipArchiveEntry entry = entries.nextElement();
                    String entryName = entry.getName();
                    validateZipEntry(entry);

                    if (entry.isDirectory()) {
                        continue;
                    }
                    String extension = extension(entryName);
                    if (NESTED_ARCHIVE_EXTENSIONS.contains(extension) || !allowedExtensions().contains(extension)) {
                        ignoredEntries.add(entryName);
                        continue;
                    }

                    if (acceptedEntries.size() >= properties.getMaxEntryCount()) {
                        throw badRequest("ZIP 中可接受字幕文件数量超过限制");
                    }
                    long uncompressedSize = entry.getSize();
                    if (uncompressedSize < 0) {
                        throw badRequest("ZIP 中存在无法确认大小的字幕文件: " + entryName);
                    }
                    long compressedSize = entry.getCompressedSize();
                    if (compressedSize <= 0 && uncompressedSize > 0) {
                        throw badRequest("ZIP 中存在异常压缩条目: " + entryName);
                    }
                    if (compressedSize > 0
                            && (double) uncompressedSize / (double) compressedSize
                            > properties.getMaxCompressionRatio()) {
                        throw badRequest("ZIP 中存在压缩比异常的字幕文件: " + entryName);
                    }
                    long perFileLimit = perSubtitleLimit(extension);
                    if (uncompressedSize > perFileLimit) {
                        throw badRequest("ZIP 中字幕文件超过大小限制: " + entryName);
                    }
                    totalExtractedSize += uncompressedSize;
                    if (totalExtractedSize > properties.getMaxTotalExtractedSize().toBytes()) {
                        throw badRequest("ZIP 中字幕文件总大小超过限制");
                    }

                    Path localPath = entriesDir.resolve(String.format(
                            Locale.ROOT,
                            "subtitle-%03d.%s",
                            acceptedEntries.size() + 1,
                            extension
                    ));
                    try (InputStream inputStream = zipFile.getInputStream(entry)) {
                        CopyResult entryCopy = copyWithLimit(
                                inputStream,
                                localPath,
                                perFileLimit,
                                "ZIP 中字幕文件超过大小限制: " + entryName
                        );
                        if (entryCopy.size() != uncompressedSize) {
                            totalExtractedSize = totalExtractedSize - uncompressedSize + entryCopy.size();
                            if (totalExtractedSize > properties.getMaxTotalExtractedSize().toBytes()) {
                                throw badRequest("ZIP 中字幕文件总大小超过限制");
                            }
                        }
                        acceptedEntries.add(new ExtractedSubtitleEntry(
                                entryName,
                                pathName(entryName),
                                extension,
                                entryCopy.size(),
                                entryCopy.sha256(),
                                localPath
                        ));
                    }
                }
            }

            if (acceptedEntries.isEmpty()) {
                throw badRequest("ZIP 中没有可接受的字幕文件");
            }
            return new ExtractedSubtitlePackage(
                    workDir,
                    sourceFileName,
                    true,
                    stagedUpload.sourceSize(),
                    stagedUpload.sourceSha256(),
                    List.copyOf(acceptedEntries),
                    List.copyOf(ignoredEntries)
            );
        } catch (BusinessException exception) {
            deleteQuietly(workDir);
            throw exception;
        } catch (IOException exception) {
            deleteQuietly(workDir);
            throw badRequest("ZIP 文件无法读取");
        }
    }

    private void validateZipEntry(ZipArchiveEntry entry) {
        String name = entry.getName();
        if (!StringUtils.hasText(name) || name.indexOf('\0') >= 0) {
            throw badRequest("ZIP 中存在非法路径");
        }
        if (entry.getGeneralPurposeBit() != null && entry.getGeneralPurposeBit().usesEncryption()) {
            throw badRequest("ZIP 中存在加密条目");
        }
        if (entry.isUnixSymlink()) {
            throw badRequest("ZIP 中存在符号链接");
        }
        String normalizedName = name.replace('\\', '/');
        if (normalizedName.startsWith("/") || WINDOWS_DRIVE_PATTERN.matcher(normalizedName).matches()) {
            throw badRequest("ZIP 中存在绝对路径: " + name);
        }
        Path normalizedPath = Path.of(normalizedName).normalize();
        if (normalizedPath.isAbsolute() || normalizedPath.startsWith("..")) {
            throw badRequest("ZIP 中存在路径穿越: " + name);
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

    private String allowedExtensionDescription() {
        return allowedExtensions().stream()
                .map(extension -> "." + extension)
                .reduce((left, right) -> left + "、" + right)
                .orElse(".ass、.srt、.sup");
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
            String sourceSha256
    ) {
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
