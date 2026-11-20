# Verification

## Scope
Implemented common PanSou/Prowlarr candidate comparison and dual file-table evidence (Guangya / original torrent). Guangya 353 is a source refusal eligible for fallback; known unsafe structure or identity failures remain non-bypassable. No production changes, credential import, real media download, push or deployment.

## Checks performed
- Red: pre-implementation public Prowlarr resolution test exposed acceptance of torrent trailing bytes (1 test / 1 failure).
- Red: initial ReleaseFileTables regression could not compile before the new dual-source operation existed.
- Java 17 focused Maven run, serial shared target: ProwlarrTorrentResolutionTest, TorrentMetadataTest, ReleaseFileTablesTest, ReleaseCandidateComparisonTest, ReleaseSubmissionSafetyTest, ReleaseSubmissionAcceptanceTest, ProwlarrReleaseIngestServiceTest, GuangyaClientTest, ProwlarrReleaseSearchCacheTest, ReleaseFileAssessmentTest, OpenListIngestTaskCenterServiceTest, BgmWishlistTaskGatewayTest, BgmWishlistOperationTest, BgmWishlistWorkflowTest.
- Final post-review result: 187 tests, 0 failures, 0 errors, 0 skipped (14 classes). No unrelated full suite run.
- Frontend `node --experimental-strip-types --test test/release-comparison.test.ts`: pass. The network-unavailable message is an intentional mocked failure-path assertion.
- ESLint passed for release-selection.ts, resources/index.tsx, resources/publish.tsx, automation/bgm-candidate-dialog.tsx and release-comparison.test.ts.
- Both repositories `git diff --check`: pass.

## Review
One bounded read-only review found five concrete defects, all fixed and retested: cold selected submissions discarded original tables; conflicting magnet xt could be filled by search hash; canonicalization lost dn evidence; same-hash publications lost backup references/positive peers; external torrent URL authentication was incorrectly stripped. Regression tests cover these paths. No second review pass.
Source-level cleanup removes the replaced title-only recommendation branch; shared file-table comparison is the only new-search recommendation path.

## Runtime limits
Network probes in the preceding research used existing browser Guangya session, not newly configured application credentials. This implementation has not been deployed or tested by creating a production ingest task. Original torrent parsing is bounded metadata fetching, not media download. Optional integrations can remain unconfigured; fallback may succeed without Guangya when a valid original torrent is available.
