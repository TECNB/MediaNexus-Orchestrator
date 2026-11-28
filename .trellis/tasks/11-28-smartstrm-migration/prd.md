# AS → SmartStrm 兼容迁移

## 目标与边界

增加默认关闭的 SmartStrm v0.5.1 后端，内部复用原 AS 的公开方法、路由、完整表单、任务 UUID、动态账号接入和原 STRM 输出路径。关闭 Smart 时保留旧 AS 行为与重试规则；不改现有 Quark 的 SmartStrmWebhookClient，不改 UI、部署脚本和无关历史。本任务只在隔离 worktree 本地实现、验证和提交，不运行生产操作，不推送、合并或部署。

## 配置与运行

- 管理 API 使用正常表单登录与会话 Cookie。读取认证失效后最多恢复一次；写入结果未知不自动重放。HTTP 2xx 的 JSON `success:false` 仍为失败，错误不泄露正文、URL、凭据或 Webhook secret。
- 每个迁移任务保留完整 `legacy_form`，读回对比真实 task、storage 和原输出别名。输入是原 CD2 本地只读挂载，输出通过 UUID 别名指向原目录。动态接入保留原始全表单及输出布局；必须拒绝路径越界、配置漂移和未支持的 cloud 模式。
- `scan_filter.media_ext/copy_ext` 为逗号字符串，`symlink_size` 映射到 MB 最小值。禁用生成／复制用明确的非匹配扩展名，不能用空字段触发 Smart 全局回退。字幕上传通过单次运行插件覆盖复制字幕，任务基础配置不变。
- 所有执行使用官方 `a_task`，从认证 `/api/settings` 取得 Webhook 的路径，仅连接配置的 API origin；普通局部和手动全类别刷新使用 `incremental=true`，不在普通刷新中清理。
- 迁移任务 `crontab=''`、`watchdog=false`。Java 每天上海时间 02:30 调度仅包含迁移元数据且 `sync_enabled && sync_scheduled` 的任务，整根 `incremental=false, keep_local_asset=true`；不接 CD2 Webhook，不加文件系统 watcher。

## 完成与 Emby

运行前记录所选原输出子树的 STRM 快照和任务结束日志基线。受理后异步只读观察任务；新增结束记录足够覆盖提交次数，并连续两次 idle 且路径快照稳定后，发送现存 STRM 的 Created（包含未修改文件，支持幂等与仅字幕变化），以及运行前存在但已移除的 Deleted。日志只确认执行生命周期，不证明文件复制成功；输出路径与后续 Emby 查询仍为验收依据。历史日志或请求受理不能冒充本次结束，不遍历整棵 CloudNAS、不读取视频内容。

观察有界：深度 32、每子树最多 10,000 项、128 个任务、每任务最多 128 个路径；同名重叠运行共享一个观察者，通知路径去重，每批最多 200。空结果及时结束，超时保留未核验状态；未知生成只读观察，未知通知不盲重放。观察状态仅在内存，重启不会恢复。

同 basename MKV/MP4 按 SmartStrm 自身选择目标，用户接受偶发选择变化；不新增冲突策略、隔离或阻塞。

## 验收

使用 Java 17 和已批准的 SmartStrmClient、SmartStrmAccountClient、SmartStrmGeneration、AutoSymlinkRefreshService 公共边界，先用具体缺陷测试证明失败再修复。仅运行相关选择，补充字幕入口的两个原测试，不跑全套。合同见 contract.md，准确命令、结果与部署前提见 verification.md；本地 mock 通过不表示已部署或已通过真实实例验收。
