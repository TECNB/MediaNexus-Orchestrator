# Prowlarr Resource OpenList Ingest

## 2026-06-19 Latest Decision

This section supersedes the earlier first-slice statements below.

- Keep the direct `添加` action as the automatic first-match shortcut.
- `查看更多` opens a Prowlarr release-selection page and is the main manual
  selection flow.
- Search query follows torrra 2.0.7:
  - movie: the submitted outer search term unchanged
  - series: `{term} Sxx`
- Prowlarr search sends only `apikey` and `query`; do not append `type`,
  `categories`, `limit`, or `offset`.
- Return release rows in Prowlarr order without sorting, pagination, or
  deduplication.
- The release response exposes display metadata plus an opaque
  `download_ref` and `indexer_id`. It never exposes the Prowlarr API key or a
  complete upstream download/magnet URL.
- When a row is selected, Java reconstructs the Prowlarr download URL with the
  server-side API key, resolves it to a magnet, and creates the existing
  OpenList ingest task.
- Persist release source, title, indexer, size, resolution tags, and dynamic
  range tags on the task. Keep `quality_tag` as a compatibility field.
- Release search does not consume quota. Successful ingest task creation keeps
  the existing quota behavior.

## Goal

Add a lightweight Prowlarr-powered resource ingest path for movies and series.
The resource page should no longer rely on Sonarr/Radarr as the ingest action.
When the frontend asks to ingest a movie or series, the Java Orchestrator
searches Prowlarr, picks a matching release, resolves it to a real magnet URI,
and then reuses the existing movie/series OpenList magnet task flow.

The first implementation intentionally optimizes for getting the full workflow
running: resource card → automatic Prowlarr release selection → OpenList ingest
task → real-time task logs.

## Current Context

- Existing movie/series resource search is already proxied by Java through
  Radarr/Sonarr read-only search endpoints.
- Existing movie/series magnet ingest task APIs already exist:
  - `POST /api/v1/magnet-ingest/movies/tasks`
  - `POST /api/v1/magnet-ingest/series/tasks`
  - recent task list and per-task log APIs for both media types.
- `MagnetIngestService` already owns:
  - OpenList directory preparation
  - offline download submission
  - single-threaded task execution
  - file organization
  - task status and task logs
- The current service only accepts real `magnet:?` values, so Prowlarr
  `downloadUrl` values must be resolved before calling it.
- `torrra-custom` confirms the desired Prowlarr behavior:
  - call `/api/v1/search`
  - use `magnetUrl` when present
  - otherwise use `downloadUrl`
  - resolve redirects / `.torrent` responses into a real magnet URI

## Scope

### In

- Add Prowlarr backend configuration.
- Add a Prowlarr client that searches releases and resolves release links to
  magnet URIs.
- Add backend endpoints that create movie/series OpenList ingest tasks from
  resource-card context.
- Parse release title tags for:
  - resolution, such as `2160p`, `1080p`, `720p`
  - dynamic range, such as `HDR`, `HDR10`, `HDR10+`, `Dolby Vision`, `DoVi`
- Filter by the selected frontend resolution tag.
- Store selected resolution and parsed dynamic range tags on the created task.
- Reuse the existing movie/series magnet task execution and log APIs.
- Keep configuration and upstream URLs server-side only.

### Out

- No release list UI in this slice.
- No manual release selection.
- No internal search input inside a card or modal.
- No short-lived release candidate cache.
- No frontend access to Prowlarr `downloadUrl`, `magnetUrl`, base URL, or API key.
- No result deduplication in the first slice.
- No Sonarr/Radarr add/search-start behavior.
- No following/monitoring feature.
- No Anime behavior change.

## Decisions

1. **Automatic release selection**
   The backend searches Prowlarr and selects the first release that matches the
   requested resolution after title-tag parsing. Preserve Prowlarr/torrra result
   order; do not add a custom scoring system in this slice.

2. **Search query**
   - Movie query: resource card title.
   - Series query: resource card title plus season token, e.g. `庆余年 S02`.
   Do not append resolution to the Prowlarr query. Resolution is a backend filter
   over returned release titles so results are not accidentally hidden by the
   upstream search text.

3. **Resolution contract**
   The frontend sends the exact quality tag it wants, such as `2160p` or
   `1080p`. The backend does not translate display labels like `4K UHD`.

4. **Dynamic range contract**
   Dynamic range tags are informational. If a release title contains multiple
   tags, store all parsed tags. Do not enforce mutual exclusion.

5. **No defensive overbuild**
   Validate at API/config boundaries and keep internals simple. Do not introduce
   wrapper/facade layers that do not own a real invariant.

## Backend API

### Movie

`POST /api/v1/resources/movies/openlist-ingest`

Request:

```json
{
  "title": "奥本海默",
  "original_title": "Oppenheimer",
  "year": 2023,
  "quality": "2160p"
}
```

Response:

- Standard `ApiResponse<T>` envelope.
- `data` should be a movie ingest task response, extended with:
  - `quality_tag`
  - `dynamic_range_tags`

### Series

`POST /api/v1/resources/series/openlist-ingest`

Request:

```json
{
  "title": "继承之战",
  "original_title": "Succession",
  "season_number": 1,
  "quality": "1080p"
}
```

Response:

- Standard `ApiResponse<T>` envelope.
- `data` should be a series ingest task response, extended with:
  - `quality_tag`
  - `dynamic_range_tags`

## Backend Flow

### Movie

`requireCurrentUser`
→ build query from `title`
→ search Prowlarr
→ parse release tags
→ filter selected `quality`
→ pick first matching release
→ resolve `magnetUrl || downloadUrl` to a real magnet
→ create `MovieMagnetIngestRequest`
→ call existing `MagnetIngestService.createMovieTask`
→ persist selected release tags on the task
→ return task response

### Series

`requireCurrentUser`
→ build query from `title + Sxx`
→ search Prowlarr
→ parse release tags
→ filter selected `quality`
→ pick first matching release
→ resolve `magnetUrl || downloadUrl` to a real magnet
→ create `SeriesMagnetIngestRequest`
→ call existing `MagnetIngestService.createSeriesTask`
→ persist selected release tags on the task
→ return task response

## Prowlarr Integration

### Configuration

Add:

```yaml
medianexus:
  prowlarr:
    base-url: ${MEDIANEXUS_PROWLARR_BASE_URL:}
    api-key: ${MEDIANEXUS_PROWLARR_API_KEY:}
    timeout: ${MEDIANEXUS_PROWLARR_TIMEOUT:30s}
```

Add production guard coverage for `medianexus.prowlarr.api-key`.

The real values come from:

`/Users/tec/Library/Mobile Documents/com~apple~CloudDocs/torrra-custom/config.private.toml`

Do not print or commit the secret values.

### Client Behavior

Add a focused `ProwlarrClient` that:

- calls `{baseUrl}/api/v1/search`
- sends `apikey` and `query`
- maps only the fields this feature needs:
  - `title`
  - `size`
  - `seeders`
  - `leechers`
  - `indexer`
  - `magnetUrl`
  - `downloadUrl`
- does not expose upstream links to frontend DTOs

### Magnet Resolution

Add server-side resolution equivalent to `torrra-custom`:

1. If the selected value starts with `magnet:`, normalize common malformed
   `btih` / `btmh` forms into `xt=urn:...` and return it.
2. Otherwise request the URL without relying on shell proxy environment.
3. If the response redirects to a magnet, return that magnet.
4. If the response redirects to another URL, follow up to a small fixed limit.
5. If the response looks like a `.torrent`, convert it to a magnet URI.

Implementation can choose the smallest Java dependency-free path that works
with the current project. If `.torrent` conversion needs a dependency, keep it
small and justify it in the implementation notes.

## Data Changes

Extend both task tables and models with:

- `quality_tag`
- `dynamic_range_tags`

Use a simple string storage shape for `dynamic_range_tags` unless the existing
database conventions strongly prefer another shape. The frontend can receive an
array after DTO mapping.

Update:

- model classes
- response DTOs
- task creation/update mapping
- any mapper SQL if needed by the project setup

## Error Handling

Use existing `BusinessException` / `ErrorCode` patterns.

Expected user-facing cases:

- Prowlarr is not configured → `Prowlarr 服务尚未配置`
- Prowlarr search fails → `Prowlarr 搜索失败，请稍后重试`
- No matching release for selected quality → `未找到匹配分辨率的发布资源`
- Release cannot resolve to magnet → `发布资源无法解析为 magnet 链接`
- Existing OpenList root/config errors remain owned by `MagnetIngestService`.

## Files

Expected backend additions:

- `config/ProwlarrProperties.java`
- `integration/prowlarr/ProwlarrClient.java`
- `integration/prowlarr/ProwlarrClientException.java`
- `integration/prowlarr/ProwlarrRelease.java`
- `service/ProwlarrReleaseIngestService.java`
- `service/ReleaseTitleTagParser.java`
- request DTOs for movie/series OpenList ingest

Expected backend modifications:

- `MediaNexusOrchestratorApplication.java`
- `application.yml`
- `ProductionConfigurationGuard.java`
- `MagnetIngestController` or `MovieSeriesResourceController`
- movie/series task models and task response DTOs
- movie/series task table schema/migration location used by this project

## Non-Goals

- No release-list page.
- No release manual selection.
- No candidate cache.
- No result deduplication.
- No direct TMDB/TVDB integration.
- No Sonarr/Radarr add/search-start in this flow.
- No frontend exposure of Prowlarr URLs or API key.
- No full task center.

## Acceptance Criteria

- [ ] Movie resource card can trigger a backend Prowlarr search and create an
      OpenList movie ingest task.
- [ ] Series resource card can trigger a backend Prowlarr search for the selected
      season and create an OpenList series ingest task.
- [ ] Backend filters releases by selected quality tag without appending quality
      to the Prowlarr query.
- [ ] Backend resolves Prowlarr `magnetUrl` or `downloadUrl` into a real
      `magnet:?` value before calling `MagnetIngestService`.
- [ ] Created tasks include `quality_tag` and `dynamic_range_tags`.
- [ ] Existing movie/series manual magnet task endpoints still work.
- [ ] Existing task list and task log endpoints still work.
- [ ] Frontend never receives Prowlarr `downloadUrl`, `magnetUrl`, or API key.
- [ ] Configuration errors surface through backend messages rather than disabled
      frontend buttons.
- [ ] No full-project validation command is run unless explicitly requested.

## Definition of Done

- PRD implemented in Java Orchestrator.
- Prowlarr `.env` values are populated from `torrra-custom` without exposing
  secrets in logs or commits.
- Existing manual movie/series magnet ingest behavior remains intact.
- Frontend task can consume the new APIs and real-time log route.
- Only scoped verification is run unless the user explicitly requests broader
  validation.
