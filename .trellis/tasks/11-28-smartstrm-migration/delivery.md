# 本地交付与后续合并边界

本次用户授权仅在独立 worktree 实现并提交。不得将本轮完成等同线上接管。

- worktree：`/Users/tengenchang/.pi-desktop/scratch/f6321eed-dc15-415f-8538-e70a09bd8fcb/smartstrm-migration`
- 分支：`feat/smartstrm-as-migration`
- 实现基线：`d657185`
- 主工作区和另一窗口改动不合并、不覆盖。
- 本轮不推送、不合并主要分支、不部署、不操作服务器任务/配置。
- 用户已接受同名 MKV/MP4 共用 basename STRM 时，目标按 SmartStrm 当前行为变化；不额外加入格式优先级、文件特判或隔离规则。

## 获得后续部署授权时再做

1. 核对主要分支最新提交及另一任务结果。先比较重叠文件，重点是 `AutoSymlinkRefreshService`、`EmbyClient`、`AutoSymlinkAccountClient`、账号接入服务和 `application.yml`。明确合并后的逻辑，再按实际冲突做定点复测。不要覆盖整文件。
2. 以当时生产配置生成迁移任务。预演记录在 Media 根 `diagnostics/smartstrm-migration-2026-11-28.md` 和此前实测报告；旧计划文件是证据，不能直接当最新配置执行。
3. 给 SmartStrm 只读映射实际 CloudNAS 源目录，并给 SmartStrm、编排器在同名绝对路径映射原 STRM 根。输出任务别名指向原目录，保留文件名和 Emby 路径。新别名不得与实际库根重叠，不允许 AS/Smart 同时写同一输出。
4. 程序启用 SmartStrm 后接管主动刷新和定时同步；迁移任务关闭 Smart 自己的 cron/watchdog。现有非迁移夸克任务保持原调度与 webhook。
5. 使用新的环境配置启用 SmartStrm，保留旧 AS 连接与实现用于回退。认证值只进入私有部署配置，不写文档或提交。
6. 备份原 AS/Smart 配置和需要保护的输出后，逐库停用对应 AS 执行并验收新客户端。配置回退须同时恢复对应执行方，不能只切一个标志而留下两套任务双写。
7. 验证控制台四类完整刷新、自动作品子目录刷新、字幕更新、定时新增与删除通知、账号配置读回。确认 `SUBMITTED` 仍只表示受理，不替代 STRM/Emby 验收。
8. 部署按届时生效的项目工作约定执行原 `deploy.sh`，记录版本、构建/切换/健康与业务验收；本地定点通过不能冒充线上验收。

本轮没有预先消除主要分支可能发生的冲突；后续授权部署时的合并检查是必要步骤，不是本次本地提交的缺失。
