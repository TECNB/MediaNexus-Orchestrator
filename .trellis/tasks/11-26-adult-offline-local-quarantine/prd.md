# Adult 异常任务局部隔离

## 范围
仅修改 Adult 启动恢复，不修改共享调度器、外部 OpenList、任务状态表或 LTX 替换草稿。

已结束的 OFFLINE_UNRESOLVED 批次，其提交/成功/失败数量完整、失败列表与原 hash 匹配、账号和目录身份明确时，复用现有 recovery claim 保留失败 hash 及旧目标目录。其他不冲突目录可继续提交。旧目标目录（含子目录及挂载别名）仍保守封锁。

活动写入、身份不明、计数不全、JSON 损坏、hash 不一致继续全局暂停。重启从原任务证据重建隔离，不以 404 或空队列为停止证明，不自动重试、取消、删除或解封。保留历史状态、计数、错误和时间。

## 验证
定向运行 AdultOfflineQuarantineTest、AdultMagnetIngestServiceTest、OfflineIngestSchedulerTest。
MagnetRestartReconciliationTest 的 terminalOriginalTaskIsRecordedWithoutRetryWhileUnknownRemainsBlocked 在未修改 b57459e 上亦报“剧集任务检查点未持久化，保留认领”；记录为独立基线测试错误，不修改其业务逻辑。

## 不包含
不提供客户端证据解封接口，不完成 LTX 替换，不删除旧文件。生产发布尚未执行。
