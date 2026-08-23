package com.medianexus.orchestrator.integration.qas;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class QasShareUrl {

    private static final Pattern SHARE_PATH = Pattern.compile("^(/s/[^/]+)(?:/[^/]+)?/?$");

    private QasShareUrl() {
    }

    public static String withDirectoryFid(String sourceUrl, String fid) {
        try {
            URI source = new URI(sourceUrl);
            Matcher matcher = SHARE_PATH.matcher(source.getPath());
            if (!matcher.matches()) {
                throw new IllegalArgumentException("Unsupported Quark share URL path");
            }
            StringBuilder result = new StringBuilder()
                    .append(source.getScheme())
                    .append("://")
                    .append(source.getRawAuthority())
                    .append(matcher.group(1))
                    .append('/')
                    .append(fid);
            if (source.getRawQuery() != null) {
                result.append('?').append(source.getRawQuery());
            }
            return result.toString();
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid Quark share URL", exception);
        }
    }
}
