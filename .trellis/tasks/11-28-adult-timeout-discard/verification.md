# 验证与发布边界

- 先新增调度器超时放弃用例，当前实现缺少DiscardableDownload/有界查询时编译失败；补实现后通过。
- Java17定向回归50项通过：OfflineIngestSchedulerTest35、AdultDownloadRestartTest1、AdultMagnetIngestServiceTest13、OpenListDownloadDiscardTest1。限定审查两项P2：失败检查点恢复缺失、清理原因丢失；均修复。随后针对双资源批次（正常/超时+数据库响应丢失）、启动恢复、HTTP清理复测通过。再增无响应取消/删除共用5秒预算用例通过。未运行全项目测试。
- 一项超时一项成功的真实调度器批次测试：submitted2/succeeded1/failed1、PARTIAL_SUCCESS，成功资源保留，仅超时项调用discard一次；数据库写入响应丢失后恢复不重复计数或清理。
- 编译本次Java调度器与客户端，连接真实OpenList/PikPak创建独立无种子磁力。120秒下载期限；从入队（包括目录/账号映射准备）到结束127.041秒。取消与清理ACKNOWLEDGED，临时目录不存在、claim_released=true。测试父目录删除并fresh listing确认不存在，unfinishedOfflineTaskCount=0。原始日志在当前会话scratch/adult-java-live.log。未调用业务Adult创建接口，不能冒称生产容器版端到端已验收。
- git diff --check通过；源码retainFailedDownloadClaims/adult-offline认领已移除。Movie/TV原取消结清逻辑保留，相关调度器回归通过。
- API只读确认旧批次152b4267仍PARTIAL_SUCCESS、3提交/2成功/1失败，失败临时目录仍含DPMX015C子目录；未删除旧资源、未改状态或计数。新代码不再由终态错误重建隔离。
- 生产仍有Adult任务3b753b15处于DOWNLOADING/OFFLINE_UNRESOLVED，电影PENDING3/ORGANIZING2、剧集PENDING2/ORGANIZING1；OpenList五类队列均0。依工作约定，无活动写入窗口前不切换容器，本轮未运行deploy.sh、未重启。没有擅自取消/改写这些任务。
