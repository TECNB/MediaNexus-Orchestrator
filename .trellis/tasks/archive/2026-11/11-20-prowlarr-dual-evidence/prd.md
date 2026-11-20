# Prowlarr 双来源文件表与统一候选比较

## Goal
用户批准实施：PanSou/Prowlarr 使用同一文件表目标评估和比较；Prowlarr原始torrent和光鸭均支持，某一路不可取得（含光鸭353“文件违规”）时可采用另一可信来源，不再只有提交时才发现光鸭缺证据。

## Frozen contract
- 文件表来源不是目标安全结论。光鸭353保留为来源拒绝，但不等于本地已知非法文件表；安全校验合格的原始torrent可替代。
- 任一来源发现hash不符、非法路径、重复路径、树/体积/数量超限等明确安全失败，不能被另一来源或人工risk洗白。作品/季度冲突、枪版、不支持安全整理仍拒绝。
- 已有可信表优先复用；Prowlarr可先获取原始torrent，若只返回magnet/原始获取不可用则光鸭补证；已知有效magnet可先光鸭，失败时有下载引用则尝试原始torrent。不是无条件同时查询两边。单一路成功即足够，保留已发生的失败原因和成功来源。
- 同一hash缓存/在途共享；保留全部来源标题及Prowlarr indexer/reference/seeders等。原始响应中的infoHash可作为补证身份，不能据此伪造可信文件表或将未经核验hash与下载内容绑定。
- 原始torrent必须在外部边界做严格有界bencode解析（字节体积、深度、节点、字符串/整数边界、唯一info、有效结构、hash重算、单/多文件、UTF8优先、路径组件/重复/大小溢出检查）；不下载媒体、不调离线任务API。
- 统一comparison/evidence四类状态、全已发现队列评估、暂停/显式继续、自动只选完成且可执行项；不只核验旧推荐前几项。标题缺证据不能提前挡住文件表机会，直接magnet不能被旧Prowlarr过滤漏掉。
- 总45秒、文件表阶段30秒、光鸭最多20次HTTP尝试/并发2等既有预算保持；Prowlarr引用解析/跳转/读体也受剩余时间约束与有界请求数量，不能每项重新开预算。
- 共同提交Safety复用服务端证据，raw/selected/retry一致，自动不得继承人工risk。原始文件名和来源结论写入既有日志/证据说明。
- 前端缺evidence为未核验；展示可信来源、回退原因，不将光鸭拒绝等同0集。复用现有UI与轮询协议。

## Confirmed test seams
沿用已批准：只读解析/认证；候选比较与提交安全。顺序实施red-green；原始torrent结构/353回退/双失败/不可洗白、Prowlarr候选排序与提交复用、前端缺证据与原因展示。

## Acceptance
- 真实研究样本cf4d...和5307...分别代表光鸭成功和353但原torrent可取得，作为脱敏fixture事实，不依赖真实联网跑测试。
- 原始torrent失败光鸭成功、光鸭353原始torrent成功、双失败无自动创建、非法路径/hash失败不可回退洗白。
- 同hash去重缓存，未知不是0；字幕画质等仍是名称声明，非播放验证。
- 定点后端测试串行、前端定点测试/lint、diff检查；一次有限审查。Java17，不跑无关全量测试。

## Out of scope
不导入浏览器凭证、不改生产、不启动/停止用户后端、不创建真实下载，不推送不部署。原始torrent本地安全解析是本任务必需，不新增依赖或平台。

## References
- ../11-18-guangya-candidate-comparison/prd.md
- ../../spec/backend/external-integrations.md
- ../../spec/backend/quality-guidelines.md
- workspace diagnostics/prowlarr-guangya-live-reuse-probe.md（真实研究，含第二轮光鸭补测）
