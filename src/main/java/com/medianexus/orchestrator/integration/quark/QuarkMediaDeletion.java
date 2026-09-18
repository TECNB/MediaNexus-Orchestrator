package com.medianexus.orchestrator.integration.quark;

import java.util.List;
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
        List<String> fileIds = mediaSourcePaths.stream()
                .map(this::requireFileId)
                .distinct()
                .toList();
        quarkClient.deleteOwnedFiles(fileIds);
    }

    private String requireFileId(String mediaSourcePath) {
        Matcher matcher = FILE_ID.matcher(mediaSourcePath);
        if (!matcher.find()) {
            throw new IllegalArgumentException("无法从 SmartStrm 媒体源识别夸克文件 ID");
        }
        return matcher.group(1);
    }
}
