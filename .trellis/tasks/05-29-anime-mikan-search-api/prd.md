# Anime Mikan Search API

## Summary

Add the first Anime API capability to `MediaNexus-Orchestrator`: a Java endpoint that searches Mikan through ani-rss REST and returns frontend-friendly Anime search results.

This API powers the frontend task `MediaNexus/.trellis/tasks/05-29-anime-mikan-search`.

## Goals

- Add `GET /api/v1/resources/anime/search?term=...`.
- Call ani-rss REST `/mikan` with API key authentication.
- Flatten ani-rss `weeks[].items[]` into a simple result list.
- Preserve enough metadata for the frontend to render Anime cards.
- Change Orchestrator's success code from `0` to `200` globally.
- Keep the API display-only; do not add subscription/download/group endpoints.

## Non-Goals

- Do not implement Anime subscription creation.
- Do not call `/mikanGroup` in this slice.
- Do not preview RSS items.
- Do not add download or task workflow integration.
- Do not add WebFlux or a new HTTP client dependency.
- Do not change existing Python backend behavior.
- Do not run full-project Maven validation unless explicitly requested.

## Public API

Endpoint:

```text
GET /api/v1/resources/anime/search?term=<keyword>
```

Success response:

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "items": [
      {
        "id": "mikan:1234",
        "title": "Anime Title",
        "cover": "https://...",
        "source_url": "https://mikanime.tv/Home/Bangumi/1234",
        "score": 8.1,
        "exists": false,
        "week_label": "Search"
      }
    ],
    "total": 1
  }
}
```

Field rules:

- `id`: Prefer `mikan:<numeric-id>` parsed from the Mikan URL suffix. If parsing fails, use a stable hash/slug fallback.
- `title`: Trimmed title. Use a consistent fallback for invalid blank upstream title.
- `cover`: Direct Mikan cover URL or `null`.
- `source_url`: Direct Mikan detail URL or `null`.
- `score`: Upstream score or `null`; do not format for display in the backend.
- `exists`: `true` when ani-rss reports the item already exists in subscriptions.
- `week_label`: The source Mikan week/group label from `weeks[]`.
- `total`: Count of flattened returned items.

Do not return `groups` or `seasons` in this slice.

## ani-rss REST Contract

Use REST, not MCP.

Request:

```text
POST {MEDIANEXUS_ANI_RSS_BASE_URL}/mikan?text=<keyword>
x-api-key: <MEDIANEXUS_ANI_RSS_API_KEY>
Content-Type: application/json

{}
```

Notes:

- Do not pass season filters in the first slice.
- Do not call `/mikanGroup`.
- Do not re-sort results. Preserve ani-rss returned order while flattening.
- Do not limit result count.

## Configuration

Add Orchestrator configuration:

```text
MEDIANEXUS_ANI_RSS_BASE_URL=http://example.invalid:7789
MEDIANEXUS_ANI_RSS_API_KEY=
MEDIANEXUS_ANI_RSS_TIMEOUT=10s
```

Implementation notes:

- Use `x-api-key` header, not query parameters, for the API key.
- Do not log the API key.
- Tracked examples may include fake values only.
- Local ignored `.env` may receive real values during implementation if requested.

## Recommended Code Structure

Add light domain structure without creating a multi-module project:

- `config/AniRssProperties.java`
- `integration/anirss/AniRssClient.java`
- `service/AnimeSearchService.java`
- `controller/AnimeResourceController.java`
- `dto/anime/AnimeSearchItem.java`
- `dto/anime/AnimeSearchResponse.java`

Use Java 17 `java.net.http.HttpClient`.

## Response Envelope Change

Change:

```java
SUCCESS(0, "success")
```

to:

```java
SUCCESS(200, "success")
```

This should apply globally to Orchestrator responses. Update README examples accordingly.

## Error Handling

- Blank keyword: return HTTP 400 with envelope code 400 and message `搜索关键词不能为空`.
- Any upstream failure, auth failure, timeout, parse failure, or missing configuration: return a business error with message `动漫搜索失败，请稍后重试`.
- Empty upstream result: return HTTP 200/code 200 with `items: []` and `total: 0`.

Do not leak ani-rss internal text such as auth or parser details to the frontend.

## Acceptance Criteria

- `GET /api/v1/resources/anime/search?term=...` returns code 200 and flattened items.
- Blank `term` returns a 400-style business response.
- ani-rss is called with `POST /mikan?text=...`, body `{}`, and `x-api-key`.
- Returned items preserve ani-rss order.
- Response does not include subtitle groups or seasons.
- `ErrorCode.SUCCESS` and README examples use `200`.
- No secrets are committed to tracked files or logged.
- No new HTTP dependency or WebFlux dependency is added.

## Verification Guidance

Follow project instructions:

- Do not run full-project Maven validation by default.
- Prefer static review and the smallest possible targeted checks.
- Optional live smoke test may call the new endpoint once the service is running and local `.env` has ani-rss credentials.
