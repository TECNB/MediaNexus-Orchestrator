# Verification

## Implementation

Media-library item deletion now stores and deletes exact deduplicated Emby MediaSources paths, reusing existing CD2 account mapping and Quark file-ID routing. It no longer resolves STRM content, consults capacity folder inference, or supplies title/season hints for remote directory deletion. Local STRM cleanup and Emby notification/verification remain. Missing/blank/local STRM-only movie/episode/member paths are refused; a requested multi-version response with a blank source is no longer silently truncated. Separate Adult storage deletion and other STRM resolver callers remain unchanged. Ancillary remote files and empty directories can remain because only indexed source files are selected.

## Checks performed

Java 17. Two initial workflow tests failed against the old implementation. Final checks passed:
- MediaLibraryDeletionWorkflowTest: 9 cases (exact multi-source movie, incomplete source refusal, historical STRM plan refusal, season execution, exact collection sources and existing storage behavior).
- MediaSourceDeletionTest: 2 cases; QuarkMediaDeletionTest: 2 cases.
- EmbyClientTest#deletionLookupPreservesExactSourcesAndRejectsAnIncompleteVersion: 1 case.
- IngestTargetRegistryTest: 4 cases.
- CloudDrive2LibraryOrganizerTest#deletesRegisteredSourcesUsingEachMappingAndRejectsPrefixCollisionsBeforeMutation: 1 case.
- git diff --check passed.

One bounded review found a retained Quark directory hint; removed it and the unused title/season helper, retested workflow and Quark routing successfully. No further review loop.

Earlier read-only production Emby samples (3 Movies, 3 TV episodes, 3 Anime episodes) exposed actual file MediaSources paths. No user-media deletion, scan or playback was invoked as a test.

## Deployment boundary

Base is 64a78a4, which includes the parallel Adult fix and deletion-quarantine rollback. Production source checkout is at this base but the running image is still sha256:071a8346d7c134a7d75599439b08aff9fe90a0327847281dae3d52280ad18988. This task does not advance that shared release branch.

Preflight still reports Movie PENDING 3 / ORGANIZING 2, Series PENDING 2 / ORGANIZING 1, Adult DOWNLOADING 1. Deletion active 0, organization journal COMPLETE 9 / ISOLATED 5, all five OpenList undone queues 0. No new restart/deployment performed because active/unfinished records remain and parallel Adult work is ongoing. A safe deployment window and a deliberately selected expendable sample are required before claiming production deletion acceptance. No new code blocker found.
