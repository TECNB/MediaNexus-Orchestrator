# SmartStrm v0.5.1 迁移合同（本地实现，未部署）

保留 AS 的公开方法／路由、数据库 UUID 绑定和完整原始表单。只在 `medianexus.smartstrm.enabled=true` 时委派 Smart；默认关闭，AS 旧路径和重试行为保留。现有 Quark `SmartStrmWebhookClient` 不变，无 `legacy_form` 的 Smart 任务不伪造成 AS 任务，也不纳入迁移定时同步。

## 环境与准备

配置默认值唯一来源为 `src/main/resources/application.yml`，`.env.production.example` 提供无秘密示例：

| 环境变量 | 默认值／用途 |
| --- | --- |
| MEDIANEXUS_SMARTSTRM_ENABLED | false |
| MEDIANEXUS_SMARTSTRM_BASE_URL | 空；内部 API origin，可填写原浏览器 base URL |
| MEDIANEXUS_SMARTSTRM_USERNAME / PASSWORD | 空；正常管理员表单登录 |
| MEDIANEXUS_SMARTSTRM_TIMEOUT | 15s |
| MEDIANEXUS_SMARTSTRM_OUTPUT_ALIAS_ROOT | /srv/media/STRM/SmartStrm |
| MEDIANEXUS_SMARTSTRM_PERMITTED_OUTPUT_ROOT | /srv/media/STRM |
| MEDIANEXUS_SMARTSTRM_COMPLETION_TIMEOUT | 5m，故障观察上限；未确认本次执行结束时不提前通知 |
| MEDIANEXUS_SMARTSTRM_POLL_INTERVAL | 2s |
| MEDIANEXUS_SMARTSTRM_SYNC_CRON | `0 30 2 * * *`，Spring 六字段（含秒），等价五字段 `30 2 * * *` |
| MEDIANEXUS_SMARTSTRM_SYNC_ZONE | Asia/Shanghai |

上线准备必须完成：

1. Smart 将原 `legacy_form.media_dir` 的本地 CD2 输入按相同绝对路径**只读挂载**；无整库视频读取，无 Docker API 或 docker binary 依赖。
2. 后端与 Smart 均按原绝对路径挂载整个允许输出根，Smart `/strm` 对应 output-alias-root。准备 `<output-alias-root>/<uuid>` 绝对符号链接，精确指向原 `legacy_form.symlink_dir`，保留原输出文件名及路径。原输出与 alias 根必须不重叠，均位于允许输出根内；不覆盖已有别名冲突。alias 根及原输出父目录须预先存在，动态接入可创建最后一级输出目录。
3. 初次动态接入前需有可 browse 的本地 bootstrap storage，根为 `/srv/media/CloudNAS`；可无对应 task。目录验证只 browse 所选新目录。
4. 迁移已有 task 时保存完整 typed `legacy_form`，同步下述真实配置；迁移任务自身 `crontab=''`、`watchdog=false`，避免第二个调度器。原 AS observer/checker/backup 等表单保留为元数据，不启用 Smart watchdog。

## Storage 与只读响应

POST `/api/storages/<uuid>`：

```json
{"name":"<uuid>","driver":"local","root_path":"<legacy_form.media_dir>","compatibility":true}
```

GET `/api/storages` 仅接受字段直接位于条目的 JSON 数组，不猜测其他包装。GET `/api/tasks` 接受以任务名为 key 的对象，条目含 `config` 和布尔 `running`。browse 为 GET `/api/storage/<storage-name>/browse?path=<relative>`，仅接受数组或含 `files`／`items` 数组的对象。任何 JSON `success:false`（包括带空 files 的失败响应）都拒绝。

## Task 配置与读回核验

POST `/api/tasks/<uuid>` 示例：

```json
{
  "name":"<uuid>","storage":"<uuid>","storage_path":"/","crontab":"",
  "incremental":true,"dir_time_check":false,"keep_local_asset":true,"watchdog":false,
  "plugins":{
    "custom_strm_name":{"enabled":true,"custom_name":"{name}.strm"},
    "scan_filter":{
      "enabled":true,"media_size_min":100,"media_size_max":"",
      "copy_size_min":"","copy_size_max":"",
      "media_ext":"mkv,mp4","copy_ext":"nfo,srt"
    }
  },
  "legacy_form":{"...":"完整原始 typed AS 表单"}
}
```

- `symlink_size` 的 MB 最小值映射 `media_size_min`；必须为非负整数字符串，超范围拒绝。上例对应 `symlink_size="100"`。
- `media_ext/copy_ext` **是字符串，不是数组**。从 `symlink_ext/metadata_ext` 按逗号、分号、空白拆分，去前导点，保持顺序并去重。
- Smart 空媒体／复制扩展名会回退全局。`symlink_creator=false` 使用 `media_ext="medianexus-no-media"`，`metadata_copyer=false` 使用 `copy_ext="medianexus-no-copy"`；启用但扩展名为空也使用对应非匹配哨兵，不意外扩大全局范围。这是确定性扩展名哨兵，不是新增 Smart 字段，约定源中不存在这两种后缀。复制大小上下界为空，实际选择由显式 copy_ext 决定，不使用极小大小阈值伪装禁用。
- 支持已核验的 `symlink_mode=strm, strm_mode=local`；cloud（包括旧 Quark AS cloud）和非空 exclude_folder 显式拒绝，不能声称语义已迁移。
- 完整 `legacy_form` 含原 display name、UUID 绑定相关全表单、CONFIG_STRINGS/CONFIG_BOOLEANS，API 必须原样保存。真实 listed task 字段、整个 plugins 对象、storage 配置及本地 alias 均须与表单映射一致；允许额外顶层 task/storage 字段。漂移拒绝，不仅信任元数据。
- 同 basename MKV/MP4 沿用 Smart 自身选择目标，用户已接受偶发变化；不加冲突／隔离策略，不视为阻塞。

## 会话与官方运行入口

POST `/login` 表单 username/password，消费会话 Cookie，不跟随重定向；用 GET `/api/tasks` 确认登录。GET 遇 401/302/303/login HTML 仅恢复登录并重试一次；写入认证失效可恢复会话供后续只读核验，但原写入仍返回未知，绝不重放。HTTP 非 2xx、网络异常及业务 `success:false` 均失败，不输出原始 URL／正文／凭据／Cookie。

认证 GET `/api/settings` 读取 `webhook.url`。仅缓存匹配 `/webhook/[A-Za-z0-9_-]+` 且无 query／fragment 的 raw path，用**配置的 API origin**替换其浏览器 origin；不连接返回 URL 的任意主机，不输出 secret。官方 POST 到该路径：

```json
{
  "event":"a_task","delay":0,"debounce":0,
  "task":{
    "name":"<uuid>","storage_path":"/Film (2026)",
    "incremental":true,"keep_local_asset":true,"dir_time_check":false,
    "plugins":{"...":"完整映射的两个插件"}
  }
}
```

普通 path-based 和手动全类别刷新均增量，整类别范围用 `/`。原 request.media_dir 是本地挂载路径，须为任务根或规范子目录，拒绝 `..`／`.` 跳转。组织刷新仍先 refresh CD2 挂载目录，随后只提交一次。

每次 run 可用请求中的 typed `symlink_creator/metadata_copyer/symlink_ext/metadata_ext/symlink_size` 覆盖插件过滤；基础任务配置仍按原 form 核验，不持久化运行覆盖。字幕上传走 `refreshAfterSubtitleUpload`，将 metadata_copyer 临时设为 true、metadata_ext 为 `.ass,.srt,.sup`；关闭 Smart 时仍调用原 AS 刷新，不改变原 body。

Java `synchronizeAll` 按 YAML/env 的唯一日程调用，仅选 `legacy_form.sync_enabled && legacy_form.sync_scheduled`，整根 `incremental=false, keep_local_asset=true`，使用相同运行／观察／通知链路。`legacy_form.sync_time` 保留但不再驱动 Smart cron。无 CD2 推送事件接入，无文件系统 watchdog。

## 完成观察与 Emby

提交前仅快照所选原输出子树的非空普通 STRM 文件路径与属性，不读取视频或全 CloudNAS，同时保存任务日志的结束记录基线。受理／结果未知后异步读取 task.running；只有新增结束记录足够覆盖本观察器提交次数，且两次连续 idle 的输出快照稳定，才进入路径核对和通知。普通日志即使没有换行也按结束标记计数；单次运行日志截断时可用新的带时间结束记录补证。结束记录只证明执行生命周期已收尾，不证明所有文件复制成功；输出文件与后续 Emby 查询仍是验收依据，历史日志和单纯 idle 都不能作为本次结束证据。

结束时对该范围**全部现存 STRM**发送精确原路径 Created，包含 mtime 不变的文件，以支持幂等手动刷新和仅字幕／辅助文件更新。对运行前存在但结束已移除的 STRM 发送 Deleted。每批最多 200 路径，路径去重；未知 Emby 响应不盲重放，其他独立批次仍只提交一次。

边界：每子树深度 32、最多 10,000 entries、最多 128 pending 任务／每任务 128 个所选子树。同名重叠提交仍各执行一次，合并到单个观察者、共同等待 idle、路径去重通知；不会为同一运行注册重复观察者。空结果与幂等运行正常结束；超时结束为未核验。只存在内存，后端重启会丢失待观察状态；Smart UI 外部运行不注册 Java 观察者。

SmartStrm 没有独立运行 ID；外部同时运行和日志滚动导致证据不完整时，可能等待至超时，保持未核验，不盲目重跑。路径存在不能单独证明每个元数据文件复制成功；需真实实例检查字幕结果。不能把 HTTP 受理、日志或 mock 成功说成端到端交付成功。定时调度逐任务核验配置，单项漂移不阻断其他正常任务。

## 真实实例预检仍需完成

启用前确认真实 `/api/storages` 数组与 browse/settings/tasks 形状、任意 legacy_form 保存、scan_filter 字段类型与实际过滤效果（尤其非零 MB 大小和非匹配复制哨兵）、官方 plugins 单次覆盖、原输出 alias 跟随／挂载权限、任务根和子目录布局、STRM 直链内容、字幕实际复制及 Emby 更新／删除。测试仅使用本地 HTTP fixture 与临时输出目录，本任务没有运行这些生产操作，没有已部署声明。
