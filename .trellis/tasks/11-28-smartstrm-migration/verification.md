# 本地定点验证

工作树：`/Users/tengenchang/.pi-desktop/scratch/f6321eed-dc15-415f-8538-e70a09bd8fcb/smartstrm-migration`；分支 `feat/smartstrm-as-migration`，实现 base `d657185`。仅本地提交，未推送／合并／部署。Java 17：`/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`。

## 本轮 red → green

以下命令均在隔离工作树运行，统一前缀：

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home mvn -q
```

1. **Red（预期 4 failures，0 errors）**：先增加具体缺陷断言，证明原草稿不能拒绝 JSON success:false、扩展名仍为数组、禁用复制为空回退、mtime 不变的幂等运行无通知：

```sh
'-Dtest=SmartStrmClientTest#rejectsJsonFailureForWritesAndBrowseWithoutSensitiveBody,SmartStrmAccountClientTest#readsLegacyFormOnlyWhenActualStorageTaskAndAliasMatch+disabledCopyingNeverFallsBackToGlobalExtensions,SmartStrmGenerationTest#idempotentAndSubtitleOnlyRunsNotifyExistingMediaAndFinish' test
```

2. 实现修复后运行：

```sh
'-Dtest=SmartStrmClientTest,SmartStrmAccountClientTest,SmartStrmGenerationTest' test
```

初次出现两个测试夹具问题：旧 AutoSymlinkClient 夹具未设置构造所需 timeout；Mockito after+captor 在等待中重复捕获导致分块大小断言重复。修正夹具／捕获后运行第 3 项全部通过，无未处理失败。

3. **Passed**（HTTP/client、mapping、completion 与指定 AS 回归）：

```sh
'-Dtest=SmartStrmClientTest,SmartStrmAccountClientTest,SmartStrmGenerationTest,AutoSymlinkClientTest,AutoSymlinkRefreshServiceTest#smartRefreshUsesLocalSubdirectoryWithoutAsCredentialsAndDoesNotReplayUnknownWrite+smartOrganizationRefreshKeepsOneShotAndUsesResolvedSubdirectory+retriesConfiguredRefreshUntilSubmitted+usesOriginalAndExplicitTargetBindingsWithFullJsonWhileNoTargetKeepsLegacy+refreshesPlaybackDirectoryBeforeSubmittingAndReportsRefreshFailure' test
```

4. **Passed**（后续同名观察者合并、字幕专用调用与旧路径保留）：

```sh
'-Dtest=SmartStrmGenerationTest,AutoSymlinkRefreshServiceTest#subtitleUploadEnablesPerRunCopyAndDisabledSmartKeepsLegacyBody,SubtitleUploadServiceTest#backgroundProcessingUploadsSubtitleThenRefreshesMovieAutoSymlink+backgroundProcessingRefreshesSeriesAutoSymlinkForSeriesSubtitle' test
```

5. **Passed**（最终补充非迁移任务忽略／cloud 与路径拒绝、重叠子树通知去重、定时清理 Deleted 与保留资产后，仅复测受影响选择）：

```sh
'-Dtest=SmartStrmAccountClientTest,SmartStrmGenerationTest' test
```

覆盖行为：正常登录／Cookie、只读认证恢复、401/500 写入不重放、HTTP 2xx success:false 拒绝且错误脱敏；从认证 settings 取 Webhook raw path 并固定 API origin，官方 a_task exact payload 与 once-only submission；字符串扩展名、非零 MB 最小值、禁用复制／生成哨兵、真实 task/storage/plugin/alias 漂移拒绝、原输出文件名保留、动态接入 browse；未知生成继续只读观察、初始 idle→running→idle、两个稳定 idle、幂等／仅字幕 Created、删除 Deleted、空运行及时结束、200/200/5 分块、未知通知不重放、同名重叠范围合并去重；定时仅 eligible 迁移任务、整根非增量与 keep_local_asset；字幕专用入口临时启用复制、禁用 Smart 保留原 AS body、组织/CD2 先刷新和旧 AS 重试／绑定行为。

Maven 同时编译 main/test 源码。最终 `git diff --check` 与 `python3 .trellis/scripts/task.py validate .trellis/tasks/11-28-smartstrm-migration` 均通过。未跑全套、全量 lint、package build，未启动应用，无服务器／生产网络操作，无提交／推送／合并／部署；本地 HTTP fixture 不代表真实上游验收。

## 交付前提与真实剩余项

- 启用前按 contract.md 准备原输入只读挂载、完整原输出根、UUID→原目录 alias 和 browse bootstrap storage；已有任务需按最终字符串插件配置迁移，crontab 清空，watchdog=false。
- 实例存储列表／settings/browse 形状、legacy_form 保留、过滤哨兵与 MB 阈值的实际效果、单次插件覆盖、字幕真实复制、alias 跟随与输出布局、Emby 真正接收／刷新／删除仍需真实实例预检；没有已部署声明。
- Cloud 模式和非空 exclude_folder 显式不支持，不冒充已迁移。旧 Quark SmartStrmWebhookClient 未修改。
- 执行结束改为新增结束记录加连续稳定 idle 和输出路径核对；不再仅以两次 idle 或 running→idle 结束。上游无运行 ID、外部并发与日志滚动导致证据不足时可能超时；后端重启丢失内存观察，细节见合同。
- 同 basename MKV/MP4 选择变化已获用户接受，不是 blocker，不新增冲突／隔离策略。

## 限定审查修正与最终验证

单轮审查发现排队时提前通知和单个配置异常阻断全部定时任务；同时补正后缀正则空白解析。

- 新增 `doesNotFinishWhileAcceptedTaskIsStillQueued` 先运行，预期 red：1 failure / 0 errors，确认旧两次 idle 逻辑提前结束。
- 修正为新增执行结束记录加连续稳定输出；新增仅历史日志仍 idle 不通知，以及无换行结束标记、多次触发、单项漂移不阻断健康任务断言。
- 最终命令：

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home mvn -q '-Dtest=SmartStrmClientTest,SmartStrmAccountClientTest,SmartStrmGenerationTest,AutoSymlinkClientTest,AutoSymlinkRefreshServiceTest,SubtitleUploadServiceTest#backgroundProcessingUploadsSubtitleThenRefreshesMovieAutoSymlink+backgroundProcessingRefreshesSeriesAutoSymlinkForSeriesSubtitle' test
```

最终通过 33 项：Smart HTTP 7、任务配置 6、生成／同步 9、AS HTTP 1、刷新兼容 8、字幕上传调用 2；0 failures、0 errors、0 skipped。未运行全项目测试、package build 或应用真实启动；没有服务器操作。
