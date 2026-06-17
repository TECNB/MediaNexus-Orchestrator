# Movie & Series Magnet Ingest API

## Goal

Port the manual **movie** and **series** magnet ingest from MediaNexus-Core
(Python) into the Java Orchestrator. Anime manual magnet already exists; this
adds the two remaining media types as **lightweight fire-and-forget** endpoints
plus the read-only movie/series search calls the existing frontend picker
requires. The submit path builds a target save path, ensures the OpenList
directory exists, submits a single magnet to OpenList offline download, and
returns the computed path. The search path proxies the minimal Radarr/Sonarr
lookups needed by the picker. Reuse existing Orchestrator infrastructure
(`OpenListClient`, `ApiResponse`, auth, quota) **without** importing Core's
runtime or the heavy anime task model.

Requirements discovery was completed via a one-pass interview (`/grill-me`);
the 9 decisions below are already converged. This PRD records them.

## Scope

- **In**:
  - `POST /api/v1/magnet-ingest/movies`
  - `POST /api/v1/magnet-ingest/series`
  - `GET /api/v1/resources/movies/search`
  - `GET /api/v1/resources/series/search`
  - `GET /api/v1/resources/series/seasons`
  - frontend cutover of movie/series submit plus shared movie/series search
    functions to the Java client.
- **Out**: movie quality profile lookup and Radarr add/search-start
  (`GET /api/v1/resources/movies/quality-profiles`,
  `POST /api/v1/resources/movies/add`) stay on Core.

## Decisions (converged)

1. **Scope** — migrate the two submit endpoints and the three read-only search
   endpoints required by the picker. Quality profile lookup and Radarr add stay
   on Core.
2. **Weight** — pure fire-and-forget. No task table, no log table, no async
   worker, no organize/rename, no dedup. Mirrors Core behavior.
3. **Auth + quota** — go through `authService.requireCurrentUser()` and
   `userActionQuotaService.consumeDailyContentCreate(user, MAGNET_INGEST_CREATE)`.
   All three media types share the one daily magnet budget; no enum/DB change.
4. **Path logic ownership** — add a pure `ensureDirectoryReady(fullPath, rootPath)`
   to `OpenListClient` (verify root exists → mkdir each level below it, no task
   logging). Add `movieRootPath` / `tvRootPath` to `OpenListProperties` (fixed
   structure, **not** a template). Title sanitization is a private helper in the
   new service. The anime service's existing private, log-emitting
   `ensurePathReady` is left untouched.
5. **Code organization** — one `MagnetIngestController` (`POST /movies`,
   `POST /series`) + one `MagnetIngestService` with two complete operations
   `ingestMovie()` / `ingestSeries()` (no mode flag) sharing private helpers.
   The anime stack stays separate.
6. **Envelope + frontend** — backend returns the standard `ApiResponse<T>`
   (`{code,message,data}`). DTOs use camelCase fields with per-field
   `@JsonProperty` snake_case mapping (same as anime DTOs). This task also
   switches the frontend `createMovieMagnetIngest` / `createSeriesMagnetIngest`
   to `javaApiClient` + `code === 200` + `getJavaErrorMessage`.
7. **Core disposition** — long-term coexistence. Core's movie/series submit and
   search endpoints are not deleted, preserving a rollback path. Quality
   profile lookup and movie add remain active on Core.
8. **Path & validation parity (strict)** — both backends must produce an
   identical `save_path` while they coexist, so the same content never lands in
   two different folders. See "Path & Validation Parity".
9. **Quota timing** — consume the daily quota **after** `addOfflineDownload`
   returns successfully (a failed submit does not cost quota). This differs from
   anime's "consume on accept" because movie/series are synchronous and the
   outcome is known before responding.
10. **Search migration** — add only minimal Radarr/Sonarr clients:
    `RadarrClient.searchMovies(term)`, `SonarrClient.searchSeries(term)`, and
    `SonarrClient.getSeriesByTvdbId(tvdbId)`. Search endpoints require auth but
    do not consume quota. Java uses `MEDIANEXUS_RADARR_*` and
    `MEDIANEXUS_SONARR_*` config names.

## Flow

`requireCurrentUser` → resolve & validate configured root → `ensureDirectoryReady`
→ `addOfflineDownload(savePath, magnet)` → on success `consumeDailyContentCreate`
→ return `save_path` (series also returns `series_name`, `season_folder`).

## Path & Validation Parity (must match Core exactly)

- Prefer `original_title` over `title` for the folder name.
- Movie folder: `{movieRoot}/{sanitize(title)} ({year})`.
- Series path: `{tvRoot}/{sanitize(title)}/Season {NN}` (`%02d`).
- `sanitize`: replace `[\\/:*?"<>|]+` with a space, collapse whitespace, then
  strip leading/trailing spaces **and dots** (Core's `.strip(" .")`). This is
  stricter than the anime `sanitizePathSegment`, which only trims whitespace.
- Validation: `magnet` must start with `magnet:?`; `year` ∈ [1888, currentYear+2];
  `season_number` ≥ 1; at least one of `title` / `original_title` present.

## API Shape

- `POST /api/v1/magnet-ingest/movies`
  - Request: `{ magnet, title, original_title, year }`
  - Response data: `{ save_path }`
- `POST /api/v1/magnet-ingest/series`
  - Request: `{ magnet, title, original_title, season_number }`
  - Response data: `{ save_path, series_name, season_folder }`

All responses use the existing `ApiResponse<T>` envelope. Request JSON stays
snake_case (matching the payload the frontend already sends and Core's contract).

### Read-only Search APIs

- `GET /api/v1/resources/movies/search?term=...`
  - Response data: `{ items }`
  - Item fields: `id,title,original_title,year,overview,poster,tmdb_id,imdb_id,status`
- `GET /api/v1/resources/series/search?term=...`
  - Response data: `{ items }`
  - Item fields:
    `id,title,original_title,year,overview,poster,tvdb_id,imdb_id,tmdb_id,status,network,series_type`
- `GET /api/v1/resources/series/seasons?tvdb_id=...`
  - Response data: `{ tvdb_id,title,season_count,season_numbers }`

Search response field names must stay byte-compatible with Core/frontend
TypeScript types. Java DTOs may use camelCase internally with `@JsonProperty`
for snake_case fields.

## Error Mapping (preserve Core's Chinese messages)

- Validation failure → 400.
- Root not configured → `OpenList 电影基础路径尚未配置` / `OpenList 剧集基础路径尚未配置`.
- Root missing on OpenList → `OpenList 电影基础路径不存在` / `OpenList 剧集基础路径不存在`.
- Empty title → `电影标题不能为空` / `剧集标题不能为空`.
- OpenList submit failure → 500 `创建离线下载任务失败` / `创建剧集离线下载任务失败`.

## Files

**New**
- `controller/MagnetIngestController.java`
- `controller/MovieSeriesResourceController.java`
- `service/MagnetIngestService.java`
- `service/MovieSeriesResourceSearchService.java`
- `dto/magnet/request/MovieMagnetIngestRequest.java`
- `dto/magnet/request/SeriesMagnetIngestRequest.java`
- `dto/magnet/response/MovieMagnetIngestResponse.java`
- `dto/magnet/response/SeriesMagnetIngestResponse.java`
- `dto/resources/response/*`
- `integration/radarr/*`
- `integration/sonarr/*`
- `config/RadarrProperties.java`
- `config/SonarrProperties.java`

**Modified**
- `integration/openlist/OpenListClient.java` — add `ensureDirectoryReady(fullPath, rootPath)`.
- `config/OpenListProperties.java` + `application.yml` — add `movie-root-path` / `tv-root-path`.
- `application.yml` — add Radarr/Sonarr read-only integration config.
- `MediaNexus/src/lib/api/magnet-ingest.ts` — switch the two submit functions to `javaApiClient`.
- `MediaNexus/src/lib/api/resources.ts` — switch movie/series search and series seasons to `javaApiClient`; keep movie quality profiles and add movie on Core.

## Config / Deploy

- Set Java `open-list.movie-root-path` / `tv-root-path` to the same values as
  Core's `alist_path_movie` / `alist_path_tv`.
- Set Java `radarr.*` and `sonarr.*` values from Core's existing
  `RADARR_*` / `SONARR_*` values, using the Java env names
  `MEDIANEXUS_RADARR_*` and `MEDIANEXUS_SONARR_*`.
- Ensure the frontend `VITE_JAVA_API_BASE_URL` is configured so movie/series hit Java.

## Non-Goals

- No movie quality profile lookup or Radarr add/search-start in Java.
- No direct TMDB/TVDB integration in Java; series seasons are inferred from
  Sonarr lookup results only.
- No persistent task records, task logs, async worker, organize/rename, or dedup
  for movie/series.
- No deletion of Core's movie/series magnet code in this task.
- No new UI; the magnet-ingest page already supports all three modes and
  movie/series "Recent Tasks" remains static mock.

## Acceptance Criteria

- [ ] Submitting a valid movie magnet creates an OpenList offline task and
      returns `{ save_path }` shaped `{movieRoot}/{title} ({year})`.
- [ ] Submitting a valid series magnet returns `{ save_path, series_name,
      season_folder }` shaped `{tvRoot}/{title}/Season NN`.
- [ ] The computed `save_path` is byte-identical to Core's for the same input
      (incl. `original_title` preference and dot-stripping).
- [ ] Invalid magnet / out-of-range year / season < 1 / missing title → 400.
- [ ] Unconfigured or missing OpenList root → the matching Chinese error message.
- [ ] A failed OpenList submit does **not** consume the user's daily quota; a
      successful one does (shared `MAGNET_INGEST_CREATE`).
- [ ] Unauthenticated requests are rejected by the existing auth layer.
- [ ] Frontend movie/series submit goes to Java via `javaApiClient` and reads
      `save_path` from the `ApiResponse` envelope.
- [ ] Movie search, series search, and series seasons endpoints return the same
      field shape the existing frontend types expect.
- [ ] Frontend `searchMovies`, `searchSeries`, and `getSeriesSeasons` go to
      Java via `javaApiClient`; movie quality profiles and add movie remain on
      Core.

## Definition of Done

- Unit tests for path/sanitize/validation parity and quota-on-success behavior;
  integration test for the two endpoints where practical.
- Lint / typecheck / build green (backend `mvn`, frontend `tsc`/build).
- `application.yml` documents the two new properties.
- Commit uses `TECNB <3489044730@qq.com>`, Conventional Commit prefix + Chinese
  summary (e.g. `feat: 接入电影剧集手动磁力离线提交`).

## Technical Notes

- **Assumptions to verify during implementation**:
  - How `OpenListClient` detects a missing root (likely `listFiles(root)` throws
    `OpenListClientException`); pick the cleanest existence check.
  - `AuthService.requireCurrentUser()` is the same entry point anime uses.
- **Reconciliation**: the anime task PRD (`05-31-anime-season-magnet-ingest-api`)
  states "Core is being phased out." Decision #7 (long-term coexistence) keeps
  Core's movie/series dormant for rollback rather than contradicting that — the
  Java path becomes active, Core becomes unused, and eventual removal is a later
  cleanup. Java never calls Core (consistent with the anime task's "do not call
  Core").
- Reuse: `OpenListClient.addOfflineDownload` already applies the configured
  `offlineTool` + `deletePolicy`; movie/series ignore the returned offline task
  id (fire-and-forget). `ApiResponse`, `BusinessException`/`ErrorCode`,
  `authService`, `userActionQuotaService` all already exist.
- Reference implementations:
  - Core: `app/services/magnet_ingest_service.py`, `app/schemas/magnet_ingest.py`,
    `app/api/v1/endpoints/magnet_ingest.py`.
  - Java anime (pattern source): `service/AnimeMagnetIngestTaskService.java`,
    `controller/MagnetIngestAnimeController.java`,
    `dto/magnet/request/AnimeMagnetIngestTaskCreateRequest.java`.
