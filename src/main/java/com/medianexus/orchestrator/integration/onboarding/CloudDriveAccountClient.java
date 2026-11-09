package com.medianexus.orchestrator.integration.onboarding;

import com.google.protobuf.Empty;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.config.CloudDrive2Properties;
import com.medianexus.orchestrator.integration.clouddrive.proto.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** CD2 账号/系统挂载协议；公开读取只返回白名单，不暴露 DAV 用户或原始云盘配置。 */
@Component
public class CloudDriveAccountClient {
    public record CloudView(String name, String path, boolean locked) {}
    public record MountView(String mountPoint, String sourceDir, boolean mounted, boolean readOnly) {}
    public record Resources(List<CloudView> clouds, List<MountView> mounts, boolean canAddCloud, boolean canAddMount) {}

    public Resources readResources(CloudDrive2Properties connection) {
        return call(connection, stub -> new Resources(
                stub.getAllCloudApis(Empty.getDefaultInstance()).getApisList().stream()
                        .map(api -> new CloudView(api.getName(), api.hasPath() ? api.getPath() : null, api.getIsLocked())).toList(),
                stub.getMountPoints(Empty.getDefaultInstance()).getMountPointsList().stream()
                        .map(mount -> new MountView(mount.getMountPoint(), mount.getSourceDir(), mount.getIsMounted(), mount.getReadOnly())).toList(),
                stub.canAddMoreCloudApis(Empty.getDefaultInstance()).getSuccess(),
                stub.canAddMoreMountPoints(Empty.getDefaultInstance()).getSuccess()));
    }

    /** 服务端继承实际挂载选项，避免自行填造权限；管理响应仍使用 MountView 白名单。 */
    public MountOption readMountTemplate(CloudDrive2Properties connection, String mountPoint) {
        return call(connection, stub -> {
            var matches = stub.getMountPoints(Empty.getDefaultInstance()).getMountPointsList().stream()
                    .filter(mount -> mount.getMountPoint().equals(mountPoint)).toList();
            if (matches.size() != 1 || !matches.get(0).getIsMounted()) { throw rejected("既有挂载模板无法唯一确认或当前未挂载"); }
            MountPoint mount = matches.get(0);
            return MountOption.newBuilder().setMountPoint(mount.getMountPoint()).setSourceDir(mount.getSourceDir())
                    .setLocalMount(mount.getLocalMount()).setReadOnly(mount.getReadOnly()).setAutoMount(mount.getAutoMount())
                    .setUid(mount.getUid()).setGid(mount.getGid()).setPermissions(mount.getPermissions()).setName(mount.getName()).build();
        });
    }

    /** 仅向明确支持的官方 HTTPS 根发送专用 DAV 凭据，不跟随重定向。 */
    public String verifyNativeWebDav(String endpoint, String username, String password) {
        java.net.URI uri;
        try {
            uri = java.net.URI.create(endpoint.trim());
        } catch (IllegalArgumentException failure) {
            throw rejected("请输入 PikPak 官方 WebDAV HTTPS 地址");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || !java.util.Set.of("dav.pikpak.ai", "dav.mypikpak.com").contains(uri.getHost().toLowerCase(java.util.Locale.ROOT))
                || uri.getPort() != -1 && uri.getPort() != 443 || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !java.util.Set.of("", "/").contains(uri.getRawPath())
                || !StringUtils.hasText(username) || username.contains(":") || !StringUtils.hasText(password)) {
            throw rejected("请填写 PikPak 官方 WebDAV 根地址和专用凭据");
        }
        String url = "https://" + uri.getHost().toLowerCase(java.util.Locale.ROOT) + "/";
        var timeout = java.time.Duration.ofSeconds(12);
        String basic = java.util.Base64.getEncoder().encodeToString(
                (username.trim() + ":" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).timeout(timeout)
                .header("Authorization", "Basic " + basic).header("Depth", "0")
                .method("PROPFIND", java.net.http.HttpRequest.BodyPublishers.noBody()).build();
        try {
            var response = java.net.http.HttpClient.newBuilder().connectTimeout(timeout).build()
                    .send(request, java.net.http.HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 207) { throw rejected("PikPak 官方 WebDAV 未通过授权检查"); }
            return url;
        } catch (java.io.IOException failure) {
            throw rejected("PikPak 官方 WebDAV 无法访问");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw rejected("PikPak 官方 WebDAV 检查中断");
        }
    }

    /** 只更新四项已验证的下载参数；保留 CD2 版本返回的其他字段。 */
    public void applyNewAccountDownloadProfile(CloudDrive2Properties connection, String cloudPath) {
        call(connection, stub -> {
            var matches = stub.getAllCloudApis(Empty.getDefaultInstance()).getApisList().stream()
                    .filter(a -> cloudPath.equals(a.getPath()) && !a.getIsLocked()).toList();
            if (matches.size() != 1 || !"WebDAV".equals(matches.get(0).getName())
                    || !StringUtils.hasText(matches.get(0).getUserName())) {
                throw rejected("CD2 云盘身份无法唯一确认，未更新下载设置");
            }
            var cloud = matches.get(0);
            var key = GetCloudAPIConfigRequest.newBuilder().setCloudName(cloud.getName()).setUserName(cloud.getUserName()).build();
            var before = stub.getCloudAPIConfig(key);
            var desired = before.toBuilder().setMaxDownloadThreads(4).setMinReadLengthKB(64)
                    .setDefaultReadLengthKB(2048).setMaxBufferPoolSizeMB(128).build();
            if (!before.equals(desired)) {
                stub.setCloudAPIConfig(SetCloudAPIConfigRequest.newBuilder().setCloudName(cloud.getName())
                        .setUserName(cloud.getUserName()).setConfig(desired).build());
            }
            var actual = stub.getCloudAPIConfig(key);
            if (actual.getMaxDownloadThreads() != desired.getMaxDownloadThreads()
                    || actual.getMinReadLengthKB() != desired.getMinReadLengthKB()
                    || actual.getDefaultReadLengthKB() != desired.getDefaultReadLengthKB()
                    || actual.getMaxBufferPoolSizeMB() != desired.getMaxBufferPoolSizeMB()) {
                throw rejected("CD2 下载设置未按计划生效");
            }
            return null;
        });
    }

    /** 返回只表示请求接受；调用者必须另行读取真实云盘根，不能推测名字。 */
    public void connectWebDav(CloudDrive2Properties connection, String serverUrl, String username, String password) {
        call(connection, stub -> {
            if (!stub.aPILoginWebDav(LoginWebDavRequest.newBuilder().setServerUrl(serverUrl)
                    .setUserName(username).setPassword(password).setDoNotSyncToCloud(true).build()).getSuccess()) {
                throw rejected("CD2 WebDAV 登录被拒绝，请检查授权或服务版本");
            }
            return null;
        });
    }

    /** 完整挂载配置由经核实的部署模板提供，不在此补造 uid/gid/permissions。 */
    public void createMount(CloudDrive2Properties connection, MountOption option) {
        call(connection, stub -> {
            if (!stub.addMountPoint(option).getSuccess()) {
                throw rejected("CD2 系统挂载创建被拒绝");
            }
            return null;
        });
    }

    public void mount(CloudDrive2Properties connection, String mountPoint) {
        call(connection, stub -> {
            if (!stub.mount(MountPointRequest.newBuilder().setMountPoint(mountPoint).build()).getSuccess()) {
                throw rejected("CD2 系统挂载启动被拒绝");
            }
            return null;
        });
    }

    /** 调用者已用创建前基线证明 root 是本次新增资源；目的根须先确认未被其他云盘占用。 */
    public void renameCreatedCloudRoot(CloudDrive2Properties connection, String createdPath, String desiredPath) {
        if (createdPath.equals(desiredPath)) { return; }
        if (!createdPath.matches("/[^/]+") || !desiredPath.matches("/[^/]+")
                || desiredPath.equals("/.") || desiredPath.equals("/..")) {
            throw new BusinessException(ErrorCode.CONFLICT, "CD2 云盘根重命名仅支持直接根目录", HttpStatus.CONFLICT);
        }
        Resources before = readResources(connection);
        if (before.clouds().stream().anyMatch(c -> desiredPath.equals(c.path()))) {
            throw new BusinessException(ErrorCode.CONFLICT, "CD2 计划云盘根已被其他资源占用，未覆盖", HttpStatus.CONFLICT);
        }
        if (before.clouds().stream().filter(c -> createdPath.equals(c.path()) && !c.locked()).count() != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "CD2 本次新增云盘身份无法唯一读回，未重命名", HttpStatus.CONFLICT);
        }
        call(connection, stub -> {
            if (!stub.renameFile(RenameFileRequest.newBuilder().setTheFilePath(createdPath)
                    .setNewName(desiredPath.substring(1)).build()).getSuccess()) {
                throw rejected("CD2 云盘根重命名被拒绝；未修改其他云盘");
            }
            return null;
        });
        if (readResources(connection).clouds().stream().filter(c -> desiredPath.equals(c.path()) && !c.locked()).count() != 1) {
            throw rejected("CD2 云盘根重命名后尚无法唯一读回，请核对后继续");
        }
    }

    public void verifyDirectory(CloudDrive2Properties connection, String path) {
        call(connection, stub -> {
            var replies = stub.getSubFiles(ListSubFileRequest.newBuilder().setPath(path).setForceRefresh(true).build());
            while (replies.hasNext()) { replies.next(); }
            return null;
        });
    }

    private <T> T call(CloudDrive2Properties connection, Function<CloudDriveFileSrvGrpc.CloudDriveFileSrvBlockingStub, T> action) {
        if (!StringUtils.hasText(connection.getHost()) || !StringUtils.hasText(connection.getApiToken())) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "CD2 有效连接未配置", HttpStatus.SERVICE_UNAVAILABLE);
        }
        ManagedChannel channel = ManagedChannelBuilder.forAddress(connection.getHost(), connection.getPort()).usePlaintext().build();
        try {
            Metadata headers = new Metadata();
            String token = connection.getApiToken().trim();
            headers.put(Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER),
                    token.regionMatches(true, 0, "Bearer ", 0, 7) ? token : "Bearer " + token);
            var stub = CloudDriveFileSrvGrpc.newBlockingStub(channel)
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                    .withDeadlineAfter(connection.getOperationTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return action.apply(stub);
        } catch (StatusRuntimeException exception) {
            // description 可含上游认证细节，仅保留 gRPC 状态枚举。
            throw switch (exception.getStatus().getCode()) {
                case UNAUTHENTICATED -> new BusinessException(ErrorCode.UNAUTHORIZED,
                        "CD2_UNAUTHENTICATED: CD2 认证失败", HttpStatus.BAD_GATEWAY);
                case PERMISSION_DENIED -> new BusinessException(ErrorCode.FORBIDDEN,
                        "CD2_PERMISSION_DENIED: CD2 令牌没有此操作权限", HttpStatus.BAD_GATEWAY);
                case UNIMPLEMENTED -> new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                        "CD2_UNIMPLEMENTED: 当前 CD2 版本不支持所需资源接口", HttpStatus.SERVICE_UNAVAILABLE);
                default -> rejected("CD2_" + exception.getStatus().getCode().name() + ": CD2 协议调用失败");
            };
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "CD2_CONFIGURATION_INVALID: CD2 有效连接格式无效",
                    HttpStatus.SERVICE_UNAVAILABLE);
        } finally {
            channel.shutdownNow();
        }
    }

    private BusinessException rejected(String message) {
        return new BusinessException(ErrorCode.BAD_GATEWAY, message, HttpStatus.BAD_GATEWAY);
    }
}
