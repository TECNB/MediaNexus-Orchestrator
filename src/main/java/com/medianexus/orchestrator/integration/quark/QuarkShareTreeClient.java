package com.medianexus.orchestrator.integration.quark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medianexus.orchestrator.config.QasProperties;
import com.medianexus.orchestrator.integration.qas.QasClientException;
import com.medianexus.orchestrator.integration.qas.QasShareInspectionException;
import com.medianexus.orchestrator.integration.qas.QasShareNode;
import com.medianexus.orchestrator.integration.qas.QasShareTree;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Direct Quark share-tree reader used by previews; QAS remains the task adapter. */
@Component
public class QuarkShareTreeClient {

    private static final String API_BASE = "https://drive-pc.quark.cn/1/clouddrive";
    private static final int MAX_DEPTH = 4;
    private static final int MAX_NODES = 500;
    private static final Pattern SHARE_PATH = Pattern.compile("/s/([^/?#]+)(?:/([^/?#]+))?");
    private static final Pattern HASH_FID = Pattern.compile("/([0-9a-fA-F]{32})(?:[-/?#]|$)");

    private final QasProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public QuarkShareTreeClient(QasProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public QasShareTree inspectShare(String shareUrl) {
        ShareRef ref = parseShareUrl(shareUrl);
        String stoken = fetchStoken(ref.shareId(), ref.passcode());
        InspectionState state = new InspectionState();
        List<QasShareNode> entries = inspectDirectory(ref, ref.startFid(), stoken, 0, state);
        return new QasShareTree(shareUrl, entries);
    }

    private List<QasShareNode> inspectDirectory(
            ShareRef ref,
            String fid,
            String stoken,
            int depth,
            InspectionState state
    ) {
        if (depth > MAX_DEPTH) {
            throw failure(QasClientException.Reason.INVALID_RESPONSE, "夸克分享目录深度超过 " + MAX_DEPTH, state);
        }
        JsonNode data = requestDetail(ref.shareId(), stoken, fid);
        List<QasShareNode> result = new ArrayList<>();
        for (JsonNode item : data.path("list")) {
            state.nodes++;
            if (state.nodes > MAX_NODES) {
                throw failure(QasClientException.Reason.INVALID_RESPONSE, "夸克分享节点数超过 " + MAX_NODES, state);
            }
            String childFid = item.path("fid").asText("");
            String name = item.path("file_name").asText("");
            boolean directory = item.path("dir").asBoolean(false);
            if (!StringUtils.hasText(childFid) || !StringUtils.hasText(name)) {
                throw failure(QasClientException.Reason.INVALID_RESPONSE, "夸克分享条目缺少 fid 或文件名", state);
            }
            List<QasShareNode> children = directory
                    ? inspectDirectory(ref, childFid, stoken, depth + 1, state)
                    : List.of();
            result.add(new QasShareNode(
                    childFid,
                    name,
                    directory,
                    item.path("obj_category").asText(null),
                    item.path("size").asLong(0),
                    children
            ));
        }
        return List.copyOf(result);
    }

    private String fetchStoken(String shareId, String passcode) {
        HttpRequest request = requestBuilder(API_BASE + "/share/sharepage/token?pr=ucpro&fr=pc")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"pwd_id\":" + quote(shareId) + ",\"passcode\":" + quote(passcode) + "}"
                ))
                .build();
        JsonNode root = send(request);
        String token = root.path("data").path("stoken").asText("");
        if (!StringUtils.hasText(token)) {
            throw failure(QasClientException.Reason.AUTHENTICATION, "夸克分享令牌获取失败", new InspectionState());
        }
        return token;
    }

    private JsonNode requestDetail(String shareId, String stoken, String fid) {
        ObjectNode merged = null;
        int page = 1;
        while (true) {
            String query = "?pr=ucpro&fr=pc&pwd_id=" + enc(shareId)
                    + "&stoken=" + enc(stoken)
                    + "&pdir_fid=" + enc(fid)
                    + "&force=0&_page=" + page + "&_size=50&_fetch_banner=0&_fetch_share=1&_fetch_total=1"
                    + "&_sort=file_type:asc,updated_at:desc&ver=2&fetch_share_full_path=1";
            JsonNode root = send(requestBuilder(API_BASE + "/share/sharepage/detail" + query).GET().build());
            if (root.path("code").asInt(0) != 0 && !root.path("success").asBoolean(false)) {
                throw new QasShareInspectionException(QasClientException.Reason.UPSTREAM, "夸克分享目录读取失败", false);
            }
            JsonNode data = root.path("data");
            if (!data.isObject() || !data.path("list").isArray()) {
                throw new QasShareInspectionException(QasClientException.Reason.INVALID_RESPONSE, "夸克分享目录响应无 data.list", false);
            }
            if (merged == null) {
                merged = ((ObjectNode) data).deepCopy();
                merged.set("list", objectMapper.createArrayNode());
            }
            ArrayNode list = (ArrayNode) merged.path("list");
            data.path("list").forEach(list::add);
            int expected = data.path("metadata").path("_total").asInt(0);
            if (data.path("list").size() < 50 || (expected > 0 && list.size() >= expected)) {
                break;
            }
            page++;
        }
        return merged;
    }

    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300 || root == null) {
                throw new QasClientException(QasClientException.Reason.UPSTREAM, "夸克分享接口请求失败");
            }
            return root;
        } catch (QasClientException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new QasClientException(QasClientException.Reason.UPSTREAM, "夸克分享接口请求失败", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new QasClientException(QasClientException.Reason.UPSTREAM, "夸克分享接口请求被中断", exception);
        }
    }

    private HttpRequest.Builder requestBuilder(String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(properties.getTimeout())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
        if (StringUtils.hasText(properties.getQuarkCookie())) {
            builder.header("Cookie", properties.getQuarkCookie());
        }
        return builder;
    }

    private ShareRef parseShareUrl(String shareUrl) {
        Matcher path = SHARE_PATH.matcher(shareUrl);
        if (!path.find()) {
            throw new QasClientException(QasClientException.Reason.INVALID_RESPONSE, "夸克分享链接格式无效");
        }
        String fid = path.group(2);
        Matcher hash = HASH_FID.matcher(shareUrl.substring(Math.max(0, shareUrl.indexOf('#'))));
        if (hash.find()) {
            fid = hash.group(1);
        }
        String passcode = "";
        try {
            URI uri = new URI(shareUrl);
            String query = uri.getRawQuery();
            if (query != null) {
                for (String pair : query.split("&")) {
                    String[] parts = pair.split("=", 2);
                    if (parts.length == 2 && parts[0].equals("pwd")) {
                        passcode = java.net.URLDecoder.decode(parts[1], java.nio.charset.StandardCharsets.UTF_8);
                    }
                }
            }
        } catch (Exception ignored) {
            // The path was already validated; an optional password is best effort.
        }
        return new ShareRef(path.group(1), fid == null ? "0" : fid, passcode);
    }

    private QasShareInspectionException failure(QasClientException.Reason reason, String message, InspectionState state) {
        return new QasShareInspectionException(reason, message, false);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String enc(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private record ShareRef(String shareId, String startFid, String passcode) { }
    private static final class InspectionState { private int nodes; }
}
