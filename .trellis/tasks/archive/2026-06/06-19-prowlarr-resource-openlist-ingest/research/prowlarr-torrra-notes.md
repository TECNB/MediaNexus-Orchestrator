# Prowlarr / torrra-custom Notes

Source inspected:

`/Users/tec/Library/Mobile Documents/com~apple~CloudDocs/torrra-custom`

Do not copy secret values into this task.

## Configuration

`config.private.toml` contains:

```toml
[indexers.prowlarr]
url = <redacted>
api_key = <redacted>
```

Map to Orchestrator `.env`:

```env
MEDIANEXUS_PROWLARR_BASE_URL=<redacted>
MEDIANEXUS_PROWLARR_API_KEY=<redacted>
```

## Prowlarr search behavior in torrra-custom

`src/torrra/indexers/prowlarr.py`:

- search URL: `{url}/api/v1/search`
- request params: `apikey`, `query`
- response is a JSON array
- normalized torrent fields:
  - `title`
  - `size`
  - `seeders`
  - `leechers`
  - `indexer`
  - `magnet_uri = magnetUrl or downloadUrl`

## Magnet resolution behavior

`src/torrra/utils/magnet.py`:

- If value starts with `magnet:`, normalize common malformed forms:
  - `?btih:` → `?xt=urn:btih:`
  - `&btih:` → `&xt=urn:btih:`
  - `?btmh:` → `?xt=urn:btmh:`
  - `&btmh:` → `&xt=urn:btmh:`
- For non-magnet values:
  - perform HTTP GET with redirects handled manually
  - ignore shell proxy environment (`trust_env=False` in torrra)
  - if redirect location is a magnet, return it
  - otherwise follow relative/absolute redirects up to a small limit
  - if response looks like a `.torrent`, convert content to magnet

## Sorting / dedup note

The UI table in torrra removes duplicate `magnet_uri` rows, but the MediaNexus
first slice explicitly does not do release-result deduplication. Preserve
Prowlarr/torrra return order and pick the first result that matches the selected
quality tag.
