package com.medianexus.orchestrator.service;

import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.medianexus.orchestrator.config.QasProperties;
import com.medianexus.orchestrator.integration.qas.QasClient;
import com.medianexus.orchestrator.integration.qas.QasClientException;
import com.medianexus.orchestrator.integration.qas.QasShareTree;
import com.medianexus.orchestrator.integration.quark.QuarkShareTreeClient;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Shared, short-lived Quark tree snapshot used by every preview and submit path. */
@Service
public class QuarkShareTreeService {

    private static final int MAX_CACHE_ENTRIES = 256;
    private static final Pattern SHARE_PATH = Pattern.compile("^/s/([^/]+)(?:/([A-Fa-f0-9]{32}))?/?$");
    private static final Pattern HASH_FID = Pattern.compile("/([A-Fa-f0-9]{32})(?:[-/?#]|$)");

    private final QuarkShareTreeClient directClient;
    private final QasClient qasClient;
    private final Cache<String, QasShareTree> trees;

    @Autowired
    public QuarkShareTreeService(
            QuarkShareTreeClient directClient,
            QasClient qasClient,
            QasProperties properties
    ) {
        this(directClient, qasClient, properties, Ticker.systemTicker());
    }

    QuarkShareTreeService(
            QuarkShareTreeClient directClient,
            QasClient qasClient,
            QasProperties properties,
            Ticker ticker
    ) {
        this.directClient = directClient;
        this.qasClient = qasClient;
        this.trees = CacheBuilder.<String, QasShareTree>newBuilder()
                .maximumSize(MAX_CACHE_ENTRIES)
                .expireAfterWrite(properties.getShareTreeCacheTtl())
                .ticker(ticker)
                .build();
    }

    public QasShareTree inspectShare(String shareUrl) {
        try {
            QasShareTree cached = trees.get(cacheKey(shareUrl), () -> load(shareUrl));
            // Keep the caller's URL representation for planning and fingerprints.
            return new QasShareTree(shareUrl, cached.entries());
        } catch (ExecutionException exception) {
            throw propagate(exception.getCause());
        } catch (UncheckedExecutionException exception) {
            throw propagate(exception.getCause());
        }
    }

    private QasShareTree load(String shareUrl) {
        try {
            return directClient.inspectShare(shareUrl);
        } catch (QasClientException exception) {
            return qasClient.inspectShare(shareUrl);
        }
    }

    private RuntimeException propagate(Throwable cause) {
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new QasClientException(QasClientException.Reason.UPSTREAM, "夸克分享检查失败", cause);
    }

    static String cacheKey(String shareUrl) {
        try {
            URI uri = URI.create(shareUrl.trim());
            Matcher path = SHARE_PATH.matcher(uri.getPath());
            if (!path.matches()) {
                return sha256(shareUrl.trim());
            }
            String directoryFid = path.group(2);
            if (directoryFid == null && uri.getRawFragment() != null) {
                Matcher hash = HASH_FID.matcher(uri.getRawFragment());
                if (hash.find()) {
                    directoryFid = hash.group(1);
                }
            }
            String password = queryValue(uri.getRawQuery(), "pwd");
            return path.group(1)
                    + ':' + (directoryFid == null ? "0" : directoryFid.toLowerCase())
                    + ':' + sha256(password);
        } catch (RuntimeException exception) {
            return sha256(shareUrl.trim());
        }
    }

    private static String queryValue(String query, String name) {
        if (query == null) {
            return "";
        }
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && parts[0].equals(name)) {
                return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
