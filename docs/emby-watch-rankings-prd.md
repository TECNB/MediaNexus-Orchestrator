# Emby 观看活跃与排行统计 PRD

## 1. 背景

当前 MediaNexus-Orchestrator 需要新增一项轻量的 Emby 使用情况统计能力，用于管理员了解朋友们是否在使用 Emby 媒体服务，以及今天大致看了多久、看了哪些作品。

本需求接入的 Emby 版本为 `4.9.3.0`。当前 Emby 已安装 Webhooks 插件，未安装或未启用 Playback Reporting 插件，因此一期不依赖 Playback Reporting 的 `user_usage_stats/*` 接口。

## 2. 目标

一期目标是通过 Emby Webhooks 采集播放开始与停止事件，在 Java 后端中结算已结束观看会话，并提供一个管理员可访问的结构化 API：

- 查看某一天的活跃概览。
- 查看 Emby 用户观看时长排行。
- 查看电影观看时长排行。
- 查看电视剧/番剧观看时长排行。

核心使用场景：

- 管理员打开后续 MediaNexus 前端页面，确认今天有多少朋友使用了系统。
- 管理员查看每个活跃用户今天大致看了多久、最后一次观看时间、最后观看内容。
- 管理员查看今天哪些电影、剧集被观看较多。

## 3. 非目标

一期不做以下内容：

- 不做历史数据回填。
- 不接入 Playback Reporting 插件。
- 不做复杂反刷榜、并发播放限制或异常用户检测。
- 不做实时正在播放会话的时长估算。
- 不做 Emby API 元数据补全。
- 不做媒体库黑名单或用户黑名单。
- 不做复杂可视化、趋势图或固定中文日报文本。
- 不生成固定中文文本榜单。
- 不精确处理暂停、恢复、拖动进度带来的误差。

## 4. 已确认产品决策

- 数据从功能上线后开始记录，历史数据不管。
- 观看时长按媒体进度差计算，即 `stopPositionTicks - startPositionTicks`。
- 一期只处理 `playback.start` 和 `playback.stop`。
- 只有收到 `playback.stop` 并成功结算的会话才进入统计。
- 没有 stop 的异常会话不计入统计。
- 单次观看时长小于 10 秒不计入统计。
- 今日按 `Asia/Shanghai` 自然日计算。
- 跨天播放按 `playback.stop` 所在北京时间日期入账。
- 用户榜按 Emby 用户统计，不绑定 MediaNexus 登录用户。
- 榜单接口只允许 MediaNexus 管理员访问。
- Webhook 入口使用 query secret，例如 `?secret=xxx`。
- Webhook 使用 Emby Webhooks 原生 `application/json` payload。
- 一期不做 Emby API 元数据补全，只依赖 webhook 原生 payload。
- 电影榜统计 `itemType = Movie`。
- 电视剧/番剧榜统计 `itemType = Episode`，TV 与 Anime 不拆分。
- Adult 媒体库不排除。
- 排行榜默认 `limit = 20`，不额外定义最大值。
- 查询接口支持可选 `date` 参数，不传默认今天。
- API 返回结构化数据和 summary 概览。
- 用户榜项返回 `last_watched_at` 和 `last_item_name`。
- 作品榜项返回 `last_played_at`，不返回最近观看用户。
- 为前端 Webhooks 实时监控卡片返回轻量 `webhook_status`。

## 5. Webhook 接入

### 5.1 Endpoint

```text
POST /api/v1/emby/webhooks/playback?secret={MEDIANEXUS_EMBY_WEBHOOK_SECRET}
```

该接口需要从当前 `/api/**` 登录拦截中排除，但接口内部必须校验 query 参数 `secret`。

### 5.2 配置项

```text
MEDIANEXUS_EMBY_WEBHOOK_SECRET=your-random-secret
```

建议同时在 `application.yml` 中新增配置：

```yaml
medianexus:
  emby:
    webhook-secret: ${MEDIANEXUS_EMBY_WEBHOOK_SECRET:}
```

### 5.3 Emby Webhooks 配置

Emby Webhooks 新增通知时：

- URL 填写 `/api/v1/emby/webhooks/playback?secret=...`。
- 请求内容类型选择 `application/json`。
- Events 只勾选播放分类下的播放开始和播放停止事件。
- 不配置自定义 body 模板；当前 Emby Webhooks UI 只发送原生 JSON payload。

后端从 Emby 原生 payload 中解析 `Event`、`Date`、`User`、`Item`、`Session` 等字段。

### 5.4 事件处理

`playback.start`：

- 校验 secret。
- 校验必要字段：`event`、`userId`、`sessionId`、`itemId`、`itemType`、`positionTicks`。
- 仅处理 `itemType = Movie` 或 `itemType = Episode`。
- 将未结束会话写入 `emby_active_playback_sessions`。
- 如果同一 `sessionId + itemId` 已存在 active 记录，以最新 start 覆盖旧记录。

`playback.stop`：

- 校验 secret。
- 校验必要字段。
- 查询同一 `sessionId + itemId` 的 active start。
- 如果找不到 start，不生成观看会话。
- 计算 `watchSeconds`。
- 如果有效，则写入 `emby_watch_sessions`。
- 写入成功后删除对应 active 记录。

## 6. 观看时长计算规则

Emby ticks 换算：

```text
10,000,000 ticks = 1 second
```

基础计算：

```text
rawWatchSeconds = floor((stopPositionTicks - startPositionTicks) / 10_000_000)
```

无效条件：

```text
rawWatchSeconds <= 0
rawWatchSeconds < 10
```

异常截断：

```text
if runtimeTicks > 0:
  watchSeconds = min(rawWatchSeconds, floor(runtimeTicks / 10_000_000))
else:
  watchSeconds = min(rawWatchSeconds, 12 * 3600)
```

入账日期：

```text
watchDate = playback.stop 的北京时间日期
```

## 7. 数据模型

### 7.1 emby_active_playback_sessions

用途：保存已收到 start、尚未收到 stop 的播放会话。

字段建议：

```text
id BIGINT PK AUTO_INCREMENT
emby_session_id VARCHAR(128) NOT NULL
emby_user_id VARCHAR(128) NOT NULL
emby_user_name VARCHAR(255)
item_id VARCHAR(128) NOT NULL
item_type VARCHAR(64) NOT NULL
item_name VARCHAR(512)
series_id VARCHAR(128)
series_name VARCHAR(512)
runtime_ticks BIGINT
start_position_ticks BIGINT NOT NULL
start_time DATETIME NOT NULL
device_name VARCHAR(255)
client_name VARCHAR(255)
created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
```

约束与索引：

```text
UNIQUE KEY uk_emby_active_session_item (emby_session_id, item_id)
KEY idx_emby_active_user (emby_user_id)
KEY idx_emby_active_start_time (start_time)
```

### 7.2 emby_watch_sessions

用途：保存已结算的有效观看会话，用于排行榜聚合。

字段建议：

```text
id BIGINT PK AUTO_INCREMENT
emby_session_id VARCHAR(128) NOT NULL
emby_user_id VARCHAR(128) NOT NULL
emby_user_name VARCHAR(255)
item_id VARCHAR(128) NOT NULL
item_type VARCHAR(64) NOT NULL
item_name VARCHAR(512)
series_id VARCHAR(128)
series_name VARCHAR(512)
runtime_ticks BIGINT
start_time DATETIME NOT NULL
stop_time DATETIME NOT NULL
start_position_ticks BIGINT NOT NULL
stop_position_ticks BIGINT NOT NULL
watch_seconds INT NOT NULL
watch_date DATE NOT NULL
device_name VARCHAR(255)
client_name VARCHAR(255)
created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
```

约束与索引：

```text
UNIQUE KEY uk_emby_watch_settlement (emby_session_id, item_id, stop_time)
KEY idx_emby_watch_date (watch_date)
KEY idx_emby_watch_user_date (emby_user_id, watch_date)
KEY idx_emby_watch_item_date (item_id, watch_date)
KEY idx_emby_watch_type_date (item_type, watch_date)
```

## 8. 排行榜 API

### 8.1 Endpoint

```text
GET /api/v1/admin/emby/watch-rankings?date=2026-06-14&limit=20
```

参数：

```text
date: optional, yyyy-MM-dd, default Asia/Shanghai today
limit: optional, integer, default 20
```

权限：

```text
MediaNexus ADMIN only
```

### 8.2 Response

```json
{
  "date": "2026-06-14",
  "timezone": "Asia/Shanghai",
  "generated_at": "2026-06-14T21:30:00",
  "summary": {
    "active_user_count": 8,
    "total_watch_seconds": 151980,
    "total_play_count": 96,
    "last_watched_at": "2026-06-14T21:26:00"
  },
  "users": [
    {
      "rank": 1,
      "emby_user_id": "abc",
      "user_name": "TT",
      "watch_seconds": 51480,
      "play_count": 8,
      "last_watched_at": "2026-06-14T21:26:00",
      "last_item_name": "某部作品"
    }
  ],
  "movies": [
    {
      "rank": 1,
      "media_id": "123",
      "title": "某部电影",
      "watch_seconds": 10800,
      "play_count": 3,
      "last_played_at": "2026-06-14T20:58:00"
    }
  ],
  "series": [
    {
      "rank": 1,
      "media_id": "456",
      "title": "某部剧集",
      "watch_seconds": 32000,
      "play_count": 12,
      "last_played_at": "2026-06-14T21:10:00"
    }
  ],
  "webhook_status": {
    "secret_configured": true,
    "active_session_count": 1,
    "recent_events": [
      {
        "event": "playback.stop",
        "event_time": "2026-06-14T21:26:00",
        "user_name": "TT",
        "item_name": "某部作品",
        "watch_seconds": 2712
      }
    ]
  }
}
```

### 8.3 排序规则

所有榜单使用相同排序思路：

```text
主排序：`watch_seconds DESC`
次排序：`play_count DESC`
再次排序：`title/user_name ASC`
```

### 8.4 聚合规则

用户榜：

```text
GROUP BY emby_user_id
watch_seconds = SUM(watch_seconds)
play_count = COUNT(*)
last_watched_at = MAX(stop_time)
last_item_name = 最近一条 watch session 的 item_name/series_name 展示名
```

电影榜：

```text
WHERE item_type = 'Movie'
GROUP BY item_id
title = item_name
watch_seconds = SUM(watch_seconds)
play_count = COUNT(*)
last_played_at = MAX(stop_time)
```

电视剧/番剧榜：

```text
WHERE item_type = 'Episode'
groupKey = series_id if present else series_name if present else item_id
title = series_name if present else item_name
watch_seconds = SUM(watch_seconds)
play_count = COUNT(*)
last_played_at = MAX(stop_time)
```

Summary：

```text
active_user_count = COUNT(DISTINCT emby_user_id)
total_watch_seconds = SUM(watch_seconds)
total_play_count = COUNT(*)
last_watched_at = MAX(stop_time)
```

## 9. 错误处理

Webhook：

- secret 缺失或不匹配：返回 403。
- 不支持的 event：返回成功但不处理，避免 Emby 重试造成噪音。
- 不支持的 itemType：返回成功但不处理。
- 必要字段缺失：返回成功但记录 warning 日志，不结算。
- stop 找不到 start：返回成功但记录 debug/info 日志，不结算。
- 重复 stop 触发唯一键冲突：返回成功，不重复计数。

排行榜 API：

- 非管理员访问：返回 403。
- date 格式非法：返回 400。
- limit 缺失：使用 20。
- 当天无数据：返回空数组，summary 使用 0/null。

## 10. 实施建议

后端新增模块建议：

```text
config/EmbyProperties.java
controller/EmbyWebhookController.java
controller/AdminEmbyWatchRankingController.java
service/EmbyPlaybackWebhookService.java
service/EmbyWatchRankingService.java
model/EmbyActivePlaybackSession.java
model/EmbyWatchSession.java
mapper/EmbyActivePlaybackSessionMapper.java
mapper/EmbyWatchSessionMapper.java
dto/emby/request/EmbyPlaybackWebhookRequest.java
dto/emby/response/EmbyWatchRankingResponse.java
```

需要修改：

```text
application.yml
DatabaseInitializer.java
SaTokenWebMvcConfig.java
docker-compose.yml
```

前端新增模块建议：

```text
MediaNexus/src/pages/emby-watch-rankings/index.tsx
MediaNexus/src/lib/api/emby-watch-rankings.ts
MediaNexus/src/types/emby-watch-rankings.ts
```

需要修改：

```text
MediaNexus/src/router/index.tsx
MediaNexus/src/components/layout/sidebar.tsx
```

`SaTokenWebMvcConfig` 需要将 webhook endpoint 排除普通登录拦截：

```text
/api/v1/emby/webhooks/playback
```

`docker-compose.yml` 需要传入：

```text
MEDIANEXUS_EMBY_WEBHOOK_SECRET
```

## 11. 验收标准

- 配置 Emby Webhooks 后，播放 `Movie` 并停止，管理员 API 的 movies 与 users 中出现对应记录。
- 配置 Emby Webhooks 后，播放 `Episode` 并停止，管理员 API 的 series 与 users 中出现对应记录。
- 小于 10 秒的会话不进入榜单。
- 没有 stop 的会话不进入榜单。
- `date` 不传时查询北京时间今天。
- `date=yyyy-MM-dd` 时查询指定日期。
- 默认返回每个榜单最多 20 条。
- 非管理员无法访问排行榜 API。
- secret 错误时 webhook 返回 403。
- 重复 stop 不会重复增加观看时长或播放次数。

## 12. 后续可选增强

- 前端管理页展示活跃概览和排行榜。
- 支持正在播放会话实时估算。
- 支持按媒体库过滤，例如 Movies、TV、Anime、Adult。
- 支持用户或媒体库排除配置。
- 支持 Playback Reporting 对账或历史导入。
- 支持按周、月、年统计。
- 支持生成可复制的中文日报文本。
- 支持更精细的 pause/resume/progress 事件统计。
