# Emby source deletion

User approved simplifying deletion from the existing media-library UI. Use Emby MediaSources paths directly; retain existing per-account CD2 path mapping and Quark URL handling. Never read STRM contents to locate media or infer cloud folders from titles. Clean local STRM paths reported by Emby, then notify and verify Emby as before.

Scope: MediaLibraryDeletionWorkflow media-item movie/series/season/collection entry, preserving the separate Adult storage deletion entry and other users of LocalStrmDeletion. No new timeout mechanism, quarantine, framework, or historical task replay.

Reject missing/blank/local STRM-only media source paths before queueing a movie or an episode/member. Persist exact deduplicated media paths. Validate stored paths on execution so historical STRM-only plans cannot read pointers. Empty seasons with no episodes may still clean local indices. Delete collection sources by exact listed files rather than title-derived cloud folders. Remove capacity-based whole-work cloud-folder inference from this entry.

Checks: real workflow mocked external boundary; exact multiple sources, no STRM resolver/capacity inference; incomplete sources refused; season/collection/Quark behavior; existing CD2 mapping checks. Java 17, targeted tests only. Production API read-only samples already confirmed complete file media paths in Movies/TV/Anime. No deletion of real user media for testing. Deployment uses unchanged root script only after checking current parallel release changes and active jobs.
