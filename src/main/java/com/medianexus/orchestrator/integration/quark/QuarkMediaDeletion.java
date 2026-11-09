package com.medianexus.orchestrator.integration.quark;

import java.net.URI;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class QuarkMediaDeletion {

    private static final String SOURCE_MARKER = "/smartstrm_fid/QuarkTV/";
    private static final Pattern FILE_ID = Pattern.compile(
            "/smartstrm_fid/QuarkTV/([0-9a-fA-F]{32})(?:/|$)"
    );

    private final QuarkDirectClient quarkClient;

    public QuarkMediaDeletion(QuarkDirectClient quarkClient) {
        this.quarkClient = quarkClient;
    }

    public boolean supports(String mediaSourcePath) {
        return mediaSourcePath != null && mediaSourcePath.contains(SOURCE_MARKER);
    }

    public void deleteMediaSourcePaths(List<String> mediaSourcePaths) {
        deleteMediaSourcePaths(mediaSourcePaths, null);
    }

    public void deleteMediaSourcePaths(List<String> mediaSourcePaths, String targetDirectoryName) {
        String directoryPath = sharedDirectoryPath(mediaSourcePaths, targetDirectoryName);
        if (directoryPath != null && quarkClient.deleteOwnedPath(directoryPath)) {
            return;
        }
        List<String> fileIds = mediaSourcePaths.stream()
                .map(this::requireFileId)
                .distinct()
                .toList();
        quarkClient.deleteOwnedFiles(fileIds);
    }

    private String sharedDirectoryPath(List<String> sourcePaths, String targetDirectoryName) {
        if (targetDirectoryName == null || targetDirectoryName.isBlank() || sourcePaths.isEmpty()) {
            return null;
        }
        Set<String> matches = new LinkedHashSet<>();
        for (String sourcePath : sourcePaths) {
            String match = directoryPath(sourcePath, targetDirectoryName);
            if (match == null) {
                return null;
            }
            matches.add(match);
        }
        return matches.size() == 1 ? matches.iterator().next() : null;
    }

    private String directoryPath(String mediaSourcePath, String targetDirectoryName) {
        try {
            String path = URI.create(mediaSourcePath).getPath();
            int marker = path.indexOf(SOURCE_MARKER);
            if (marker < 0) return null;
            String afterMarker = path.substring(marker + SOURCE_MARKER.length());
            int fileIdEnd = afterMarker.indexOf('/');
            if (fileIdEnd < 0 || fileIdEnd == afterMarker.length() - 1) return null;
            String[] segments = afterMarker.substring(fileIdEnd + 1).split("/");
            StringBuilder directory = new StringBuilder();
            for (String segment : segments) {
                directory.append('/').append(segment);
                if (targetDirectoryName.equals(segment)) {
                    return directory.toString();
                }
            }
            return null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String requireFileId(String mediaSourcePath) {
        Matcher matcher = FILE_ID.matcher(mediaSourcePath);
        if (!matcher.find()) {
            throw new IllegalArgumentException("无法从 SmartStrm 媒体源识别夸克文件 ID");
        }
        return matcher.group(1);
    }
}
