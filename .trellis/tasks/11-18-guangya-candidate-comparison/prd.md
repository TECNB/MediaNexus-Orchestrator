# 光鸭文件表驱动候选扩展与比较

## Goal
实现用户交接文档冻结要求；不是给旧 qualified 前几项加过滤器。用户已批准实现及以下三个测试 seam：只读解析/认证、候选比较与提交安全、CD2识别与版本命名。不执行真实下载或生产发布。

### 2026-11-22 用户批准的选择策略变更
以下变更覆盖本文原“全部评估前禁止自动提交”条款：允许从本轮已核验且可执行的片源中自动选择，即使其他候选尚未完成；仍保留PAUSED/BLOCKED事实，不声称全队列最优，未知风险、错身份/年份/季度/路径、枪版和Prowlarr零做种不得自动提交。单轮时间/次数限制不扩大，手动比较可继续。缺配置/认证、搜索故障和上限应分别提示，自动记录不提供无主快照的死链。

## Requirements
- 搜索发现按hash合并，保留所有来源/冲突。明确作品/年份/类型冲突不能被同名目录洗白。合理相关缺整包/画质/容量/多季等进入补证队列；不凭标题 qualified 才准入。
- 光鸭只读 resolve，禁止 create_task/restore/转存/建目录；既有OpenList/PikPak路径不变。文件表按hash缓存；目标评估重算。可信路径、hash、数量、大小、深度/节点上限校验；失败未知，不显示0集或死种。
- 同轮已发现进入队列的项全部评估才完成，不因第一个可用早停。跨证据层调度，原合格、缺证据、合集均有机会。无可用时先评估剩余再按可信别名补搜。
- 总45秒（含搜索/补搜/认证/比较），最多4词，搜索并发2；最多20次新resolve HTTP（认证重试计数，缓存不计），解析并发2、阶段30秒、单次8秒。普通超时不重试，每轮最多一次共享认证刷新且不突破截止时间。
- 预算结束还有待查则PAUSED，展示当前推荐/已查待查，禁止自动提交；明确继续触发下一轮，保留已查与文件表、快照时间。搜索先展示后台渐进更新。人工可提前选当前项或明确接受未核验风险，但不能绕过已知身份/路径/目标冲突。
- 文件表识别目标后不再用标题/容量作为整包硬门槛。内容可确认但执行不支持独立为包含目标待处理；复杂跨季/绝对编号无可信映射/复杂多版本/选择下载不自动提交，不能隐藏大合集；分别展示总包及目标容量。
- 正常版本 > 中文尝鲜/抢先 > 枪版拒绝（CAM上下文；.ts容器不等于枪版）。在版本层之后按既有画质允许范围（1080->2160；2160->1080，不开放1080->720）及字幕等优先级，容量末级。
- 01-06正常、07-12尝鲜整季包可备选，不跨包拼集，不以TMDB精确齐集硬阻塞。标准名保留尝鲜/抢先标记，原文件名/证据进任务记录。CD2须识别全部12集，不能只入前6集成功。未知/冲突/零正片防误删保持。
- 凭证普通access+refresh+稳定deviceId，用SettingsCipher密文存储，管理员设置/检查复用；按需一次刷新、同账号并发合并、原子轮换防旧版本覆盖；持久化失败显式告警。未真实验证refresh不得声称通过。

## Acceptance Criteria
- 隐秘HD1080P/WEB1080P标签识别；EP01-12替代包可执行；纯数字/_01_未支持时准确待处理，不能误推荐。
- 切尔诺贝利E01副标题1.23.45不被非正片误伤；分数集仍排除。
- El Camino不是绝命毒师S1；多季合集保持可见且映射未支持不自动下。
- 混合正常/尝鲜包12目标视频均识别并保留版本标记；.ts不是CAM。
- 候选后面的正常版胜过前面尝鲜；所有队列完成前不得自动提交；20次/时限暂停、显式续查、缓存复用和刷新合并有定点回归。
- 前端状态四类：可推荐、包含目标待处理、不适合本次目标、未核验；显示进度、当前推荐、继续比较与风险确认。

## Technical Approach
复用现有PanSouMagnetSearch、SettingsService/SettingsCipher、MovieSeriesFileRenameService及MagnetIngestService受理。统一文件表被coverage和比较复用。最小会话快照缓存可用既有Guava缓存，明确重启失效，不新增平台/依赖。

## References
工作区根 /Users/tengenchang/Desktop/github/Media。
- diagnostics/guangya-candidate-expansion-live-research-20261118.md
- diagnostics/guangya-authenticated-resolve-probe-20261118.md
- diagnostics/medianexus-pansou-handoff-20261112.md
- diagnostics/series-ingest-4554e2ba-2026-11-18.md
原交接：/private/var/folders/59/sjwxmgxx6k5crpg89pkdk5500000gn/T/medianexus-guangya-search-implementation-handoff-20261118.md
外部当前产品资料：/Users/tengenchang/Library/Mobile Documents/com~apple~CloudDocs/AI-Coding-Docs/Media/ResourceSearch。

## Baseline / Limits
本地 backend7116627 / frontend47d15a6 干净；独立worktrees/guangya/backend和frontend，分支feat/guangya-candidate-comparison。远端fetch及ls-remote超时，尚未确认远端。生产尚未重查。不得改生产、重试旧任务、替换/清理媒体。定点测试，不跑无关全量Maven。Java17 /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home。
