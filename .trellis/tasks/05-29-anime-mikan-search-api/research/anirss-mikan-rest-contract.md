# ani-rss Mikan REST Contract Notes

## Source Findings

Reference source path:

```text
/Users/tengenchang/Library/Mobile Documents/com~apple~CloudDocs/ani-rss-hermes/ani-rss
```

Relevant ani-rss endpoints:

```java
@PostMapping("/mikan")
public Result<Mikan> mikan(@RequestParam("text") String text, @RequestBody Mikan.Season season)

@PostMapping("/mikanGroup")
public Result<List<Mikan.Group>> mikanGroup(@RequestParam("url") String url)
```

This task only uses `/mikan`.

## Auth

The ani-rss auth code accepts API key values from headers or params named:

- `api-key`
- `x-api-key`
- `s`

Use the `x-api-key` header from Orchestrator.

## Search Response Shape

Mikan search returns:

- `seasons`
- `weeks`
- `totalItem`

Each `week` includes:

- `weekLabel`
- `items`

Each Mikan item can include:

- `bangumiId`
- `cover`
- `url`
- `exists`
- `score`
- `title`
- `bgmUrl`
- `groups`

Search-list construction primarily provides `cover`, `url`, `exists`, `score`, and `title`. The Orchestrator should derive a stable ID from the URL because `bangumiId` may not be populated on search-list items.

## First-Slice Mapping

Flatten `weeks[].items[]` to:

```json
{
  "id": "mikan:<id>",
  "title": "...",
  "cover": "...",
  "source_url": "...",
  "score": 8.1,
  "exists": false,
  "week_label": "Search"
}
```

Do not return `groups` or `seasons` in the first slice.

## Secret Handling

The user provided a real ani-rss URL and API key in chat. Implementation may write these only to ignored local `.env` files when explicitly needed. Do not write them to tracked task notes, source files, examples, README, or logs.
