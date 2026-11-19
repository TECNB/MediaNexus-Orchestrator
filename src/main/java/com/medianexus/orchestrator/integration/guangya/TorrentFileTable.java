package com.medianexus.orchestrator.integration.guangya;

import com.medianexus.orchestrator.integration.pansou.PanSouMagnetIdentity;
import java.util.List;

/** 已核对上游声明 hash、相对路径、叶子数量及字节和；不代表媒体内容或原始 torrent hash 重算验证。 */
public record TorrentFileTable(PanSouMagnetIdentity identity, String rootName, List<Entry> files, long totalBytes) {
    public TorrentFileTable { files = List.copyOf(files); }
    /** 多文件路径相对于根目录；单文件路径就是根文件名。 */
    public record Entry(String path, long sizeBytes) {}
}
