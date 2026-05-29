# Fix Anime Mikan Search Failure For Dongpai

## Summary

Fix `GET /api/v1/resources/anime/search?term=冻牌` returning the generic 500
search failure response.

## Goals

- Reproduce the failing request against the running local Orchestrator.
- Identify the concrete upstream response or mapping issue.
- Call this deployment's ani-rss REST route at `/api/mikan`.
- Keep the existing public API contract unchanged.
- Preserve sanitized frontend errors for real upstream/config failures.

## Acceptance Criteria

- `term=冻牌` returns HTTP 200/envelope code 200 with flattened `items` and
  `total`, or an empty successful result if upstream genuinely returns no
  matches.
- No ani-rss secrets are logged or committed.
- No new dependencies are added.
- The project can remain running during and after the fix.

## Verification

- Use focused curl checks for `/api/v1/resources/anime/search?term=冻牌`.
- Do not run full Maven validation unless explicitly requested.
