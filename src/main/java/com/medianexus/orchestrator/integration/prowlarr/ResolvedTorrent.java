package com.medianexus.orchestrator.integration.prowlarr;

import com.medianexus.orchestrator.integration.guangya.TorrentFileTable;
import java.util.Optional;

/** 搜索已知 BTIH 与解析所得身份不得冲突；仅原始 torrent 完整安全解析成功时携带文件表。 */
public record ResolvedTorrent(String magnet, Optional<TorrentFileTable> table) {}
