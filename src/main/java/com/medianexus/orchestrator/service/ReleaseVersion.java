package com.medianexus.orchestrator.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 文件名/发行标题声明的版本证据，不代表媒体质量实测；未命中仅表示未知或无标记。 */
public final class ReleaseVersion {

    private static final Pattern EARLY_PREVIEW = Pattern.compile("尝鲜|嘗鮮");
    private static final Pattern ADVANCE_PREVIEW = Pattern.compile("抢先|搶先");
    // TS 单词或 .ts 容器均无录屏含义；只有明确发行标签才作为枪版证据。
    private static final Pattern CAMERA = Pattern.compile(
            "(?i)枪版|槍版|(?<![a-z0-9])(?:cam(?:rip)?|hdcam|hdts|telesync)(?![a-z0-9])"
                    + "|(?<![a-z0-9])ts\\s*版");

    private ReleaseVersion() {
    }

    public static boolean isPreview(String text) {
        return text != null && (EARLY_PREVIEW.matcher(text).find() || ADVANCE_PREVIEW.matcher(text).find());
    }

    public static boolean isCamera(String text) {
        return text != null && CAMERA.matcher(text).find();
    }

    /** 返回去重后的简体标记（尝鲜版、抢先版、枪版，按此顺序）；无证据/null 返回空串。 */
    public static String marker(String text) {
        if (text == null) {
            return "";
        }
        List<String> markers = new ArrayList<>();
        if (EARLY_PREVIEW.matcher(text).find()) {
            markers.add("尝鲜版");
        }
        if (ADVANCE_PREVIEW.matcher(text).find()) {
            markers.add("抢先版");
        }
        if (isCamera(text)) {
            markers.add("枪版");
        }
        return String.join(" ", markers);
    }
}
