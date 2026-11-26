# 验证结果

- Java 17 定向命令：`mvn -q -Dtest=AdultOfflineQuarantineTest,AdultMagnetIngestServiceTest,OfflineIngestSchedulerTest test`。
- 最终复跑 50 项：Adult 隔离 4、Adult 原回归 12、共享调度器 34；失败 0，错误 0。`git diff --check` 通过。
- 前一轮调度器异步测试 blockedOrganizationReleasesDownloadSlotButKeepsDirectoryAndHashClaims 等待失败；共享调度器未修改，最终整组复跑通过，保留波动记录。
- 独立剧集恢复测试 terminalOriginalTaskIsRecordedWithoutRetryWhileUnknownRemainsBlocked 仍报检查点未持久化；在原 b57459e 的独立 worktree 复现同样错误，不混入本修复。
- 新测试调用真实调度器，验证两次启动重建隔离、失败 hash/旧目标子目录不可用、不同 hash/目录可提交、不完整证据仍全局暂停、原状态计数错误不变。
- 仅本地模拟，没有生产 API/数据库或云端写入；未发布、未部署。
- 刻意保守：旧目标日期目录整体继续隔离，同日共享旧目录的无关资源仍暂缓；不同目录可继续。没有新增解封入口。
- 早期失败的 scheduler 编辑位于另一废弃 worktree，已恢复原文件；不在本修复中。原 LTX replacement 草稿保持原样。
