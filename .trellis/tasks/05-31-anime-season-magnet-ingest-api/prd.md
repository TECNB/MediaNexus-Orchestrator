# Anime Season Magnet Ingest API

## Goal

Implement the first Java Orchestrator backend path for manual Anime season
magnet ingest. The user selects an Anime/Bangumi result and submits a whole
season magnet link. Orchestrator creates a persistent background task, submits
the magnet to OpenList/PikPak, waits for completion, scans downloaded files,
renames recognizable video/subtitle files with ANI-RSS-compatible episode
rules, leaves recognized files flat in the target Season directory, and records
a per-task log timeline.

## Decisions

- Do not create or modify ANI-RSS subscriptions.
- Do not call MediaNexus-Core; Core is being phased out.
- OpenList is configured directly in Orchestrator.
- OpenList client follows ANI-RSS' token/header model:
  - `Authorization: <token>`
  - `POST {host}/api/{action}`
  - no username/password login flow
- Offline download request uses:
  - `tool=PikPak`
  - `delete_policy=delete_on_upload_succeed`
- Use a persistent database task table and a task log table.
- Submit endpoint returns a `taskId` immediately.
- A single-thread worker processes tasks sequentially.
- On startup, unfinished tasks are marked `INTERRUPTED`; no automatic resume in
  the first slice.
- Duplicate in-progress magnet hash submissions return the existing task.
- Historical completed/failed/partial tasks may be submitted again.
- Output files are not overwritten; existing target files are skipped and
  logged.
- Final Season directory is flat. Do not preserve source collection folder
  structure.
- OpenList downloads directly into the final Season directory. Do not create a
  separate `.medianexus` or task-id temp directory.
- Unrecognized, duplicate, ad, and half-episode files are deleted after logging
  instead of being quarantined, so media scanners only see clean Season content.
- First slice does not trigger Emby/Jellyfin refresh.

## Fixed First-Slice Configuration

- Anime path template:
  `/pikpak/Media/Anime/${themoviedbName}/Season ${season}`
- If `${themoviedbName}` is unavailable, use the selected Bangumi title and log
  the fallback.
- Rename template:
  `${title} S${seasonFormat}E${episodeFormat}`
- Exclude patterns:
  - `特别篇`
  - `\d-\d`
  - `总集`
- Auto offset: enabled.
- Skip `.5` episodes: enabled.
- OpenList timeout: 360 minutes.
- OpenList polling interval: 30 seconds.
- OpenList retry limit for failed states: 5.
- If OpenList reports a failed state but video files exist in the Season path,
  continue to organization as a best-effort fallback and log it.

## ANI-RSS Behavior To Reuse

Port the practical behavior, not the whole project:

- Episode recognition should follow `RenameUtil.REG_STR`.
- Collection-style file filtering should follow `CollectionController.preview`.
- Rename output should follow `RenameUtil.rename` for supported placeholders in
  this slice.
- Video files keep original extension.
- Subtitle files keep original subtitle extension and language suffix when
  present, for example `.zh-CN.ass`.
- Files that cannot be recognized are logged and deleted.

## API Shape

- `POST /api/v1/magnet-ingest/anime/tasks`
  - Creates or reuses an in-progress task.
  - Returns task id and current status.
- `GET /api/v1/magnet-ingest/anime/tasks`
  - Returns recent Anime magnet tasks for the UI.
- `GET /api/v1/magnet-ingest/anime/tasks/{taskId}`
  - Returns task detail.
- `GET /api/v1/magnet-ingest/anime/tasks/{taskId}/logs`
  - Returns per-task logs in chronological order.

All responses use the existing `ApiResponse<T>` envelope.

## Task Statuses

- `PENDING`
- `SUBMITTED`
- `DOWNLOADING`
- `ORGANIZING`
- `SUCCEEDED`
- `PARTIAL_SUCCESS`
- `FAILED`
- `INTERRUPTED`

## Non-Goals

- No ANI-RSS subscription creation.
- No `.torrent` upload/preview flow.
- No qBittorrent support.
- No media library refresh.
- No full task-center UI or generic workflow engine.
- No full-project validation command.

## Acceptance Criteria

- Submitting a valid Anime whole-season magnet returns a persistent `taskId`.
- In-progress duplicate magnet hash submission returns the same task.
- Worker writes task logs for each major OpenList and organize stage.
- Successful downloads leave recognizable video/subtitle files flat in the
  target Season directory using ANI-RSS-compatible names.
- Unrecognized or duplicate files are deleted and produce `PARTIAL_SUCCESS`
  when at least one file was organized.
- Startup marks unfinished tasks `INTERRUPTED`.
