# External Integrations

> Conventions and known integration details for upstream services used by the
> orchestrator.

---

## ANIRSS

- Do not infer the final ANIRSS HTTP path from controller `@PostMapping` values
  alone. Check global route configuration such as ANIRSS
  `WebMvcConfig.configurePathMatch()`.
- ANIRSS adds `/api` to `@RestController` routes, so controller mappings like
  `/searchBgm` are exposed as `/api/searchBgm`.
- When adding or debugging an ANIRSS endpoint, verify the final route with a
  direct upstream request before changing the orchestrator client path.
- Ani-RSS timeout defaults belong in `application.yml` and are bound through
  `AniRssProperties`. The client should consume the configured timeout directly
  and should not provide a second fallback value.

## OpenList

- OpenList defaults belong in `application.yml` / `.env` placeholders. Avoid
  duplicating defaults in `OpenListProperties` fields, client helpers, and
  business services.
- Keep OpenList `base-url` and `authorization` optional at application startup
  so the service can run when OpenList-backed features are unused. Validate them
  inside the OpenList client before making an upstream call.
- Keep tool names, delete policy, retry limits, and timeouts as validated
  configuration. Callers should not second-guess them with fallback logic.
- For anime path and rename templates, use `{placeholder}` syntax in default
  configuration values, for example `{themoviedbName}` and `{seasonFormat}`.
  Avoid `${placeholder}` in YAML defaults because Spring treats it as a
  configuration placeholder. Existing code may accept `${placeholder}` only for
  backward compatibility with older local configuration.
- OpenList offline task wait defaults should be short enough to fail visibly
  for broken magnet jobs. The current default is `10m`; callers may override it
  through `MEDIANEXUS_OPENLIST_OFFLINE_TIMEOUT`.
- Reuse the established media organization lifecycle after files land under the
  final save path: build the complete plan first, rename files in their current
  parent directories, move files from nested directories into the final target
  directory, delete rejected files, and finally remove empty directories.
  Movie, series, and anime ingestion already follow this ordering.
- Do not create application-owned hidden, staging, transaction, or temporary
  directories in OpenList for direct-upload workflows. Upload files directly
  under the resolved target media directory, perform all collision checks
  before writing, then use the existing OpenList rename/move/remove operations
  only when the actual source layout requires them. Local process temporary
  directories used for bounded archive inspection and extraction are allowed;
  they must not be mirrored into OpenList.

## Conditional Integrations

- For conditionally enabled integrations such as the development database SSH
  tunnel, keep defaults in `application.yml` but validate required connection
  fields only when the feature is enabled and the lifecycle starts.
- Do not add unconditional Bean Validation annotations to disabled-by-default
  integration credentials if that would block unrelated application startup.
- For the development database SSH tunnel, use the system OpenSSH client rather
  than an in-process SSH library. The tunnel is a local-development convenience;
  deployments that run beside the database should disable it and connect to the
  server-local MySQL port directly. The OpenSSH path may use an askpass helper
  that reads the password from the child process environment, but the helper
  file must not contain the raw password and logs must never print passwords or
  raw credential values.

## Scenario: Guangya read-only evidence and candidate comparison

### 1. Scope / Trigger
- Movie/series magnet discovery and all shared task acceptance paths. Guangya is metadata-only; downloading remains OpenList/PikPak/CD2.

### 2. Signatures
- `GuangyaClient.resolve(String magnet, ResolveBudget budget)` returns a validated `TorrentFileTable`; `cached(String)` does not spend HTTP budget.
- `GET /api/v1/resources/releases/comparisons/{id}` reads an owner-isolated snapshot; `POST .../{id}/continue` explicitly starts another bounded round.
- Existing search/recommendation responses include nullable `comparison`; each candidate includes nullable `evidence`.
- `ReleaseFileTables.resolve(ProwlarrRelease, ResolveBudget)` obtains a validated table from Guangya or the original torrent; the string-magnet operation and submission share its bounded cache.
- `ProwlarrClient.resolveTorrent(ProwlarrRelease, Duration)` reads only magnet/torrent metadata, returning `ResolvedTorrent`; it never creates an offline task.

### 3. Contracts
- Only `resolve_res` and the verified refresh-token grant are called. No create_task, restore, transfers or directory operations.
- Credentials are HOT administrator settings `guangya.accessToken/refreshToken/deviceId`, encrypted via SettingsCipher. No environment token binding. Rotation uses the existing settings transaction/CAS.
- A movie comparison round includes search in 45s; a series comparison round has 75s total, reserving room for the observed ~36s supplemental search plus up to 30s file resolution. First and explicit continued rounds use the same media-specific deadline. At most four search terms (concurrency two), resolution in at most 30s starting after series supplement search, at most 20 new resolve HTTP attempts (retries count), concurrency two, request timeout eight seconds, one shared authentication refresh. Cache and in-flight same-hash requests are reused.
- Comparison states: RUNNING, COMPLETE, PAUSED, BLOCKED. Evidence states: RECOMMENDABLE, NEEDS_PROCESSING, UNSUITABLE, UNVERIFIED. Unknown counts and sizes remain null.
- Automatic recommendation may select an already RECOMMENDABLE candidate while other candidates are PAUSED/BLOCKED (user-approved 2026-11-22); this is the best verified current choice, not the whole-queue optimum. Preserve comparison status, exact identity, executable file evidence, positive Prowlarr seeders and the common submission safety check. No automatic unverified-risk override.
- Filename resolution/subtitle/version tags remain declarations, not media inspection. Normal versions precede accepted preview versions, then existing allowed resolution/subtitle priorities; target bytes are the final size comparison, separate from whole-package bytes.
- Standalone promotional video names made entirely of the verified “更多(无水印)高品质资源请访问 www.domain” wording (including repeated complete bracket blocks) are auxiliary regardless of positive file size. Preserve bracket boundaries: a promotion block followed by a title/episode block is not a standalone advertisement. Movie/Adult use the shared feature-video qualification; series preserve short genuine episodes. Unknown or mixed-title videos and subtitles are not auto-deleted by this rule; filenames do not establish actual video content.
- Snapshot and evidence caches are bounded and process-local; restart/expiry requires another search. Failed searches retry only on explicit continuation. Ordinary request timeouts are not automatically retried.
- PanSou and Prowlarr use `ReleaseCandidateComparison`. Prowlarr retains search hash, indexer/reference, backup torrent reference and peer metadata; source size is separate from file-table total/target size. Direct magnets and title-incomplete related candidates remain eligible for evidence.
- Known BTIH uses Guangya first, then the original torrent on source unavailability/rejection. References without BTIH fetch original metadata first; a returned magnet can use Guangya. Successful evidence is reused; there is no mandatory duplicate fetch. Guangya code 353 is a source refusal, not an invalid file table, and may fall back to a safely validated original torrent. Retain source and fallback reason.
- Original torrent metadata is limited to 4 MiB, depth 32, 20,000 nodes; reject duplicate keys/trailing bytes, malformed bencode, unsafe/duplicate/conflicting paths, symlinks, hash mismatch and inconsistent piece sizes. Recompute BTIH from raw info bytes. Known safety failures cannot be washed by another source or human permission.
- Up to 20 original-reference operations per round, at most six HTTP requests per operation, each at most eight seconds and all inside the shared remaining deadline. Prowlarr automation additionally preserves its positive-seeder requirement; peer counts never establish content safety.

### 4. Validation & Error Matrix
| Condition | Outcome |
| --- | --- |
| Invalid hash, unsafe/duplicate paths, inconsistent leaf count/size, structure limits | No success cache; reject submission even with risk permission |
| Auth/quota/persistence failure | Try an available original torrent source; otherwise keep unavailable evidence (PanSou-only comparison may BLOCK), never call it dead/zero episodes |
| Budget exhausted with pending queue | PAUSED; preserve pending evidence; automation may use an independently verified executable candidate, otherwise no submission |
| Mixed seasons, unmapped absolute numbering, unknown video or duplicate main version | NEEDS_PROCESSING; never relax unknown-video deletion safety |
| Missing credentials or unavailable metadata | UNVERIFIED; administrator setup or explicit human risk acceptance |

### 5. Good / Base / Bad Cases
- Good: a small Chernobyl pack with explicit S01E01–E05 is assessed by filenames rather than a title-size threshold.
- Base: 01–06 normal + 07–12 preview remains one executable backup package; preserve version markers in all output names.
- Bad: accepting an El Camino film as Breaking Bad S1, treating `.ts` as a camera release, or inferring an absolute-number offset from file count.

### 6. Tests Required
- Mock HTTP only-read routes, path/hash/size/count bounds, same-hash sharing, budget accounting, refresh merging/recovery and encrypted CAS persistence.
- Full discovered-queue comparison, layer-fair scheduling, paused continuation, failed-search recovery, raw/selected/retry safety parity, nullable UI states and cancellation.
- Preserve unknown/conflicting/zero-feature deletion regressions and all twelve mixed-version CD2 targets.

### 7. Wrong vs Correct
- Wrong: return the first title-qualified item and call unresolved metadata `0/12`.
- Correct: preserve full-queue progress and unknown counts; bounded comparison may pause, but unresolved alternatives do not invalidate a separately verified current choice. Missing configuration/authentication, search failures and time/request limits have distinct outcomes.

## Adult download timeout discard

- Adult uses the shared scheduler's `DiscardableDownload` contract: at the configured deadline, cancel once, remove only that item's direct `adult-task-*` temporary directory, persist failure/cleanup results, then release local claims. No held-task polling or automatic replay follows this timeout path.
- Cleanup uses a five-second total HTTP budget (cancel at most two seconds); acknowledgement is recorded as acknowledgement, not proof that every remote effect has stopped. Failure reasons remain visible. Never delete the shared date directory or already promoted successful files.
- Movie/TV retain their existing cancellation reconciliation and organization recovery. Adult terminal historical error strings no longer rebuild directory/hash claims at startup; interrupted active writes still require separate reconciliation.
- Adult failure checkpoint recovery recomputes the batch failed count from item states rather than incrementing again; database response loss must not repeat cleanup or double-count failures.
