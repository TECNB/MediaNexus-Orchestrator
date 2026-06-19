# Movie Subtitle OpenList Upload

## Goal

Migrate the working movie subtitle upload flow from MediaNexus-Core (Python) to
MediaNexus-Orchestrator (Java), while replacing the old SSH/SFTP and
Radarr-dependent path resolution with the Orchestrator's existing OpenList and
movie-folder conventions.

The user selects a movie and uploads either one subtitle file or one ZIP
archive. Java derives the same movie directory used by manual magnet ingest,
checks that the directory and a real video file already exist, safely inspects
the upload, renames every accepted subtitle against the selected target video,
and uploads the final files directly into that movie directory through the
OpenList API.

The request is synchronous. A persistent upload record and step logs remain
queryable for troubleshooting. Auto_Symlink (AS) later observes the source
directory and copies the subtitle files into the generated STRM directory.

Requirements discovery was completed through the `/grill-me` interview. This
PRD records the converged decisions and supersedes the old Core behavior where
the two designs differ.

## Current Core Behavior

The Python implementation currently:

- accepts one subtitle file or one ZIP;
- supports manual `target_path` or movie association fields;
- may resolve movies through Radarr;
- supports `.srt`, `.ass`, `.ssa`, and `.sub`;
- allows only one accepted subtitle inside a ZIP;
- inspects the remote directory through SSH/SFTP;
- prefers a `.strm` file, otherwise the largest real video;
- renames the subtitle to the selected media basename;
- uploads through SFTP;
- optionally refreshes Emby after upload.

The implementation is operational, but these parts are not carried forward:

- Radarr is not the source of truth for target-folder existence.
- The frontend must not choose or send a server target path.
- SSH/SFTP is unnecessary because OpenList already owns the relevant storage.
- The source movie directory does not contain the generated STRM file.
- ZIP archives commonly contain multiple subtitles and must not be restricted
  to one subtitle.
- Emby refresh is not part of this operation because AS owns the later subtitle
  migration and media-library refresh will be designed centrally.

## Scope

### In

- Movie subtitle upload only.
- One multipart `file`, containing either:
  - one configured subtitle type; or
  - one ZIP containing one or more configured subtitle types.
- Target movie directory derivation from `title`, optional `original_title`,
  and `year`.
- OpenList target directory inspection and direct file upload.
- Safe ZIP inspection/extraction with bounded resource usage.
- Movie subtitle rename planning, collision detection, and optional overwrite.
- Persistent upload history and per-upload logs.
- Frontend cutover from Core to the Java endpoint.

### Out

- Series and anime subtitle upload endpoints.
- A generic media-type abstraction reserved for future implementations.
- Frontend-selected `target_path`.
- Radarr/Sonarr lookup during subtitle upload.
- SSH/SFTP upload.
- STRM file discovery or writes to the STRM directory.
- AS API integration, observer restart, refresh, or result polling.
- Emby/Jellyfin refresh.
- Multiple independent multipart subtitle files in one request.
- Async workers, message queues, distributed locks, or process-local path locks.
- A new subtitle-upload quota action or changes to the existing daily content
  creation quota.
- Strict storage-level transaction guarantees.
- Long-term storage of the uploaded ZIP or subtitle contents.

## Converged Decisions

1. **First slice is movie-only**
   Do not prebuild series/anime mode switches or DTO fields. Shared archive
   safety and filename utilities may be reusable later, but the public API and
   business service remain explicitly movie-oriented.

2. **No Radarr dependency**
   The selected movie metadata is used only to derive the same OpenList path as
   manual movie magnet ingest. Folder existence is checked directly in
   OpenList.

3. **Frontend sends movie identity, not a path**
   Request fields are `file`, `title`, optional `original_title`, `year`, and
   `overwrite`. Do not accept the old `target_path`, `media_type`, TMDB/Radarr
   association fields, or `library_title/library_year` aliases.

4. **Reuse the manual movie folder rule**
   Prefer `original_title` when present, otherwise `title`; call
   `MovieSeriesFileRenameService.movieFolderName(preferredTitle, year)` and
   join it with `OpenListProperties.movieRootPath`.

5. **Existing movie directory is required**
   Subtitle upload must not create the movie directory. A missing directory
   means the movie has not been ingested or its title/year does not match, and
   the request fails with a clear message.

6. **Use a real source video**
   Do not inspect or prefer `.strm`. List real video files in the target movie
   directory using the existing video-extension rules. If several exist, select
   the largest file as the primary video.

7. **Trust the user's movie selection**
   Subtitle filename matching is diagnostic only. A poor or cross-language
   match produces a `WARN` log but does not reject the upload.

8. **Rename before OpenList upload**
   Java performs archive extraction, planning, validation, and final filename
   creation locally. OpenList receives files under their final names directly
   in the target movie directory.

9. **No OpenList staging directory**
   Do not create hidden, task-id, transaction, or temporary directories in
   OpenList. This follows the repository's OpenList integration rules. A bounded
   local process temporary directory is allowed for ZIP extraction.

10. **AS-configured subtitle types**
    Java exposes a configurable allowed-extension list. The initial deployment
    value mirrors the current AS subtitle metadata configuration:
    `.ass`, `.srt`, `.sup`.

11. **ZIP remains a supported transport**
    ZIP is common on subtitle sites. Java never uploads the ZIP to OpenList or
    AS; it extracts only accepted subtitle entries and uploads the resulting
    files.

12. **Multiple subtitles are supported**
    ZIP archives may contain multiple languages, variants, or editions. The old
    Core rule that rejects more than one subtitle is removed.

13. **Synchronous operation with persistent diagnostics**
    The POST waits for validation and OpenList upload completion. It also
    creates a persistent upload row plus chronological log rows.

14. **Two tables only**
    Use `subtitle_uploads` and `subtitle_upload_logs`. Per-file information is
    stored as serialized JSON in the upload row; do not create a third
    `subtitle_upload_files` table.

15. **No original file retention**
    Persist names, sizes, hashes, mappings, decisions, and errors. Delete local
    temporary contents at request completion and never persist the ZIP or
    subtitle bytes in the application.

16. **Overwrite is explicit**
    `overwrite` defaults to `false`. Existing remote files with planned final
    names cause HTTP 409 and list the conflicting names. The frontend may
    resubmit with `overwrite=true`.

17. **Overwrite is narrowly scoped**
    Only exact final filenames from the current upload may be overwritten.
    Other subtitles in the movie directory are untouched.

18. **Best-effort cleanup, not a transaction**
    All possible checks happen before the first upload. If a later OpenList
    upload fails, delete files already uploaded by this batch. Previously
    overwritten content cannot be restored; log that limitation explicitly.

19. **No concurrency control in the first slice**
    Do not add target-path locks, lock timeouts, or distributed coordination.
    Keep the implementation small until a real concurrent-upload problem
    appears.

20. **Java success ends at OpenList**
    The upload is successful once all planned files have been accepted by
    OpenList in the source movie directory. Log `WAITING_FOR_AS` as the
    post-upload boundary, but return the upload record as `SUCCEEDED`. Do not
    wait for AS or inspect its output.

## API Contract

### Upload

`POST /api/v1/subtitles/uploads`

Content type: `multipart/form-data`

Fields:

| Field | Required | Meaning |
|---|---:|---|
| `file` | yes | One `.ass`, `.srt`, `.sup`, or `.zip` file |
| `title` | yes | Selected movie title |
| `original_title` | no | Original movie title; preferred for folder derivation |
| `year` | yes | Movie release year |
| `overwrite` | no | Defaults to `false` |

Success response data:

```json
{
  "upload_id": "uuid",
  "status": "SUCCEEDED",
  "target_path": "/movie-root/Dune Part Two (2024)",
  "primary_video": "沙丘2.2024.2160p.mkv",
  "saved_files": [
    "沙丘2.2024.2160p.chs.srt",
    "沙丘2.2024.2160p.cht.srt"
  ],
  "ignored_files": [
    "__MACOSX/readme.txt"
  ],
  "overwrite": false
}
```

All responses use the existing `ApiResponse<T>` envelope. Java DTO fields may
use camelCase internally with `@JsonProperty` for the snake_case wire contract.

### History

`GET /api/v1/subtitles/uploads`

- Require authentication.
- Return the most recent 20 uploads ordered by `created_at DESC`, matching the
  current magnet-task list convention.
- Normal users see only their own rows.
- Administrators see uploads from all users.

### Logs

`GET /api/v1/subtitles/uploads/{uploadId}/logs`

- Require authentication.
- Normal users may access only their own upload logs.
- Administrators may access all upload logs.
- Return chronological rows ordered by log id.

A separate detail endpoint is not required in the first slice; the POST result
and history row contain the persisted upload summary.

## End-to-End Flow

1. Require the current user.
2. Validate multipart fields and supported top-level file extension.
3. Create a `PROCESSING` upload record and the initial `created` log.
4. Derive the target movie path using the existing movie-folder naming service.
5. Verify the target directory exists in OpenList; do not create it.
6. List target directory files and select the largest real video.
7. Copy the incoming stream into a bounded local temporary workspace while
   calculating source size and SHA-256.
8. For a direct subtitle, create a one-item subtitle batch.
9. For a ZIP, safely inspect and extract only configured subtitle entries.
10. Build all final subtitle filenames locally.
11. Run diagnostic filename matching and write `INFO` or `WARN`.
12. Verify final names are unique and inspect OpenList for remote conflicts.
13. If conflicts exist and `overwrite=false`, mark the upload `FAILED`, record
    the conflict list, and return HTTP 409.
14. Upload each local subtitle directly to its final OpenList path.
15. If an upload fails, remove files already uploaded by this batch, mark
    `FAILED`, and retain the diagnostic record and logs.
16. On complete success, persist the final manifest and counts, write the
    `WAITING_FOR_AS` boundary log, and mark the upload `SUCCEEDED`.
17. Delete the local temporary workspace in `finally`.

## Target Resolution

### Folder

```text
preferredTitle = original_title when non-blank, otherwise title
folderName = movieFolderName(preferredTitle, year)
targetPath = joinPath(movieRootPath, folderName)
```

The rule must be byte-compatible with the manual movie magnet path. Reuse
`MovieSeriesFileRenameService.movieFolderName`; do not duplicate its sanitizing
regular expressions in the subtitle service.

### Primary Video

- Inspect only the resolved movie source directory.
- Reuse `MovieSeriesFileRenameService.isVideo`.
- Ignore directories and non-video files.
- Select the video with the greatest non-null size.
- Use a deterministic filename tie-break when sizes are equal.
- Fail if no real video is present.
- The subtitle base name is the selected video's filename without its final
  video extension.

## Subtitle Naming

### Direct File Or One Subtitle In ZIP

Discard the uploaded movie name and use the selected video basename:

```text
沙丘2.2024.2160p.mkv
Dune.Part.Two.Chinese.srt

=> 沙丘2.2024.2160p.srt
```

### Multiple Subtitles In ZIP

The archive's internal directory hierarchy is ignored for destination layout.
Original entry paths remain in logs and the file manifest.

Determine a shared movie-name prefix from subtitle stems using complete
filename segments, not a raw character prefix:

- segment boundaries include `.`, `_`, `-`, whitespace, `[]`, and `()`;
- the common prefix must end at a complete segment boundary;
- remove the common prefix from each subtitle stem;
- keep each remaining tail as the distinguishing suffix;
- do not translate or semantically reinterpret values such as `chs`, `cht`,
  `eng`, `forced`, or `简中&英文`;
- normalize runs of spaces and filename separators to one `.`;
- remove path separators, control characters, and unsafe filename characters;
- trim leading/trailing separators and brackets.

Example:

```text
Dune.Part.Two.2024.chs.srt
Dune.Part.Two.2024.cht.srt

=> 沙丘2.2024.2160p.chs.srt
=> 沙丘2.2024.2160p.cht.srt
```

Another example:

```text
Dune.2024.[简中&英文].ass
=> 沙丘2.2024.2160p.简中&英文.ass
```

If no reliable complete-segment common prefix can be extracted, fail the whole
batch before upload. If normalization produces duplicate final filenames, fail
the whole batch and log the conflicting source entries. Do not append numeric
suffixes because that would discard subtitle semantics.

### Diagnostic Match

Normalize the detected archive movie prefix and compare it using simple
contains/equality checks against:

- `title`;
- `original_title`;
- target folder name;
- primary video basename.

At least one match produces `INFO`. No match produces `WARN` with the compared
values, but processing continues. Do not add fuzzy scoring, cross-language
translation, external metadata lookup, or a "highest score" selection system.

## ZIP Safety Contract

### Limits

- Top-level ZIP size: maximum `50 MB`.
- Maximum accepted subtitle entries: `100`.
- `.srt` or `.ass` uncompressed size: maximum `10 MB` each.
- `.sup` uncompressed size: maximum `50 MB` each.
- Total accepted uncompressed subtitle size: maximum `150 MB`.
- Maximum per-entry compression ratio: `100:1`.

Expose these values through one `SubtitleUploadProperties` configuration owner;
keep defaults in `application.yml`.

### Rejection Rules

Reject the entire upload before OpenList writes when the archive contains:

- an encrypted entry;
- an absolute path;
- `..` path traversal;
- a symbolic-link entry;
- an accepted subtitle entry exceeding its size limit;
- too many accepted subtitle entries;
- an accepted subtitle exceeding the compression-ratio limit;
- accepted subtitle total size exceeding the configured limit;
- no accepted subtitle files;
- a malformed ZIP.

### Ignored Entries

- Ignore non-subtitle files and record their archive paths.
- Ignore nested archives as ordinary non-subtitle files; do not recursively
  extract them.
- Do not write ignored entries to the local extraction directory.
- Ignore ZIP directory hierarchy when building destination filenames.

Use Apache Commons Compress for ZIP entry metadata, including encryption flags
and Unix symbolic-link detection, plus normalized `Path` containment checks for
extraction. Do not rely only on string replacement to prevent ZIP Slip. Keep
this dependency scoped to archive handling; do not introduce a generic archive
framework.

## OpenList Upload Contract

Add one focused operation to `OpenListClient`, conceptually:

```java
void uploadFile(Path localFile, String remotePath, boolean overwrite)
```

The implementation uses OpenList's stream upload endpoint:

```text
PUT /api/fs/put
Authorization: <configured value>
File-Path: <URL-encoded absolute OpenList file path>
Overwrite: true|false
Content-Type: application/octet-stream
Content-Length: <known local file size>
```

Use a known `Content-Length` rather than a chunked request. Subtitle files are
bounded and locally materialized before upload, and fixed-length streaming
avoids known compatibility differences between OpenList versions/drivers.

The client operation:

- validates optional OpenList configuration only when called;
- streams the local file without loading it fully into memory;
- preserves the existing OpenList response-envelope checks;
- never logs Authorization or file contents;
- does not create directories;
- does not hide overwrite decisions from the calling service.

## Persistence

### `subtitle_uploads`

Suggested first-slice columns:

| Column | Type | Notes |
|---|---|---|
| `id` | `VARCHAR(36)` | UUID primary key |
| `status` | `VARCHAR(32)` | `PROCESSING`, `SUCCEEDED`, `FAILED` |
| `stage` | `VARCHAR(64)` | Current/final stage |
| `title` | `VARCHAR(255)` | Submitted movie title |
| `original_title` | `VARCHAR(255)` | Nullable |
| `year` | `INT` | Submitted year |
| `source_filename` | `VARCHAR(1024)` | Original top-level upload name |
| `source_size` | `BIGINT` | Received file size |
| `source_sha256` | `CHAR(64)` | Upload hash |
| `target_path` | `VARCHAR(1024)` | Derived OpenList movie path |
| `primary_video` | `VARCHAR(1024)` | Selected real video filename |
| `overwrite_enabled` | `BOOLEAN` | Submitted overwrite flag |
| `file_manifest` | `LONGTEXT` | Serialized JSON file decisions/mappings |
| `saved_count` | `INT` | Successfully uploaded subtitles |
| `ignored_count` | `INT` | Ignored non-subtitle entries |
| `created_by_user_id` | `BIGINT` | Owner |
| `error_message` | `VARCHAR(1024)` | Nullable terminal summary |
| `created_at` | `DATETIME` | Creation time |
| `updated_at` | `DATETIME` | Last update |
| `finished_at` | `DATETIME` | Nullable terminal time |

Indexes:

- primary key on `id`;
- `(created_by_user_id, created_at)`;
- `created_at`.

The manifest JSON contains:

- original archive path;
- original filename;
- original size and SHA-256 for accepted subtitle entries;
- final filename;
- outcome (`SAVED`, `IGNORED`, `CONFLICT`, `ROLLED_BACK`, `FAILED`);
- overwrite information when applicable.

Do not expose local temporary paths.

### `subtitle_upload_logs`

Columns follow the established task-log shape:

- `id BIGINT AUTO_INCREMENT`;
- `upload_id VARCHAR(36)`;
- `level VARCHAR(16)`;
- `stage VARCHAR(64)`;
- `message VARCHAR(1024)`;
- `detail TEXT`;
- `created_at DATETIME`;
- index `(upload_id, id)`.

Register both table creation methods in `DatabaseInitializer`, following the
existing mapper-owned schema initialization pattern. Do not introduce Flyway or
Liquibase in this task.

## Stages And Logs

Recommended stage values:

- `created`
- `validating`
- `resolving_target`
- `inspecting_archive`
- `planning_names`
- `checking_conflicts`
- `uploading`
- `waiting_for_as`
- `succeeded`
- `failed`

Required user-facing logs include:

- received top-level filename, size, and source hash;
- derived target path;
- selected primary video;
- accepted and ignored ZIP entry counts;
- original entry path to final filename mapping;
- diagnostic movie-prefix match result;
- remote conflict list;
- each upload start/success/failure;
- cleanup/rollback actions;
- `WAITING_FOR_AS` after all OpenList uploads succeed;
- final saved/ignored counts and duration.

Server logs additionally include upload id and user id. Never log OpenList
authorization, AS keys, uploaded file contents, or local temporary paths.

## Conflict And Failure Semantics

### Preflight

Before the first OpenList upload:

- validate all request fields and configured limits;
- resolve the directory and primary video;
- finish ZIP extraction;
- calculate all final names;
- ensure final names are unique;
- list existing target files;
- identify every remote conflict.

### Existing Files

- `overwrite=false`: fail with HTTP 409. The Chinese error message includes
  the conflicting filenames, and the upload manifest/logs retain the full list.
- `overwrite=true`: pass `Overwrite: true` only for the current planned final
  paths. Do not delete or replace unrelated subtitles.

### Mid-Batch Failure

- Stop uploading remaining files.
- Best-effort remove files successfully uploaded by this batch.
- Do not claim strict atomicity.
- If an old file was overwritten, it cannot be restored by this first-slice
  design.
- Mark the upload `FAILED` even when cleanup itself partially fails.
- Write `WARN` logs for cleanup failures and preserve all affected filenames in
  the manifest.

## Error Matrix

| Condition | HTTP | Message intent |
|---|---:|---|
| Missing/invalid multipart field | 400 | Explain the invalid field |
| Unsupported top-level type | 400 | Only configured subtitle types or ZIP |
| ZIP safety/limit violation | 400 | State the violated archive rule |
| Movie root not configured | 503 | OpenList movie root is unavailable |
| Derived movie directory missing | 404 | Movie directory not found; verify ingestion/title/year |
| No real video in directory | 400 | No target video found |
| Common-prefix extraction failed | 400 | Subtitle batch naming could not be resolved |
| Planned final-name collision | 400 | List conflicting archive entries |
| Remote conflict without overwrite | 409 | List existing final filenames |
| OpenList unavailable/upload failed | 502 | Subtitle upload to OpenList failed |
| Unexpected internal error | 500 | Unified internal error response |

Every failure after upload-record creation updates the record to `FAILED` and
writes a terminal log before returning the unified error envelope.

## Configuration

Add a focused configuration owner:

```yaml
medianexus:
  subtitle-upload:
    allowed-extensions: .ass,.srt,.sup
    max-upload-size: 50MB
    max-entry-count: 100
    max-text-subtitle-size: 10MB
    max-sup-size: 50MB
    max-total-extracted-size: 150MB
    max-compression-ratio: 100
```

Environment placeholders use the `MEDIANEXUS_SUBTITLE_UPLOAD_*` prefix.

The allowed-extension value must be deployed consistently with AS
`metadata_ext`. The Java service does not read the AS config file and does not
require AS to be online during upload.

No AS `base-url` or `api-key` property is added in this task.

## Frontend Cutover

Update the existing subtitle page and API wrapper:

- keep the existing single file picker;
- accept configured subtitle types plus ZIP;
- remove the old Core `media_type`, `library_title`, and `library_year` payload;
- send `title`, optional `original_title`, `year`, and `overwrite`;
- use `javaApiClient` and the standard Java `ApiResponse` contract;
- keep the user-selected movie as the trust boundary;
- show the returned target path and saved filenames;
- on HTTP 409, show the conflicting names and allow resubmission with overwrite;
- replace static/mock history with the Java upload-history endpoint where the
  page currently exposes upload records;
- show per-upload logs using the new log endpoint.

Do not expose a formal target-path selector.

## Reuse Plan

Reuse:

- `MovieSeriesFileRenameService.movieFolderName`;
- `MovieSeriesFileRenameService.isVideo`;
- `OpenListProperties.movieRootPath`;
- `OpenListClient` path normalization, listing, removal, and response handling;
- `ApiResponse`, `BusinessException`, authentication, admin role checks;
- MyBatis-Plus model/mapper patterns;
- `DatabaseInitializer`;
- task-log DTO and access-control conventions;
- frontend `javaApiClient` and Java error-message helper.

Add narrowly scoped components for:

- movie subtitle upload orchestration;
- archive inspection/extraction;
- subtitle batch naming;
- upload persistence and logs;
- OpenList stream upload.

Do not copy Core's service wholesale. Port validated business behavior, then
apply the decisions in this PRD.

## Expected Files

### New

- `config/SubtitleUploadProperties.java`
- `controller/SubtitleUploadController.java`
- `service/MovieSubtitleUploadService.java`
- focused archive/naming helper classes where they remove real complexity
- `model/SubtitleUpload.java`
- `model/SubtitleUploadLog.java`
- `mapper/SubtitleUploadMapper.java`
- `mapper/SubtitleUploadLogMapper.java`
- request/response/log DTOs under a subtitle package
- focused unit tests for archive safety and naming

### Modified

- `integration/openlist/OpenListClient.java` — stream upload method.
- `config/DatabaseInitializer.java` — create the two subtitle tables.
- `src/main/resources/application.yml` — subtitle upload defaults.
- `.env.production.example` — subtitle upload environment placeholders.
- `pom.xml` — add Apache Commons Compress for ZIP metadata and safe extraction.
- `MediaNexus/src/lib/api/subtitles.ts` — Java multipart contract/history/logs.
- `MediaNexus/src/types/subtitles.ts` — new Java response types.
- `MediaNexus/src/pages/subtitles/index.tsx` — payload and real history/log UI.

Core files are not deleted in this task. The frontend cutover makes Java the
active path while preserving Core as a rollback reference.

## Series-Compatible Foundations

The first slice does not implement series upload, but these internal results
must not assume there is always one subtitle:

- archive inspection returns a list of accepted subtitle entries;
- per-file metadata is preserved in the manifest;
- limits support a season subtitle pack;
- destination naming is a separate phase after safe extraction.

A future series implementation will replace movie trust-based association with
episode parsing and per-video mapping. It can reuse archive safety, hashing,
manifest persistence, OpenList upload, and logs without changing the movie API.

Do not add series fields, episode DTOs, or dormant mode branches now.

## Acceptance Criteria

- [ ] The frontend submits one movie subtitle/ZIP to Java using movie title,
      optional original title, year, and overwrite.
- [ ] Java derives the exact same movie directory as manual movie magnet ingest.
- [ ] A missing target movie directory fails and is never created.
- [ ] The largest real video is selected; `.strm` is never inspected.
- [ ] Direct `.ass`, `.srt`, and `.sup` uploads are renamed to the primary video
      basename before OpenList upload.
- [ ] A ZIP may contain multiple accepted subtitles.
- [ ] Multiple ZIP subtitles retain cleaned distinguishing tails after the
      complete-segment common prefix is replaced by the primary video basename.
- [ ] Prefix diagnostic mismatch logs `WARN` but does not reject the upload.
- [ ] Common-prefix failure or final-name collision rejects the whole batch
      before any OpenList write.
- [ ] ZIP Slip, symlink, encryption, size, count, total-size, and compression
      ratio rules are enforced.
- [ ] Non-subtitle and nested-archive entries are ignored and logged without
      being extracted.
- [ ] `overwrite=false` returns HTTP 409 with the remote conflict names.
- [ ] `overwrite=true` overwrites only exact planned final paths.
- [ ] Files are uploaded directly under final names in the movie directory; no
      OpenList staging directory is created.
- [ ] Mid-batch failure triggers best-effort deletion of files uploaded by the
      current batch and records any cleanup failure.
- [ ] Success means OpenList accepted all files; AS and Emby are not called.
- [ ] The history endpoint enforces owner/admin visibility.
- [ ] The log endpoint enforces the same access boundary and returns
      chronological logs.
- [ ] Only `subtitle_uploads` and `subtitle_upload_logs` are introduced.
- [ ] Uploaded bytes and local temporary paths are not persisted.

## Focused Verification

Do not run full-project validation unless explicitly requested.

Required focused coverage during implementation:

- movie target-path parity with `MagnetIngestService`;
- largest-video selection and deterministic tie behavior;
- single subtitle rename;
- multi-subtitle common-prefix/tail rename;
- Chinese and bracketed suffix preservation;
- diagnostic mismatch continues;
- duplicate final-name rejection;
- ZIP traversal, symlink, encryption, size, entry-count, total-size, and ratio
  rejection;
- ignored entry behavior;
- remote conflict with and without overwrite;
- OpenList upload request headers and fixed content length;
- best-effort cleanup after a simulated mid-batch failure;
- normal-user/admin history and log access.

Minimum non-test check for documentation-only work is `git diff --check`.

## Implementation Notes

- Keep the operation synchronous; do not introduce an executor.
- Insert the upload record early enough that validation/upload failures remain
  diagnosable, but validate basic authentication and malformed multipart input
  before persistence where Spring already owns the error.
- Stream hashing/copying and OpenList upload; do not load `.sup` or ZIP contents
  fully into heap memory.
- Build the complete rename and conflict plan before the first upstream write.
- Keep user-facing messages and OpenAPI descriptions in Chinese, matching the
  repository conventions.
- Do not add fake OpenAPI examples that prefill Knife4j forms.
- Follow the repository commit identity and Conventional Commit requirements.
