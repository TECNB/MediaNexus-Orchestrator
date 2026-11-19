package com.medianexus.orchestrator.integration.guangya;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.GuangyaProperties;
import com.medianexus.orchestrator.integration.pansou.PanSouMagnetIdentity;
import com.medianexus.orchestrator.service.GuangyaCredentials;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.springframework.stereotype.Component;

/** 仅允许磁力元数据解析及已核实的 token grant；不提供下载/转存/目录操作。 */
@Component
public class GuangyaClient {
    private static final String CLIENT_ID = "aMe-8VSlkrbQXpUR";
    private final GuangyaProperties properties;
    private final GuangyaCredentials credentials;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(java.time.Duration.ofSeconds(8)).build();
    private final Map<String, CachedTable> cache = new LinkedHashMap<>();
    private final Map<String, CompletableFuture<TorrentFileTable>> resolving = new HashMap<>();
    private final Object refreshLock = new Object();
    private CompletableFuture<GuangyaCredentials.Snapshot> refreshFlight;
    private GuangyaCredentials.Snapshot refreshObserved;
    private volatile GuangyaCredentials.Snapshot unpersistedRotation;

    public GuangyaClient(GuangyaProperties properties, GuangyaCredentials credentials, ObjectMapper json) {
        this.properties = properties; this.credentials = credentials; this.json = json;
    }
    public boolean isConfigured() { return credentials.snapshot().configured(); }
    public Optional<TorrentFileTable> cached(String magnet) {
        return PanSouMagnetIdentity.fromUrl(magnet).flatMap(identity -> cachedIdentity(identity.btih()));
    }
    private synchronized Optional<TorrentFileTable> cachedIdentity(String hash) {
        CachedTable item = cache.get(hash);
        if (item == null) return Optional.empty();
        if (!item.expiresAt.isAfter(Instant.now())) { cache.remove(hash); return Optional.empty(); }
        return Optional.of(item.table);
    }
    public TorrentFileTable resolve(String magnet, ResolveBudget budget) {
        PanSouMagnetIdentity identity = PanSouMagnetIdentity.fromUrl(magnet).orElseThrow(() -> invalid("磁力缺少唯一有效 BTIH"));
        Optional<TorrentFileTable> hit = cachedIdentity(identity.btih());
        if (hit.isPresent()) return hit.get();
        CompletableFuture<TorrentFileTable> flight;
        boolean owner;
        synchronized (this) {
            flight = resolving.get(identity.btih());
            owner = flight == null;
            if (owner) { flight = new CompletableFuture<>(); resolving.put(identity.btih(), flight); }
        }
        if (!owner) return await(flight, budget.remaining());
        try {
            TorrentFileTable table = resolveUncached(identity, budget);
            flight.complete(table);
            return table;
        } catch (RuntimeException exception) {
            flight.completeExceptionally(exception);
            throw exception;
        } finally { synchronized (this) { resolving.remove(identity.btih(), flight); } }
    }

    private TorrentFileTable resolveUncached(PanSouMagnetIdentity identity, ResolveBudget budget) {
        if (unpersistedRotation != null) throw persistenceFailure();
        var observed = credentials.snapshot();
        if (!observed.configured()) throw new GuangyaException(GuangyaException.Reason.NOT_CONFIGURED, "光鸭 accessToken、refreshToken 和稳定 deviceId 尚未配置");
        String canonicalMagnet = "magnet:?xt=urn:btih:" + identity.btih();
        budget.acquire();
        try {
            Optional<TorrentFileTable> hit = cachedIdentity(identity.btih());
            if (hit.isPresent()) return hit.get();
            var response = resolveRequest(canonicalMagnet, observed, budget);
            if (expired(response)) {
                observed = refresh(observed, budget);
                response = resolveRequest(canonicalMagnet, observed, budget);
                if (expired(response)) throw new GuangyaException(GuangyaException.Reason.AUTHENTICATION, "光鸭刷新后仍拒绝认证，请重新登录并更新凭据");
            }
            if (response.status == 403 || response.status == 429) throw new GuangyaException(GuangyaException.Reason.ACCOUNT_BLOCKED, "光鸭账户权限或请求额度阻塞，停止本轮新解析");
            requireSuccess(response);
            TorrentFileTable table = parse(response.body.path("data"), identity);
            budget.remaining();
            synchronized (this) {
                cache.put(identity.btih(), new CachedTable(table, Instant.now().plus(properties.getCacheTtl())));
                while (cache.size() > properties.getCacheMaxEntries()) cache.remove(cache.keySet().iterator().next());
            }
            return table;
        } finally { budget.release(); }
    }
    private Reply resolveRequest(String magnet, GuangyaCredentials.Snapshot snapshot, ResolveBudget budget) {
        budget.countResolveRequest();
        return post(properties.getApiBaseUrl(), "/cloudcollection/v1/resolve_res", Map.of("url", magnet),
                Map.of("Authorization", "Bearer " + snapshot.accessToken(), "Did", snapshot.deviceId(), "Dt", "4"), budget);
    }
    private GuangyaCredentials.Snapshot refresh(GuangyaCredentials.Snapshot observed, ResolveBudget budget) {
        CompletableFuture<GuangyaCredentials.Snapshot> flight;
        boolean owner = false;
        synchronized (refreshLock) {
            if (unpersistedRotation != null) throw persistenceFailure();
            var current = credentials.snapshot();
            if (refreshFlight != null && observed.equals(refreshObserved)) {
                flight = refreshFlight;
                budget.joinAuthenticationRefresh(flight);
            } else {
                if (!current.equals(observed)) return current;
                flight = new CompletableFuture<>();
                budget.joinAuthenticationRefresh(flight);
                refreshObserved = observed;
                refreshFlight = flight; owner = true;
            }
        }
        if (owner) {
            try {
                // 协议证据：巡影 e61a7a4 guangya-client.ts:22,402–433；真实刷新尚未联调。
                Reply reply = post(properties.getAuthBaseUrl(), "/v1/auth/token", Map.of("client_id", CLIENT_ID,
                        "grant_type", "refresh_token", "refresh_token", observed.refreshToken()), Map.of(), budget);
                String access = token(reply.body.path("access_token"));
                if (reply.status != 200 || access == null) throw new GuangyaException(GuangyaException.Reason.AUTHENTICATION, "光鸭认证刷新失败，请重新登录并更新凭据");
                String refresh = token(reply.body.path("refresh_token"));
                if (refresh == null) refresh = observed.refreshToken();
                var rotated = new GuangyaCredentials.Snapshot(observed.version() + 1, access, refresh, observed.deviceId());
                try {
                    credentials.rotate(observed, rotated);
                } catch (RuntimeException failure) {
                    unpersistedRotation = rotated;
                    org.slf4j.LoggerFactory.getLogger(GuangyaClient.class).error("光鸭轮换凭据持久化失败，暂停新解析；请管理员重新保存凭据");
                    throw persistenceFailure();
                }
                flight.complete(credentials.snapshot());
            } catch (RuntimeException failure) {
                flight.completeExceptionally(failure);
                synchronized (refreshLock) {
                    if (refreshFlight == flight) { refreshFlight = null; refreshObserved = null; }
                }
            }
        }
        return await(flight, budget.remaining());
    }
    private String token(JsonNode node) {
        if (!node.isTextual() || node.textValue().isBlank() || node.textValue().chars().anyMatch(c -> c < 32 || c == 127)) return null;
        return node.textValue();
    }
    private boolean expired(Reply response) {
        return response.status == 401 || (response.status == 200 && Set.of("110", "117", "118").contains(response.body.path("code").asText()));
    }
    private void requireSuccess(Reply reply) {
        JsonNode code = reply.body.path("code");
        JsonNode msg = reply.body.path("msg");
        if (reply.status != 200 || !reply.body.isObject() || (!code.isMissingNode() && !code.isNull() && !"0".equals(code.asText()))
                || (!msg.isMissingNode() && (!msg.isTextual() || !(msg.asText().isEmpty() || "success".equalsIgnoreCase(msg.asText()))))) {
            throw new GuangyaException(GuangyaException.Reason.UNAVAILABLE, "光鸭未返回成功解析结果；不代表资源缺集或失效");
        }
    }
    private Reply post(String base, String path, Object body, Map<String, String> headers, ResolveBudget budget) {
        CompletableFuture<HttpResponse<byte[]>> request = null;
        try {
            URI uri = URI.create(base.replaceAll("/+$", "") + path);
            if (!Set.of("https", "http").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getRawQuery() != null || uri.getFragment() != null) throw invalid("光鸭接口地址无效");
            var timeout = budget.requestTimeout();
            var builder = HttpRequest.newBuilder(uri).timeout(timeout).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            headers.forEach(builder::header);
            request = http.sendAsync(builder.build(), ignored -> new BoundedBody(properties.getMaxResponseBytes()));
            var response = await(request, timeout);
            // HTTP 认证/风控状态不依赖网关错误页是否为 JSON。
            if (Set.of(401, 403, 429).contains(response.statusCode())) return new Reply(response.statusCode(), json.createObjectNode());
            JsonNode parsed = json.readTree(response.body());
            return new Reply(response.statusCode(), parsed == null ? json.createObjectNode() : parsed);
        } catch (GuangyaException exception) { throw exception; }
        catch (Exception exception) { throw new GuangyaException(GuangyaException.Reason.UNAVAILABLE, "光鸭只读请求失败或响应不可读取；未自动重试"); }
        finally { if (request != null && !request.isDone()) request.cancel(true); }
    }
    private <T> T await(CompletableFuture<T> future, java.time.Duration timeout) {
        try { return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS); }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GuangyaException(GuangyaException.Reason.UNAVAILABLE, "光鸭请求已中断");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof GuangyaException failure) throw failure;
            throw new GuangyaException(GuangyaException.Reason.UNAVAILABLE, "光鸭请求失败；未自动重试");
        } catch (TimeoutException exception) { throw new GuangyaException(GuangyaException.Reason.UNAVAILABLE, "光鸭请求超时；未自动重试"); }
    }
    private TorrentFileTable parse(JsonNode data, PanSouMagnetIdentity expected) {
        if (!data.isObject() || !data.path("resType").isIntegralNumber() || data.path("resType").intValue() != 1) throw invalid("光鸭未返回 BT 文件表");
        JsonNode tree = data.path("btResInfo");
        String hash = tree.path("infoHash").asText();
        if (!hash.matches("(?i)[0-9a-f]{40}") || !expected.btih().equalsIgnoreCase(hash)) throw invalid("光鸭返回 hash 与输入磁力不一致");
        String root = name(tree.path("fileName"));
        if (tree.path("subfiles").isArray() && !tree.path("subfiles").isEmpty() && !tree.has("subfilesNum")) {
            throw invalid("光鸭多文件表缺少声明叶子数量，无法确认完整性");
        }
        var files = new ArrayList<TorrentFileTable.Entry>();
        var paths = new HashSet<String>();
        int[] nodes = {0};
        long sum = walk(tree, "", 0, files, paths, nodes);
        if (files.isEmpty()) throw invalid("光鸭文件表没有可信叶子文件");
        return new TorrentFileTable(expected, root, files, sum);
    }
    private long walk(JsonNode node, String parent, int depth, List<TorrentFileTable.Entry> files, Set<String> paths, int[] nodes) {
        if (++nodes[0] > properties.getMaxNodes() || depth > properties.getMaxDepth() || !node.isObject()) throw invalid("光鸭文件表深度或节点数超限");
        String name = name(node.path("fileName"));
        String path = parent.isEmpty() ? name : parent + "/" + name;
        if (path.length() > 4096) throw invalid("光鸭文件路径过长");
        long declaredSize = integer(node.path("fileSize"));
        JsonNode children = node.path("subfiles");
        JsonNode directory = node.path("isDir");
        if (!directory.isMissingNode() && !directory.isBoolean()) throw invalid("光鸭目录标记格式无效");
        if (!children.isMissingNode() && !children.isArray()) throw invalid("光鸭子文件列表格式无效");
        int before = files.size();
        long sum = 0;
        boolean hasChildren = (children.isArray() && !children.isEmpty()) || directory.asBoolean(false);
        if (depth > 0 && !paths.add(path)) throw invalid("光鸭存在重复文件或目录路径");
        if (hasChildren) {
            if (!children.isArray()) throw invalid("光鸭目录缺少完整子文件表");
            for (JsonNode child : children) {
                try { sum = Math.addExact(sum, walk(child, depth == 0 ? "" : path, depth + 1, files, paths, nodes)); }
                catch (ArithmeticException exception) { throw invalid("光鸭文件大小总和溢出"); }
            }
            if (sum != declaredSize) throw invalid("光鸭目录大小与叶子总和不一致");
        } else {
            files.add(new TorrentFileTable.Entry(path, declaredSize)); sum = declaredSize;
        }
        // 单文件的 subfilesNum=0 是 protobuf 缺省子项数；不是整张表有零个文件。
        if (node.has("subfilesNum") && integer(node.path("subfilesNum")) != (hasChildren ? files.size() - before : 0)) throw invalid("光鸭声明叶子数量与完整文件表不一致");
        return sum;
    }
    private long integer(JsonNode value) {
        try {
            if (value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0) return value.longValue();
            if (value.isTextual() && value.textValue().matches("[0-9]{1,19}")) return Long.parseLong(value.textValue());
        } catch (NumberFormatException ignored) { }
        throw invalid("光鸭文件大小或数量缺失、负数或超出整数范围");
    }
    private String name(JsonNode value) {
        if (!value.isTextual()) throw invalid("光鸭文件名缺失");
        String name = value.textValue();
        if (name.isBlank() || name.length() > 255 || !name.equals(name.strip()) || name.equals(".") || name.equals("..")
                || name.matches(".*[\\\\/:].*") || name.chars().anyMatch(c -> c < 32 || c == 127)) throw invalid("光鸭返回非法相对文件路径");
        return name;
    }
    private GuangyaException invalid(String message) { return new GuangyaException(GuangyaException.Reason.INVALID_RESPONSE, message); }
    private GuangyaException persistenceFailure() { return new GuangyaException(GuangyaException.Reason.PERSISTENCE_FAILED, "光鸭轮换凭据未能安全持久化，已暂停新解析，请管理员重新保存凭据并重启恢复"); }
    private record Reply(int status, JsonNode body) {}
    private record CachedTable(TorrentFileTable table, Instant expiresAt) {}

    /** 在订阅边界限制响应体，截止时间覆盖慢速响应体，不依赖仅返回响应头的 InputStream 超时。 */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int maximum;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private BoundedBody(int maximum) { this.maximum = maximum; }
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > maximum - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new GuangyaException(GuangyaException.Reason.INVALID_RESPONSE, "光鸭响应体超出大小上限")); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable failure) { result.completeExceptionally(failure); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
