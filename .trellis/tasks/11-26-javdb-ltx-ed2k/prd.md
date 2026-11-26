# JavDB LTX 最高优先级与原生 ED2K 入库

用户授权直接开发并按共用工作约定部署验收。基线为生产 f6eed5e；主工作区改动保持原样。

- 优先级：LTX > 破解+中文字幕 > 中文字幕 > 破解 > 普通。同档保留体积降序、页面顺序。字幕/破解限制仍生效。
- JavDB 详情候选同时接收 magnet 和合法 ED2K；ED2K 使用原始链接，文件名与精确大小来自链接，独立资源键为 ed2k:<hash>。同协议同键去重，跨协议不错误合并。
- 复用 Adult 原提交/调度/整理/元数据链路，ED2K 通过现有配置使用 PikPak；同步默认值和实际配置，不改变 OpenList 镜像或另一个账号。
- 元数据准备支持从 ED2K 文件名提取准确番号、字幕及 LTX；非法链接或身份冲突拒绝猜测。
- 选源策略快照有版本；旧试运行不能被新规则正式执行复用，历史保留。
- 不扩展电影/剧集/动漫入口，不批量重下载已有资源，不重放未知写操作。

验证边界：JavdbClient.detail、自动化试运行结果、Adult 提交接口和元数据版本解析。先红后绿，最小用例；一次定向只读审查。发布执行原 deploy.sh，串行构建。真实验收只提交一条已核对未入库资源，另验证受控失败可见；未知结果停止并保留证据。完整媒体交付需 Cloud/STRM/Emby 身份、字节与有界可读性复核；下载完成不冒充成功入库。

研究依据：用户提供的 /private/var/folders/59/sjwxmgxx6k5crpg89pkdk5500000gn/T/ed2k-pikpak-native-handoff.md；Media 根 diagnostics/openlist-v4.2.10-upgrade-2026-11-26/升级记录.md、diagnostics/javdb-ltx-share-2026-11-26.md。
