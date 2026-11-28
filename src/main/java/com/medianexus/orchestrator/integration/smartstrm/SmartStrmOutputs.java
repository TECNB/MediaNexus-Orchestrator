package com.medianexus.orchestrator.integration.smartstrm;

import com.medianexus.orchestrator.config.SmartStrmProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/** SmartStrm 全局输出别名只可指向允许根内的原始 STRM 目录，不覆盖任何现存文件或目录。 */
@Component
public class SmartStrmOutputs {
    private final SmartStrmProperties properties;
    public SmartStrmOutputs(SmartStrmProperties properties) { this.properties = properties; }
    public boolean verifyDirectory(String directory) {
        if (!Path.of(directory).startsWith(Path.of(properties.getPermittedOutputRoot()))) return false;
        try {
            if (!Files.isDirectory(permitted(directory))) throw SmartStrmClient.failure("SmartStrm 输出目录未挂载或不可读取");
            return true;
        } catch (IOException exception) { throw SmartStrmClient.failure("SmartStrm 输出目录无法核验"); }
    }


    public Path prepare(String uuid, String legacyDirectory) {
        requireUuid(uuid);
        try {
            Path target = permitted(legacyDirectory);
            Path aliases = permitted(properties.getOutputAliasRoot());
            if (target.startsWith(aliases) || aliases.startsWith(target))
                throw SmartStrmClient.failure("SmartStrm 原输出不能与别名根重叠");
            if (!Files.isDirectory(aliases) || !Files.isDirectory(target.getParent())) {
                throw SmartStrmClient.failure("SmartStrm 输出父目录未挂载或不存在");
            }
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(target);
            if (!Files.isDirectory(target) || !target.toRealPath().startsWith(root())) {
                throw SmartStrmClient.failure("SmartStrm 原输出不是允许根内的目录");
            }
            Path alias = aliases.resolve(uuid);
            if (!Files.exists(alias, LinkOption.NOFOLLOW_LINKS)) Files.createSymbolicLink(alias, target);
            verify(uuid, legacyDirectory);
            return target;
        } catch (IOException exception) { throw SmartStrmClient.failure("SmartStrm 输出别名准备失败，未覆盖现存路径"); }
    }

    public Path verify(String uuid, String legacyDirectory) {
        requireUuid(uuid);
        try {
            Path target = permitted(legacyDirectory);
            Path alias = permitted(properties.getOutputAliasRoot()).resolve(uuid);
            Path aliases = alias.getParent();
            if (target.startsWith(aliases) || aliases.startsWith(target))
                throw SmartStrmClient.failure("SmartStrm 原输出不能与别名根重叠");
            if (!Files.isDirectory(target) || !Files.isSymbolicLink(alias)
                    || !alias.toRealPath().equals(target.toRealPath())) {
                throw SmartStrmClient.failure("SmartStrm 输出别名与原输出路径不一致");
            }
            return target;
        } catch (IOException exception) { throw SmartStrmClient.failure("SmartStrm 输出路径无法核验"); }
    }

    private Path permitted(String value) throws IOException {
        Path path = Path.of(value);
        if (!path.isAbsolute() || !path.normalize().equals(path) || path.equals(root()) || !path.startsWith(root())) {
            throw SmartStrmClient.failure("SmartStrm 输出路径超出允许根");
        }
        Path existing = path;
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        if (!existing.toRealPath().startsWith(root())) throw SmartStrmClient.failure("SmartStrm 输出路径符号链接越界");
        return path;
    }

    private Path root() throws IOException {
        Path root = Path.of(properties.getPermittedOutputRoot());
        if (!root.isAbsolute() || !root.normalize().equals(root) || !Files.isDirectory(root)
                || !root.toRealPath().equals(root)) throw SmartStrmClient.failure("SmartStrm 允许输出根未挂载或包含符号链接");
        return root;
    }

    static void requireUuid(String uuid) {
        try { if (!java.util.UUID.fromString(uuid).toString().equalsIgnoreCase(uuid)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException | NullPointerException exception) { throw SmartStrmClient.failure("SmartStrm 任务 UUID 无效"); }
    }
}
