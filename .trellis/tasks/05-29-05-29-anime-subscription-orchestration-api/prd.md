# Anime Subscription Orchestration API

## Goal

Add Java Orchestrator APIs that wrap ani-rss REST calls for Mikan subtitle groups, subscription preview, duplicate checking, and subscription creation with `enable=true`.

## Decisions

- Implement Mikan only.
- Keep ani-rss API key and base URL inside Orchestrator configuration.
- Use ani-rss REST endpoints, not MCP:
  - `/api/mikanGroup`
  - `/api/rssToAni`
  - `/api/previewAni`
  - `/api/listAni`
  - `/api/addAni`
- Port the language classification and ranking rules from `ani-rss-hermes-skill`.
- Return only Chinese subtitle groups to the frontend.
- Preview endpoint parses RSS to an ANI object, previews items, and returns compact metadata: title, season, preview count, missing episode list/summary, and raw selected group identity.
- Subscribe endpoint must re-parse/re-preview server-side before adding.
- Subscribe endpoint checks duplicates by parsed `title + season`; duplicate returns a non-error success status and does not call `/addAni`.
- Add subscription with `enable=true` so ani-rss triggers refresh/download.
- Do not promise completed downloads.

## API Shape

- `GET /api/v1/resources/anime/{id}/groups?sourceUrl=...`
- `POST /api/v1/resources/anime/preview`
- `POST /api/v1/resources/anime/subscribe`

All responses use the existing `ApiResponse<T>` envelope.

## Non-Goals

- No AniBT or AnimeGarden support.
- No database persistence in Orchestrator.
- No long-running task tracking.
- No full-project validation command.

## Future Optimization

The frontend will initially fan out requests for every Anime search result. A later task may add throttling, cache keys, or first-screen lazy loading.
